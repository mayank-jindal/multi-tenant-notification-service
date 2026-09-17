package com.notifly.notification.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param slug            stable identifier, lowercase and URL-safe
 * @param name            human-readable tenant name
 * @param dispatchWeight  relative share of dispatch capacity under contention
 * @param adminEmail      optional first tenant administrator to create alongside the tenant
 * @param adminPassword   that administrator's initial password
 * @param adminName       that administrator's display name
 */
@Schema(description = "A new tenant, optionally with its first administrator")
public record CreateTenantRequest(

        @Schema(example = "acme", description = "Immutable once created")
        @NotBlank(message = "slug is required")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$",
                message = "must be 3-64 characters, lowercase letters, digits and hyphens, not starting or ending with a hyphen")
        String slug,

        @Schema(example = "Acme Corporation")
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,

        @Schema(example = "1", description = "1-100. Higher weights win proportionally more dispatch slots under contention.")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 100, message = "must be at most 100")
        Integer dispatchWeight,

        @Schema(example = "admin@acme.com", description = "Optional. When supplied, a TENANT_ADMIN is created for the new tenant.")
        @Email(message = "must be a valid email address")
        String adminEmail,

        @Size(min = 8, max = 200, message = "must be between 8 and 200 characters")
        String adminPassword,

        @Size(max = 255, message = "must be at most 255 characters")
        String adminName) {

    /** Whether the caller asked for an administrator to be provisioned with the tenant. */
    public boolean includesAdmin() {
        return adminEmail != null && !adminEmail.isBlank();
    }
}
