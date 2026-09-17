package com.notifly.notification.audit;

/**
 * Canonical audit event names.
 *
 * <p>Kept as constants rather than an enum because the column is a free-form string: an
 * unrecognised event type from a future version must still be storable and readable, which an
 * enum column would make impossible without a migration.
 */
public final class AuditEventType {

    // Tenant lifecycle
    public static final String TENANT_CREATED = "TENANT_CREATED";
    public static final String TENANT_UPDATED = "TENANT_UPDATED";
    public static final String TENANT_SUSPENDED = "TENANT_SUSPENDED";
    public static final String TENANT_ACTIVATED = "TENANT_ACTIVATED";

    // Users
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_DISABLED = "USER_DISABLED";
    public static final String USER_LOGIN = "USER_LOGIN";
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";

    // Channel configuration
    public static final String CHANNEL_CONFIG_UPDATED = "CHANNEL_CONFIG_UPDATED";
    public static final String CHANNEL_ENABLED = "CHANNEL_ENABLED";
    public static final String CHANNEL_DISABLED = "CHANNEL_DISABLED";

    // Templates
    public static final String TEMPLATE_CREATED = "TEMPLATE_CREATED";
    public static final String TEMPLATE_UPDATED = "TEMPLATE_UPDATED";
    public static final String TEMPLATE_ARCHIVED = "TEMPLATE_ARCHIVED";
    public static final String TEMPLATE_VERSION_CREATED = "TEMPLATE_VERSION_CREATED";
    public static final String TEMPLATE_VERSION_PUBLISHED = "TEMPLATE_VERSION_PUBLISHED";

    // Rate limits
    public static final String RATE_LIMIT_POLICY_CREATED = "RATE_LIMIT_POLICY_CREATED";
    public static final String RATE_LIMIT_POLICY_UPDATED = "RATE_LIMIT_POLICY_UPDATED";
    public static final String RATE_LIMIT_POLICY_DELETED = "RATE_LIMIT_POLICY_DELETED";
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

    // Suppressions
    public static final String SUPPRESSION_ADDED = "SUPPRESSION_ADDED";
    public static final String SUPPRESSION_REMOVED = "SUPPRESSION_REMOVED";

    // Send pipeline
    public static final String REQUEST_ACCEPTED = "REQUEST_ACCEPTED";
    public static final String REQUEST_CANCELLED = "REQUEST_CANCELLED";
    public static final String REQUEST_REPLAYED = "REQUEST_REPLAYED";

    // Notification lifecycle — the state-transition half of the trail
    public static final String NOTIFICATION_TRANSITION = "NOTIFICATION_TRANSITION";
    public static final String NOTIFICATION_ATTEMPT_STARTED = "NOTIFICATION_ATTEMPT_STARTED";
    public static final String NOTIFICATION_ATTEMPT_COMPLETED = "NOTIFICATION_ATTEMPT_COMPLETED";
    public static final String NOTIFICATION_RETRY_SCHEDULED = "NOTIFICATION_RETRY_SCHEDULED";
    public static final String NOTIFICATION_DEAD_LETTERED = "NOTIFICATION_DEAD_LETTERED";
    public static final String NOTIFICATION_REPLAYED = "NOTIFICATION_REPLAYED";
    public static final String NOTIFICATION_LEASE_EXPIRED = "NOTIFICATION_LEASE_EXPIRED";
    public static final String NOTIFICATION_SUPPRESSED = "NOTIFICATION_SUPPRESSED";
    public static final String NOTIFICATION_READ = "NOTIFICATION_READ";

    private AuditEventType() {
        // constants only
    }
}
