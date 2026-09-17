package com.notifly.notification.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.util.UUID;

/**
 * Base class for every entity that belongs to exactly one tenant.
 *
 * <p>Carrying {@code tenant_id} here rather than on each subclass is what makes the shared-schema
 * isolation model mechanical: the tenant filter is declared once against this column and applies
 * uniformly, and a new tenant-owned table cannot accidentally be created without it.
 *
 * <p>Associations are held as raw {@link UUID} foreign keys rather than JPA relationships. The
 * dispatcher reads these rows in tight batched loops where an accidental lazy association would
 * turn one query into thousands, and DTO mapping means the object graph is never needed for
 * serialisation anyway.
 */
@MappedSuperclass
public abstract class TenantOwnedEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
