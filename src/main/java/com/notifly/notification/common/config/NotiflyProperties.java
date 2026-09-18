package com.notifly.notification.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Application configuration, bound from the {@code notifly.*} namespace.
 *
 * <p>Grouped into one validated tree rather than scattered {@code @Value} injections so that a
 * misconfiguration fails at startup with a clear message, rather than at the first request that
 * happens to touch the missing value.
 *
 * @param security  authentication settings
 * @param bootstrap the first platform administrator, created on an empty database
 */
@Validated
@ConfigurationProperties(prefix = "notifly")
public record NotiflyProperties(
        @Valid @NotNull Security security,
        @Valid @NotNull Bootstrap bootstrap) {

    /**
     * @param jwt        token issuing and verification settings
     * @param encryption at-rest encryption of stored provider credentials
     */
    public record Security(
            @Valid @NotNull Jwt jwt,
            @Valid @NotNull Encryption encryption) {
    }

    /**
     * @param key passphrase for AES-256-GCM encryption of provider credentials
     */
    public record Encryption(@NotBlank String key) {

        /** Marks the checked-in development key, which must never be used outside development. */
        public static final String DEV_KEY_PREFIX = "dev-only-";

        public boolean isDevelopmentKey() {
            return key.startsWith(DEV_KEY_PREFIX);
        }
    }

    /**
     * @param secret         HMAC-SHA signing key; must be at least 32 bytes for HS256
     * @param accessTokenTtl how long an issued token remains valid
     * @param issuer         the {@code iss} claim, verified on every token
     */
    public record Jwt(
            @NotBlank String secret,
            @NotNull Duration accessTokenTtl,
            @NotBlank String issuer) {

        /** Marks the checked-in development key, which must never be used outside development. */
        public static final String DEV_SECRET_PREFIX = "dev-only-";

        public boolean isDevelopmentSecret() {
            return secret.startsWith(DEV_SECRET_PREFIX);
        }
    }

    /**
     * @param adminEmail       login for the seeded platform administrator
     * @param adminPassword    its plaintext password, hashed before storage
     * @param adminDisplayName display name for the seeded account
     */
    public record Bootstrap(
            @NotBlank String adminEmail,
            @NotBlank String adminPassword,
            @NotBlank String adminDisplayName) {
    }
}
