package com.notifly.notification.dispatch;

import java.util.UUID;

/**
 * How much claimable work one tenant has on one channel, and what share it is entitled to.
 *
 * @param tenantId the tenant
 * @param weight   its dispatch weight; higher earns proportionally more slots under contention
 * @param pending  how many notifications are claimable right now
 */
public record TenantWorkload(UUID tenantId, int weight, long pending) {

    public TenantWorkload {
        if (weight < 1) {
            weight = 1;
        }
    }
}
