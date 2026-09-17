package com.notifly.notification.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One entry in the append-only audit trail.
 *
 * <p>Covers both halves of the requirement: notification state transitions and administrative
 * configuration changes. Nothing here is ever updated or deleted — a correction is a new event.
 *
 * <p>The acting user is stored as a plain id and a denormalised email rather than a foreign key,
 * so that deleting a user cannot erase or obscure the record of what they did.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null for platform-scoped actions, which happen outside any single tenant. */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Column(name = "entity_type", nullable = false, length = 64, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @Column(name = "from_state", length = 24, updatable = false)
    private String fromState;

    @Column(name = "to_state", length = 24, updatable = false)
    private String toState;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_email", updatable = false)
    private String actorEmail;

    @Column(name = "actor_role", length = 32, updatable = false)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 16, updatable = false)
    private ActorKind actorKind = ActorKind.USER;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details = new HashMap<>();

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    protected AuditEvent() {
        // for JPA
    }

    public AuditEvent(String eventType, String entityType, UUID entityId) {
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getFromState() {
        return fromState;
    }

    public void setFromState(String fromState) {
        this.fromState = fromState;
    }

    public String getToState() {
        return toState;
    }

    public void setToState(String toState) {
        this.toState = toState;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(UUID actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public void setActorEmail(String actorEmail) {
        this.actorEmail = actorEmail;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public ActorKind getActorKind() {
        return actorKind;
    }

    public void setActorKind(ActorKind actorKind) {
        this.actorKind = actorKind;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details == null ? new HashMap<>() : new HashMap<>(details);
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
