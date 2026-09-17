package com.notifly.notification.suppression;

/** Why an address is on the suppression list. */
public enum SuppressionReason {

    /** The provider reported the address does not exist. Permanent. */
    HARD_BOUNCE,

    /** The recipient marked a message as spam. Permanent, and legally significant. */
    COMPLAINT,

    /** The recipient opted out. */
    UNSUBSCRIBED,

    /** The address failed validation at the provider. */
    INVALID_ADDRESS,

    /** Added by a tenant admin. */
    MANUAL
}
