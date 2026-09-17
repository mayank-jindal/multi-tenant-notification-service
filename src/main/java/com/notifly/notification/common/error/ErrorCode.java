package com.notifly.notification.common.error;

/**
 * Stable, machine-readable error identifiers.
 *
 * <p>Returned in the {@code code} member of every problem document. Clients branch on these, so
 * they are part of the API contract: a value may be added, but an existing one must not change
 * meaning.
 */
public final class ErrorCode {

    // Authentication and authorization
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String TOKEN_INVALID = "TOKEN_INVALID";

    // Request shape
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    // Resources
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ALREADY_EXISTS = "ALREADY_EXISTS";
    public static final String CONFLICT = "CONFLICT";
    public static final String OPTIMISTIC_LOCK = "OPTIMISTIC_LOCK_CONFLICT";

    // Tenancy
    public static final String TENANT_SUSPENDED = "TENANT_SUSPENDED";
    public static final String TENANT_REQUIRED = "TENANT_REQUIRED";

    // Sending
    public static final String CHANNEL_NOT_CONFIGURED = "CHANNEL_NOT_CONFIGURED";
    public static final String CHANNEL_DISABLED = "CHANNEL_DISABLED";
    public static final String TEMPLATE_NOT_PUBLISHED = "TEMPLATE_NOT_PUBLISHED";
    public static final String TEMPLATE_VARIABLE_MISSING = "TEMPLATE_VARIABLE_MISSING";
    public static final String TEMPLATE_VARIABLE_UNDECLARED = "TEMPLATE_VARIABLE_UNDECLARED";
    public static final String RECIPIENT_SUPPRESSED = "RECIPIENT_SUPPRESSED";
    public static final String NOT_CANCELLABLE = "NOT_CANCELLABLE";

    // Throughput
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

    // Idempotency
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    // Catch-all
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCode() {
        // constants only
    }
}
