package com.notifly.notification.common.error;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Factory methods for the errors this service raises.
 *
 * <p>Centralised so that a given failure produces the same status, code and wording everywhere it
 * can occur. Scattering {@code new ApiException(...)} across services is how one API ends up
 * returning 404 in one place and 400 in another for the same condition.
 */
public final class Errors {

    private Errors() {
    }

    public static ApiException notFound(String resource, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                resource + " not found")
                .with("resource", resource)
                .with("id", String.valueOf(id));
    }

    public static ApiException alreadyExists(String resource, String field, Object value) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.ALREADY_EXISTS,
                resource + " with this " + field + " already exists")
                .with("resource", resource)
                .with("field", field)
                .with("value", String.valueOf(value));
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, message);
    }

    public static ApiException unauthenticated(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException tenantSuspended(UUID tenantId) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.TENANT_SUSPENDED,
                "Tenant is suspended and cannot submit or dispatch notifications")
                .with("tenantId", String.valueOf(tenantId));
    }

    public static ApiException tenantRequired() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.TENANT_REQUIRED,
                "This operation must be performed in the scope of a tenant");
    }

    public static ApiException rateLimited(long retryAfterSeconds, String scope) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMIT_EXCEEDED,
                "Rate limit exceeded for " + scope)
                .with("retryAfterSeconds", retryAfterSeconds)
                .with("scope", scope);
    }

    public static ApiException idempotencyKeyReused(String key) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key was already used with a different request body")
                .with("idempotencyKey", key);
    }
}
