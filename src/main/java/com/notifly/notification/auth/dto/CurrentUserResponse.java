package com.notifly.notification.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * @param id          the user's id
 * @param email       the user's email
 * @param displayName human-readable name
 * @param role        PLATFORM_ADMIN or TENANT_ADMIN
 * @param tenantId    owning tenant, null for a platform admin
 * @param tenantSlug  owning tenant's slug, null for a platform admin
 * @param lastLoginAt previous successful login, null on first use
 */
@Schema(description = "The currently authenticated user")
public record CurrentUserResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        UUID tenantId,
        String tenantSlug,
        Instant lastLoginAt) {
}
