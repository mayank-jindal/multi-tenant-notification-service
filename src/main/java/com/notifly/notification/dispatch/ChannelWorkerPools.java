package com.notifly.notification.dispatch;

import com.notifly.notification.common.config.DispatchProperties;
import com.notifly.notification.common.model.Channel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One bounded worker pool per channel.
 *
 * <p>Bounded in both dimensions, deliberately: a fixed thread count, and a fixed queue in front
 * of it. The requirement asks for bounded pools, and the reason is backpressure — when a channel
 * saturates, work must stay in the database rather than piling up in memory. An unbounded queue
 * would accept every claim, report healthy, and then lose the entire backlog to an
 * {@code OutOfMemoryError}.
 *
 * <p>Platform threads rather than virtual threads. Virtual threads are unbounded by nature, which
 * is the opposite of what is wanted here: the pool exists to limit how much concurrent load
 * reaches a rate-limited provider, and "spawn one per task" would remove the limit.
 *
 * <p>Separate pools per channel so that one slow provider cannot consume the capacity of the
 * others. An email provider timing out at thirty seconds must not stop SMS from being delivered.
 */
@Component
public class ChannelWorkerPools {

    private static final Logger log = LoggerFactory.getLogger(ChannelWorkerPools.class);

    private final Map<Channel, ThreadPoolExecutor> pools = new EnumMap<>(Channel.class);

    public ChannelWorkerPools(DispatchProperties properties) {
        for (Channel channel : Channel.values()) {
            int workers = properties.workersFor(channel);
            pools.put(channel, buildPool(channel, workers, properties.queueCapacity()));
            log.info("Dispatch pool for {}: {} workers, queue capacity {}",
                    channel, workers, properties.queueCapacity());
        }
    }

    private ThreadPoolExecutor buildPool(Channel channel, int workers, int queueCapacity) {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "dispatch-" + channel.name().toLowerCase() + "-" + counter.getAndIncrement());
                // Daemon so a shutdown is never held open by an idle worker.
                thread.setDaemon(true);
                return thread;
            }
        };

        return new ThreadPoolExecutor(
                workers, workers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                // Abort rather than caller-runs. Running the task on the coordinator thread would
                // stall the poller for every channel, and the work is safe to refuse: the
                // notification stays leased, the lease expires, and the reaper requeues it.
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Submits work, reporting whether the pool accepted it.
     *
     * @return false if the pool is saturated, in which case the caller must stop claiming
     */
    public boolean submit(Channel channel, Runnable task) {
        try {
            pools.get(channel).execute(task);
            return true;
        } catch (RejectedExecutionException ex) {
            log.warn("Dispatch pool for {} is saturated; leaving work in the database", channel);
            return false;
        }
    }

    /** Free capacity right now: how many more tasks this channel can take before it is full. */
    public int availableCapacity(Channel channel) {
        ThreadPoolExecutor pool = pools.get(channel);
        return pool.getQueue().remainingCapacity();
    }

    /** How many tasks are executing on this channel. */
    public int activeCount(Channel channel) {
        return pools.get(channel).getActiveCount();
    }

    /** Configured worker count, for reporting and for tests asserting the bound holds. */
    public int poolSize(Channel channel) {
        return pools.get(channel).getMaximumPoolSize();
    }

    @PreDestroy
    void shutDown() {
        pools.forEach((channel, pool) -> {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    // In-flight sends are abandoned rather than waited on indefinitely. Their
                    // leases expire and the reaper requeues them on the next startup, which is
                    // exactly the crash-recovery path the lease exists for.
                    log.warn("Dispatch pool for {} did not drain; {} tasks abandoned to lease recovery",
                            channel, pool.shutdownNow().size());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        });
    }
}
