package com.notifly.notification.delivery;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a single notification.
 *
 * <p>The legal transitions are declared here rather than scattered across the services that
 * perform them, so that "can this notification go from X to Y" has exactly one answer. Every
 * transition is audited; an unlisted transition is rejected as a programming error rather than
 * silently applied.
 *
 * <pre>
 *   CREATED ─┬─► SCHEDULED ──► QUEUED ──► SENDING ─┬─► SENT ──► DELIVERED
 *            │                    ▲                │
 *            └─► QUEUED           │                ├─► RETRY_SCHEDULED ──┐
 *                                 └────────────────┴────────────────────ˆ┘
 *                                                  └─► FAILED
 *
 *   CANCELLED  : from any non-terminal state, by the tenant
 *   SUPPRESSED : at submission, when the address is on the suppression list
 * </pre>
 */
public enum NotificationStatus {

    /** Row written and content rendered, not yet routed. */
    CREATED,

    /** Deferred until its scheduled time; not claimable yet. */
    SCHEDULED,

    /** Eligible for dispatch and waiting to be claimed by a worker. */
    QUEUED,

    /** Claimed under a lease and currently being handed to a provider. */
    SENDING,

    /** The provider accepted it. Handoff succeeded; final delivery is not yet confirmed. */
    SENT,

    /** The provider confirmed delivery to the recipient. Terminal. */
    DELIVERED,

    /** A transient failure occurred; waiting out a backoff before the next attempt. */
    RETRY_SCHEDULED,

    /** Permanently failed, or exhausted its retry budget. Terminal. */
    FAILED,

    /** Cancelled before dispatch. Terminal. */
    CANCELLED,

    /** Never attempted: the recipient address was suppressed. Terminal. */
    SUPPRESSED;

    private static final Set<NotificationStatus> TERMINAL =
            EnumSet.of(DELIVERED, FAILED, CANCELLED, SUPPRESSED);

    /** States from which a worker may claim the row for dispatch. */
    private static final Set<NotificationStatus> CLAIMABLE =
            EnumSet.of(QUEUED, RETRY_SCHEDULED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isClaimable() {
        return CLAIMABLE.contains(this);
    }

    /** Whether a tenant may still cancel a notification in this state. */
    public boolean isCancellable() {
        return this == CREATED || this == SCHEDULED || this == QUEUED || this == RETRY_SCHEDULED;
    }

    /** Whether {@code target} is a legal next state from this one. */
    public boolean canTransitionTo(NotificationStatus target) {
        if (this == target) {
            return false;
        }
        if (isTerminal()) {
            return false;
        }
        if (target == CANCELLED) {
            return isCancellable();
        }
        return switch (this) {
            case CREATED         -> target == SCHEDULED || target == QUEUED || target == SUPPRESSED;
            case SCHEDULED       -> target == QUEUED;
            case QUEUED          -> target == SENDING;
            case SENDING         -> target == SENT || target == RETRY_SCHEDULED || target == FAILED
                                    || target == QUEUED; // lease expired and was reaped
            case SENT            -> target == DELIVERED || target == FAILED;
            case RETRY_SCHEDULED -> target == QUEUED || target == FAILED;
            default              -> false;
        };
    }
}
