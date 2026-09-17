package com.notifly.notification.audit;

/** Whether an audited action was taken by a person or by the platform itself. */
public enum ActorKind {

    /** An authenticated platform or tenant admin. */
    USER,

    /**
     * The dispatcher, schedulers or lease reaper. These have no principal, and pretending
     * otherwise would make the trail lie about who did what.
     */
    SYSTEM
}
