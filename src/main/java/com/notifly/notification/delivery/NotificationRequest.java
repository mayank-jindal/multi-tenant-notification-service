package com.notifly.notification.delivery;

import com.notifly.notification.common.model.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One accepted API submission, before expansion into per-recipient work.
 *
 * <p>Kept separate from {@link Notification} so the record of what was asked for survives
 * independently of how it was fanned out: a bulk send to 10,000 recipients is one intent and
 * 10,000 work items, and reporting needs to speak about both.
 */
@Entity
@Table(name = "notification_requests")
public class NotificationRequest extends TenantOwnedEntity {

    @Column(name = "template_id")
    private UUID templateId;

    /**
     * Pinned at submission. Resolving the template again later could pick up a newer published
     * version and change the meaning of an in-flight request.
     */
    @Column(name = "template_version_id")
    private UUID templateVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> variables = new HashMap<>();

    /** Null means dispatch immediately. */
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private NotificationRequestStatus status = NotificationRequestStatus.ACCEPTED;

    @Column(name = "notification_count", nullable = false)
    private int notificationCount = 0;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    /** Retained for traceability; the authoritative de-duplication record is the key table. */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    protected NotificationRequest() {
        // for JPA
    }

    public NotificationRequest(UUID templateId, UUID templateVersionId) {
        this.templateId = templateId;
        this.templateVersionId = templateVersionId;
    }

    public boolean isScheduled() {
        return scheduledAt != null;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public UUID getTemplateVersionId() {
        return templateVersionId;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new HashMap<>() : new HashMap<>(variables);
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public NotificationRequestStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationRequestStatus status) {
        this.status = status;
    }

    public int getNotificationCount() {
        return notificationCount;
    }

    public void setNotificationCount(int notificationCount) {
        this.notificationCount = notificationCount;
    }

    public UUID getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(UUID submittedBy) {
        this.submittedBy = submittedBy;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
