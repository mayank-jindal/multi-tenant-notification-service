package com.notifly.notification.user.dto;

import com.notifly.notification.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A user, without any credential material.
 *
 * @param id          the user's id
 * @param email       login address
 * @param displayName human-readable name
 * @param role        PLATFORM_ADMIN or TENANT_ADMIN
 * @param status      ACTIVE or DISABLED
 * @param tenantId    owning tenant, null for a platform admin
 * @param lastLoginAt last successful login, null if never
 * @param createdAt   when the account was created
 */
@Schema(description = "A user account")
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        String status,
        UUID tenantId,
        Instant lastLoginAt,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getTenantId(),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }
}
