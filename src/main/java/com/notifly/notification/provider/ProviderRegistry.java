package com.notifly.notification.provider;

import com.notifly.notification.common.config.DispatchProperties;
import com.notifly.notification.common.model.Channel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the provider adapter for a channel.
 *
 * <p>Adapters discovered as Spring beans take precedence, so adding a real vendor integration is
 * a matter of publishing one bean. A simulator is registered for every channel that has no bean,
 * which keeps the service fully operable out of the box.
 */
@Component
public class ProviderRegistry {

    private final Map<Channel, ChannelProvider> providers = new EnumMap<>(Channel.class);

    public ProviderRegistry(List<ChannelProvider> discovered, DispatchProperties dispatchProperties) {
        for (ChannelProvider provider : discovered) {
            providers.put(provider.channel(), provider);
        }

        for (Channel channel : Channel.values()) {
            providers.computeIfAbsent(channel, c -> {
                DispatchProperties.Simulator settings = dispatchProperties.simulatorFor(c);
                return new SimulatedChannelProvider(c,
                        new SimulatedChannelProvider.SimulatorSettings(
                                settings.minLatencyMs(),
                                settings.maxLatencyMs(),
                                settings.transientFailureRate(),
                                settings.permanentFailureRate()));
            });
        }
    }

    public ChannelProvider forChannel(Channel channel) {
        ChannelProvider provider = providers.get(channel);
        if (provider == null) {
            // Unreachable while every channel gets a simulator, but a missing provider would
            // otherwise surface as a NullPointerException inside a worker thread.
            throw new IllegalStateException("No provider registered for channel " + channel);
        }
        return provider;
    }
}
