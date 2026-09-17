package com.notifly.notification.template;

import com.notifly.notification.common.model.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An immutable snapshot of a template's content and variable contract.
 *
 * <p>Immutable once published: the publish transition is the point after which the row is only
 * ever archived, never edited. Draft versions remain editable so a tenant can iterate before
 * committing.
 */
@Entity
@Table(name = "template_versions")
public class TemplateVersion extends TenantOwnedEntity {

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TemplateVersionStatus status = TemplateVersionStatus.DRAFT;

    /**
     * The variables this version declares. Rendering is strict, so this list is the contract a
     * send request is validated against before any notification rows are written — an undeclared
     * or missing required variable fails the submission rather than producing a half-rendered
     * message at dispatch time.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables_schema", nullable = false, columnDefinition = "jsonb")
    private List<TemplateVariable> variables = new ArrayList<>();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected TemplateVersion() {
        // for JPA
    }

    public TemplateVersion(UUID templateId, int versionNumber) {
        this.templateId = templateId;
        this.versionNumber = versionNumber;
    }

    public boolean isPublished() {
        return status == TemplateVersionStatus.PUBLISHED;
    }

    public boolean isEditable() {
        return status == TemplateVersionStatus.DRAFT;
    }

    public void publish(Instant at) {
        this.status = TemplateVersionStatus.PUBLISHED;
        this.publishedAt = at;
    }

    public void archive() {
        this.status = TemplateVersionStatus.ARCHIVED;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public TemplateVersionStatus getStatus() {
        return status;
    }

    public List<TemplateVariable> getVariables() {
        return variables;
    }

    public void setVariables(List<TemplateVariable> variables) {
        this.variables = variables == null ? new ArrayList<>() : new ArrayList<>(variables);
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
