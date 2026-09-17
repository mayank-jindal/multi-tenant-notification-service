package com.notifly.notification.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * @param accessToken the bearer token to send as {@code Authorization: Bearer <token>}
 * @param tokenType   always {@code Bearer}
 * @param expiresIn   seconds until the token expires
 * @param expiresAt   absolute expiry instant
 * @param user        who the token authenticates
 */
@Schema(description = "An issued access token and the identity it represents")
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        CurrentUser user) {

    /**
     * @param id          the user's id
     * @param email       the user's email
     * @param displayName human-readable name
     * @param role        PLATFORM_ADMIN or TENANT_ADMIN
     * @param tenantId    owning tenant, null for a platform admin
     */
    @Schema(description = "The authenticated identity")
    public record CurrentUser(
            UUID id,
            String email,
            String displayName,
            String role,
            UUID tenantId) {
    }
}
