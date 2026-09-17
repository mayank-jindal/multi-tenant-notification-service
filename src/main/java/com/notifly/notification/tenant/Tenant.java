package com.notifly.notification.tenant;

import com.notifly.notification.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A customer of the platform and the root of the ownership graph.
 *
 * <p>Tenants are the one entity that is not itself tenant-owned, which is why this extends
 * {@link BaseEntity} rather than {@code TenantOwnedEntity}.
 */
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    @Column(name = "slug", nullable = false, length = 64, updatable = false)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TenantStatus status = TenantStatus.ACTIVE;

    /**
     * Relative share of dispatch capacity under contention. Two tenants with weights 3 and 1
     * receive roughly three claim slots to one when both have a backlog; it does not cap
     * throughput when there is no contention.
     */
    @Column(name = "dispatch_weight", nullable = false)
    private int dispatchWeight = 1;

    protected Tenant() {
        // for JPA
    }

    public Tenant(String slug, String name) {
        this.slug = slug;
        this.name = name;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }

    public int getDispatchWeight() {
        return dispatchWeight;
    }

    public void setDispatchWeight(int dispatchWeight) {
        this.dispatchWeight = dispatchWeight;
    }
}
