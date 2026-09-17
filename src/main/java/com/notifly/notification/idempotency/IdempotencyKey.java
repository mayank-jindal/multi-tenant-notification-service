package com.notifly.notification.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A record that a client-supplied idempotency key has already been used.
 *
 * <p>Makes submission idempotent: replaying a request with the same key returns the stored
 * response instead of creating a second batch of work. The stored fingerprint guards against the
 * subtler bug — reusing a key with a <em>different</em> body, which is answered with a conflict
 * rather than someone else's result.
 *
 * <p>This is one of the two independent duplicate-prevention layers; the other is the dispatch
 * lease, which covers worker crashes rather than client retries.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /** SHA-256 of the canonicalised request body. */
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "request_path", nullable = false, updatable = false)
    private String requestPath;

    @Column(name = "notification_request_id")
    private UUID notificationRequestId;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private Map<String, Object> responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKey() {
        // for JPA
    }

    public IdempotencyKey(UUID tenantId, String idempotencyKey, String requestFingerprint,
                          String requestPath, Instant expiresAt) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.requestPath = requestPath;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean matchesFingerprint(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt.isBefore(now);
    }

    public void recordResponse(UUID notificationRequestId, int status, Map<String, Object> body) {
        this.notificationRequestId = notificationRequestId;
        this.responseStatus = status;
        this.responseBody = body;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public UUID getNotificationRequestId() {
        return notificationRequestId;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public Map<String, Object> getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
