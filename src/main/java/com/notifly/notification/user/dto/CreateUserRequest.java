package com.notifly.notification.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email       login address, unique across the whole platform
 * @param password    initial password
 * @param displayName human-readable name
 */
@Schema(description = "A new tenant administrator")
public record CreateUserRequest(

        @Schema(example = "ops@acme.com")
        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email address")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        @Schema(description = "At least 8 characters")
        @NotBlank(message = "password is required")
        @Size(min = 8, max = 200, message = "must be between 8 and 200 characters")
        String password,

        @Schema(example = "Acme Operations")
        @NotBlank(message = "displayName is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String displayName) {
}
