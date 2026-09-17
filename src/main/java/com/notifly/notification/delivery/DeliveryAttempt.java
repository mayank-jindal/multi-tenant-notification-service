package com.notifly.notification.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * An append-only record of one provider interaction.
 *
 * <p>The row is written <em>before</em> the provider is called and completed afterwards. That
 * ordering is deliberate: if the worker dies mid-send, the evidence that an attempt happened
 * survives, and the reaper can mark it {@link DeliveryOutcome#ABANDONED} instead of the system
 * quietly believing the message was never tried.
 *
 * <p>Does not extend the mutable entity base class — attempts are never updated after completion
 * and never deleted, so an update timestamp and version column would imply a mutability that is
 * not there.
 */
@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    /** Null while the attempt is still in flight. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 24)
    private DeliveryOutcome outcome;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DeliveryAttempt() {
        // for JPA
    }

    public DeliveryAttempt(UUID tenantId, UUID notificationId, int attemptNumber, String providerCode) {
        this.tenantId = tenantId;
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.providerCode = providerCode;
        this.startedAt = Instant.now();
    }

    /** Completes the attempt, deriving latency from the recorded start. */
    public void complete(DeliveryOutcome outcome, Instant at) {
        this.outcome = outcome;
        this.completedAt = at;
        this.latencyMs = (int) Math.min(Integer.MAX_VALUE, Duration.between(startedAt, at).toMillis());
    }

    public void completeWithError(DeliveryOutcome outcome, String errorCode, String errorMessage, Instant at) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        complete(outcome, at);
    }

    public boolean isInFlight() {
        return outcome == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public DeliveryOutcome getOutcome() {
        return outcome;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public void setProviderMessageId(String providerMessageId) {
        this.providerMessageId = providerMessageId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
