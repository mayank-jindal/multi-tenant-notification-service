package com.notifly.notification.common.tenancy;

import java.util.UUID;

/**
 * Implemented by authentication principals that carry a tenancy.
 *
 * <p>Exists so the tenancy filter does not have to know the concrete principal type, which keeps
 * the isolation mechanism independent of how authentication happens to be implemented.
 */
public interface TenantScoped {

    /** The owning tenant, or {@code null} for a platform administrator. */
    UUID getTenantId();

    boolean isPlatformAdmin();
}
