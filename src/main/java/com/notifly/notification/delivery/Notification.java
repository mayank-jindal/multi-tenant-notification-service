package com.notifly.notification.delivery;

import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.model.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One message to one recipient on one channel — the unit of work the dispatcher claims.
 *
 * <p>Content is rendered at submission and frozen here rather than rendered at dispatch time.
 * That costs storage, and buys two things: a template edit cannot change what an already-queued
 * message says, and a delivery record remains explicable long after the template moved on.
 *
 * <p>State changes go through {@link #transitionTo} rather than a setter, so that every
 * transition is checked against the state machine and stamped consistently. Callers are still
 * responsible for writing the corresponding audit event.
 */
@Entity
@Table(name = "notifications")
public class Notification extends TenantOwnedEntity {

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private Channel channel;

    @Column(name = "recipient_address", nullable = false, length = 512, updatable = false)
    private String recipientAddress;

    /** The tenant's own identifier for the recipient; what the in-app inbox is queried by. */
    @Column(name = "recipient_ref", updatable = false)
    private String recipientRef;

    @Column(name = "rendered_subject", length = 998)
    private String renderedSubject;

    @Column(name = "rendered_body_text", columnDefinition = "text")
    private String renderedBodyText;

    @Column(name = "rendered_body_html", columnDefinition = "text")
    private String renderedBodyHtml;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private NotificationStatus status = NotificationStatus.CREATED;

    /** 1 is most urgent, 9 least; ties are broken by {@code nextAttemptAt}. */
    @Column(name = "priority", nullable = false)
    private short priority = 5;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    /**
     * When this row becomes claimable. Carries both the original schedule and any retry backoff,
     * so the claim query is a single comparison regardless of why the row was deferred.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "dedupe_hash", length = 64)
    private String dedupeHash;

    @Column(name = "provider_code", length = 64)
    private String providerCode;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "text")
    private String lastErrorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** When the row reached a terminal state, whatever that state was. */
    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
        // for JPA
    }

    public Notification(UUID requestId, Channel channel, String recipientAddress) {
        this.requestId = requestId;
        this.channel = channel;
        this.recipientAddress = recipientAddress;
    }

    // ---------------------------------------------------------------- state

    /**
     * Applies a state transition, rejecting any the state machine does not allow.
     *
     * @throws IllegalStateException if the transition is illegal — this indicates a bug in the
     *                               calling service, not bad user input, so it is not a
     *                               validation error
     */
    public void transitionTo(NotificationStatus target, Instant at) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal notification transition " + status + " -> " + target + " for " + getId());
        }
        at = toStorablePrecision(at);
        this.status = target;
        switch (target) {
            case SENT -> this.sentAt = at;
            case DELIVERED -> {
                this.deliveredAt = at;
                this.terminalAt = at;
            }
            case FAILED, CANCELLED, SUPPRESSED -> this.terminalAt = at;
            default -> {
                // Non-terminal states carry no completion stamp of their own.
            }
        }
    }

    /** Whether this row is eligible to be claimed at the given moment. */
    public boolean isClaimableAt(Instant now) {
        return status.isClaimable() && !nextAttemptAt.isAfter(now);
    }

    public boolean hasRetriesRemaining() {
        return attemptCount < maxAttempts;
    }

    // ---------------------------------------------------------------- lease

    /** Stamps a dispatch lease. The three lease columns are always written together. */
    public void acquireLease(UUID token, String owner, Instant expiresAt) {
        this.leaseToken = token;
        this.leaseOwner = owner;
        this.leaseExpiresAt = expiresAt;
    }

    /** Clears the lease once the attempt finishes, successfully or not. */
    public void releaseLease() {
        this.leaseToken = null;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    public boolean isLeaseExpired(Instant now) {
        return leaseExpiresAt != null && leaseExpiresAt.isBefore(now);
    }

    // ---------------------------------------------------------------- attempts

    public int beginAttempt() {
        return ++attemptCount;
    }

    public void recordFailure(String errorCode, String errorMessage) {
        this.lastErrorCode = errorCode;
        this.lastErrorMessage = errorMessage;
    }

    public void scheduleRetryAt(Instant when) {
        this.nextAttemptAt = when;
    }

    public void markRead(Instant at) {
        if (readAt == null) {
            this.readAt = toStorablePrecision(at);
        }
    }

    /**
     * Truncates to microseconds, the precision PostgreSQL's {@code timestamptz} actually keeps.
     *
     * <p>Without this, the value returned immediately after a write carries nanoseconds while
     * every later read of the same row carries microseconds — the same instant, reported two
     * different ways. A client that stores the first response and compares it against a later
     * one sees a mismatch that looks like the value changed when nothing did.
     */
    private static Instant toStorablePrecision(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    }

    // ---------------------------------------------------------------- accessors

    public UUID getRequestId() {
        return requestId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getRecipientAddress() {
        return recipientAddress;
    }

    public String getRecipientRef() {
        return recipientRef;
    }

    public void setRecipientRef(String recipientRef) {
        this.recipientRef = recipientRef;
    }

    public String getRenderedSubject() {
        return renderedSubject;
    }

    public void setRenderedSubject(String renderedSubject) {
        this.renderedSubject = renderedSubject;
    }

    public String getRenderedBodyText() {
        return renderedBodyText;
    }

    public void setRenderedBodyText(String renderedBodyText) {
        this.renderedBodyText = renderedBodyText;
    }

    public String getRenderedBodyHtml() {
        return renderedBodyHtml;
    }

    public void setRenderedBodyHtml(String renderedBodyHtml) {
        this.renderedBodyHtml = renderedBodyHtml;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public short getPriority() {
        return priority;
    }

    public void setPriority(short priority) {
        this.priority = priority;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getDedupeHash() {
        return dedupeHash;
    }

    public void setDedupeHash(String dedupeHash) {
        this.dedupeHash = dedupeHash;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public void setProviderMessageId(String providerMessageId) {
        this.providerMessageId = providerMessageId;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getTerminalAt() {
        return terminalAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
