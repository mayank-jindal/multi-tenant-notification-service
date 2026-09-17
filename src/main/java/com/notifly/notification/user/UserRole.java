package com.notifly.notification.user;

/**
 * The two roles defined by the requirement.
 *
 * <p>{@code ROLE_} prefixed authorities are what Spring Security's {@code hasRole} expressions
 * expect; the prefix is added at authentication time rather than stored, so the database holds
 * the clean name.
 */
public enum UserRole {

    /** Manages tenants and platform-wide limits. Belongs to no tenant. */
    PLATFORM_ADMIN,

    /** Manages one tenant's templates, channel configuration and delivery reports. */
    TENANT_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
