package com.notifly.notification.common.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Feeds the current {@link TenantScope} to Hibernate.
 *
 * <p>Hibernate calls this when opening a session and uses the result to filter every query and
 * every {@code find()} against the {@code @TenantId} column, and to populate that column on
 * insert. This class is therefore the single point at which tenant isolation is decided — which
 * is precisely why it contains no logic beyond reading the scope that was already established.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.currentTenantId();
    }

    /**
     * Whether this identifier means "unrestricted".
     *
     * <p>Returning true makes Hibernate skip discriminator filtering altogether, so the condition
     * is intentionally the narrowest possible one: an exact match on the root sentinel, which
     * only {@link TenantScope#root()} produces and which the request filter grants only to a
     * platform administrator.
     */
    @Override
    public boolean isRoot(UUID tenantId) {
        return TenantScope.ROOT.equals(tenantId);
    }

    /**
     * False because sessions are opened per transaction and never shared across tenant scopes,
     * so there is no existing session whose tenant could have drifted.
     */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
