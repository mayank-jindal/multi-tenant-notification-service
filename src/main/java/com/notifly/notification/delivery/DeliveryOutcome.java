package com.notifly.notification.delivery;

/**
 * Result of one provider interaction.
 *
 * <p>The transient/permanent distinction is the entire basis of the retry policy: retrying a
 * permanent failure wastes capacity and, for something like an invalid address, will never
 * succeed no matter how many times it is attempted.
 */
public enum DeliveryOutcome {

    /** The provider accepted the message. */
    SUCCESS,

    /** Timeout, throttling, or a 5xx — worth retrying after a backoff. */
    TRANSIENT_FAILURE,

    /** Invalid address, rejected content, revoked token — retrying cannot help. */
    PERMANENT_FAILURE,

    /**
     * The attempt never completed: its worker died mid-send and the lease reaper found the row.
     * Recorded rather than deleted, because the provider may or may not have received it — this
     * is the one window where at-least-once can become a visible duplicate.
     */
    ABANDONED;

    public boolean isRetryable() {
        return this == TRANSIENT_FAILURE || this == ABANDONED;
    }
}
