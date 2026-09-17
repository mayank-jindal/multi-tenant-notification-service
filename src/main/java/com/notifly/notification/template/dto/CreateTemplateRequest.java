package com.notifly.notification.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param code          stable identifier quoted when sending, unique within the tenant
 * @param name          human-readable name
 * @param description   what this template is for
 * @param initialVersion optional first draft, created in the same transaction
 */
@Schema(description = "A new template, optionally with its first draft version")
public record CreateTemplateRequest(

        @Schema(example = "order-shipped", description = "Immutable once created")
        @NotBlank(message = "code is required")
        @Pattern(regexp = "^[a-z0-9][a-z0-9_.-]{1,62}[a-z0-9]$",
                message = "must be 3-64 characters, lowercase letters, digits, dots, underscores and hyphens, not starting or ending with a separator")
        String code,

        @Schema(example = "Order shipped notification")
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,

        @Size(max = 2000, message = "must be at most 2000 characters")
        String description,

        @Valid
        @Schema(description = "Optional. When supplied, draft version 1 is created alongside the template.")
        CreateVersionRequest initialVersion) {
}
