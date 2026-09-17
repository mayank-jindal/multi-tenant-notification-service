package com.notifly.notification.delivery;

/** Lifecycle of an API submission, aggregated from the notifications it produced. */
public enum NotificationRequestStatus {

    /** Accepted and expanded into notifications ready for immediate dispatch. */
    ACCEPTED,

    /** Accepted but held until its scheduled time. */
    SCHEDULED,

    /** At least one notification is still in flight. */
    IN_PROGRESS,

    /** Every notification reached a terminal state. */
    COMPLETED,

    /** Cancelled by the tenant before all of its notifications were dispatched. */
    CANCELLED
}
