package com.notifly.notification.common.model;

/**
 * Delivery channels supported by the platform.
 *
 * <p>The enum name is persisted verbatim and is mirrored by a {@code CHECK} constraint on every
 * table that stores a channel, so adding a value here requires a migration.
 */
public enum Channel {

    /** Email, rendered from subject + HTML + plain-text bodies. */
    EMAIL,

    /** SMS, plain text only and length-sensitive. */
    SMS,

    /** Mobile push, rendered as title + body. */
    PUSH,

    /**
     * In-app messages. Unlike the other channels there is no external provider: "delivery"
     * means the row became visible in the recipient's inbox.
     */
    IN_APP;

    /** Whether this channel is delivered by writing to our own store rather than calling out. */
    public boolean isInternal() {
        return this == IN_APP;
    }
}
