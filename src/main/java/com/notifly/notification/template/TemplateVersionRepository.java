package com.notifly.notification.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, UUID> {

    /** Resolves the single renderable version of a template. */
    Optional<TemplateVersion> findByTemplateIdAndStatus(UUID templateId, TemplateVersionStatus status);

    Optional<TemplateVersion> findByTemplateIdAndVersionNumber(UUID templateId, int versionNumber);

    Optional<TemplateVersion> findByIdAndTenantId(UUID id, UUID tenantId);

    List<TemplateVersion> findAllByTemplateIdOrderByVersionNumberDesc(UUID templateId);

    /**
     * Next version number for a template. Returns 1 for a template that has none yet, so the
     * caller never has to special-case the first version.
     */
    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) + 1 FROM TemplateVersion v WHERE v.templateId = :templateId")
    int nextVersionNumber(@Param("templateId") UUID templateId);
}
