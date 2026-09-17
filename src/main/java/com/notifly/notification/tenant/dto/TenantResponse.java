package com.notifly.notification.tenant.dto;

import com.notifly.notification.tenant.Tenant;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * @param id             the tenant's id
 * @param slug           stable identifier used in tokens and admin URLs
 * @param name           human-readable name
 * @param status         ACTIVE or SUSPENDED
 * @param dispatchWeight relative share of dispatch capacity under contention
 * @param createdAt      when the tenant was created
 * @param updatedAt      when it was last modified
 */
@Schema(description = "A tenant")
public record TenantResponse(
        UUID id,
        String slug,
        String name,
        String status,
        int dispatchWeight,
        Instant createdAt,
        Instant updatedAt) {

    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getName(),
                tenant.getStatus().name(),
                tenant.getDispatchWeight(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt());
    }
}
