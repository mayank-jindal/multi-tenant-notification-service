package com.notifly.notification.tenant;

/** Lifecycle state of a tenant. */
public enum TenantStatus {

    /** Normal operation: submissions accepted, dispatch running. */
    ACTIVE,

    /**
     * Suspended by a platform admin. New submissions are rejected, and already-queued work
     * stops being claimed — it is held rather than failed, so reactivating a tenant resumes
     * their backlog instead of losing it.
     */
    SUSPENDED
}
