package com.notifly.notification.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email    the account's email address
 * @param password the account's plaintext password
 */
@Schema(description = "Credentials for obtaining an access token")
public record LoginRequest(

        @Schema(example = "admin@notifly.io")
        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email address")
        String email,

        @Schema(example = "admin123")
        @NotBlank(message = "password is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String password) {
}
