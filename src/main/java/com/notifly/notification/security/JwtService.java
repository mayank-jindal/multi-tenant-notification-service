package com.notifly.notification.security;

import com.notifly.notification.common.config.NotiflyProperties;
import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies access tokens.
 *
 * <p>The token carries the tenant id as a claim, and that claim is the sole source of a request's
 * tenancy. This is what makes tenancy unspoofable: a client can send any {@code X-Tenant-ID}
 * header it likes and it will be ignored, because changing the tenant would require forging a
 * signature.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_TENANT_ID = "tid";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";

    private final NotiflyProperties.Jwt config;
    private final Environment environment;
    private final SecretKey signingKey;

    public JwtService(NotiflyProperties properties, Environment environment) {
        this.config = properties.security().jwt();
        this.environment = environment;
        this.signingKey = Keys.hmacShaKeyFor(config.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Refuses to start with the checked-in development key outside development.
     *
     * <p>A default secret that ships to production is not a hypothetical: it is one of the most
     * common ways a service like this is compromised. Failing at startup is the only reliable
     * time to catch it, because nothing about a request will ever look wrong.
     */
    @PostConstruct
    void rejectDevelopmentSecretOutsideDevelopment() {
        if (!config.isDevelopmentSecret()) {
            return;
        }
        boolean productionLike = environment.matchesProfiles("prod", "production", "staging");
        if (productionLike) {
            throw new IllegalStateException(
                    "The development JWT secret is in use under a production profile. "
                    + "Set the JWT_SECRET environment variable to a strong random value.");
        }
        log.warn("Using the development JWT signing key. Set JWT_SECRET before any real deployment.");
    }

    /** Issues an access token for a freshly authenticated principal. */
    public IssuedToken issue(UserPrincipal principal) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(config.accessTokenTtl());

        var builder = Jwts.builder()
                .issuer(config.issuer())
                .subject(principal.userId().toString())
                .claim(CLAIM_EMAIL, principal.email())
                .claim(CLAIM_ROLE, principal.role().name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString());

        // Absent rather than null for a platform admin: the claim's absence is what says
        // "belongs to no tenant", and a null would be indistinguishable from a parsing failure.
        if (principal.tenantId() != null) {
            builder.claim(CLAIM_TENANT_ID, principal.tenantId().toString());
        }

        return new IssuedToken(builder.signWith(signingKey).compact(), expiresAt,
                config.accessTokenTtl().toSeconds());
    }

    /**
     * Verifies a token and rebuilds the principal from its claims.
     *
     * @throws com.notifly.notification.common.error.ApiException if the token is expired,
     *                                                            tampered with, or malformed
     */
    public UserPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(config.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tenantClaim = claims.get(CLAIM_TENANT_ID, String.class);
            UserRole role = UserRole.valueOf(claims.get(CLAIM_ROLE, String.class));

            return new UserPrincipal(
                    UUID.fromString(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    tenantClaim == null ? null : UUID.fromString(tenantClaim),
                    role,
                    true);

        } catch (ExpiredJwtException ex) {
            throw Errors.unauthenticated(ErrorCode.TOKEN_EXPIRED, "Access token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            // Covers bad signatures, malformed tokens and unknown role values alike. The client
            // is told nothing about which, because the distinction only helps an attacker.
            throw Errors.unauthenticated(ErrorCode.TOKEN_INVALID, "Access token is not valid");
        }
    }

    /**
     * @param token     the signed compact JWT
     * @param expiresAt absolute expiry, for clients that prefer it to a duration
     * @param expiresIn seconds until expiry
     */
    public record IssuedToken(String token, Instant expiresAt, long expiresIn) {
    }
}
