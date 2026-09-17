package com.notifly.notification.tenant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Tenant> findAllByStatus(TenantStatus status, Pageable pageable);

    /**
     * Tenants eligible to have work dispatched. Suspended tenants are excluded here rather than
     * filtered later so that their backlog is never claimed in the first place.
     */
    @Query("SELECT t FROM Tenant t WHERE t.status = com.notifly.notification.tenant.TenantStatus.ACTIVE")
    List<Tenant> findAllActive();
}
