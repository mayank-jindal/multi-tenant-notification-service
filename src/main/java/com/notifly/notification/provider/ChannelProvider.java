package com.notifly.notification.provider;

import com.notifly.notification.common.model.Channel;

/**
 * Sends a message on one channel.
 *
 * <p>The seam where a real vendor integration would drop in. The simulators behind it exist
 * because real providers need credentials no assessor has, introduce network flakiness into the
 * test suite, and make the retry and backoff behaviour — the part of this service that is
 * actually interesting — impossible to demonstrate on demand.
 *
 * <p>Implementations must not throw for delivery failures. A failure is a
 * {@link ProviderResult} carrying the transient/permanent judgement, because that judgement needs
 * provider knowledge the dispatcher does not have. An exception escaping an adapter is treated as
 * a transient failure, which is the safe default but loses that information.
 */
public interface ChannelProvider {

    /** Which channel this adapter handles. */
    Channel channel();

    /**
     * Stable identifier matching {@code tenant_channel_configs.provider_code}, so a tenant can
     * select among several adapters for the same channel.
     */
    String providerCode();

    /**
     * Attempts delivery.
     *
     * <p>Called on a bounded worker thread with no transaction open and no tenant scope
     * established — an adapter must not touch the database.
     */
    ProviderResult send(OutboundMessage message);
}
