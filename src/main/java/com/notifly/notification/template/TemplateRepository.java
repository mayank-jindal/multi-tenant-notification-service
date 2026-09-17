package com.notifly.notification.template;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    Optional<Template> findByTenantIdAndCode(UUID tenantId, String code);

    Optional<Template> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);

    Page<Template> findAllByTenantId(UUID tenantId, Pageable pageable);

    Page<Template> findAllByTenantIdAndArchived(UUID tenantId, boolean archived, Pageable pageable);
}
