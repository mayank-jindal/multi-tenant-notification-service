package com.notifly.notification.common.tenancy;

import java.util.UUID;

/**
 * The tenancy in force for the current unit of work.
 *
 * @param tenantId      the tenant whose data is visible, or {@link #ROOT} for unrestricted access
 * @param platformAdmin whether the acting principal is a platform administrator
 */
public record TenantScope(UUID tenantId, boolean platformAdmin) {

    /**
     * Unrestricted access. Hibernate treats this as the root tenant and skips discriminator
     * filtering entirely, so it must only ever be granted to a platform administrator.
     */
    public static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /**
     * No tenancy. Deliberately a real value that matches no row rather than {@code null}: the
     * default for an unauthenticated or unscoped thread has to be "sees nothing", and a null
     * would instead throw at query time or, worse, be mistaken for root.
     */
    public static final UUID NONE = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    /** The scope applied when nothing has been established. Sees no tenant data at all. */
    public static final TenantScope UNSCOPED = new TenantScope(NONE, false);

    public TenantScope {
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant scope id must not be null; use TenantScope.NONE");
        }
    }

    /** Scope for a tenant user, or for a platform admin operating inside one tenant. */
    public static TenantScope of(UUID tenantId) {
        return new TenantScope(tenantId, false);
    }

    /** Unrestricted scope. Only legitimate for a platform administrator. */
    public static TenantScope root() {
        return new TenantScope(ROOT, true);
    }

    /** A platform admin reading one tenant's data through a scoped endpoint. */
    public static TenantScope platformAdminActingOn(UUID tenantId) {
        return new TenantScope(tenantId, true);
    }

    public boolean isRoot() {
        return ROOT.equals(tenantId);
    }

    public boolean isUnscoped() {
        return NONE.equals(tenantId);
    }

    /** The tenant id, rejecting the sentinels that are not real tenants. */
    public UUID requireRealTenant() {
        if (isRoot() || isUnscoped()) {
            throw new IllegalStateException("No tenant is in scope for this operation");
        }
        return tenantId;
    }
}
