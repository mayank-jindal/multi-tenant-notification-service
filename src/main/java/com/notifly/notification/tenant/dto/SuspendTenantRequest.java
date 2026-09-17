package com.notifly.notification.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * @param reason why the tenant is being suspended, recorded in the audit trail
 */
@Schema(description = "Optional context recorded alongside a suspension")
public record SuspendTenantRequest(

        @Schema(example = "Payment overdue since 2026-08-01")
        @Size(max = 500, message = "must be at most 500 characters")
        String reason) {
}
