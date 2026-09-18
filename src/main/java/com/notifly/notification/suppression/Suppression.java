package com.notifly.notification.suppression;

import com.notifly.notification.common.model.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/**
 * An address that must not be delivered to.
 *
 * <p>Checked at submission rather than at dispatch, so a suppressed recipient never consumes a
 * worker slot or a rate limit token. The resulting notification is recorded as
 * {@code SUPPRESSED} rather than dropped, because "we deliberately did not send this" is a
 * reporting answer a tenant needs.
 */
@Entity
@Table(name = "suppressions")
public class Suppression {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private Channel channel;

    @Column(name = "address", nullable = false, length = 512, updatable = false)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private SuppressionReason reason;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Null means the suppression never lapses. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected Suppression() {
        // for JPA
    }

    public Suppression(UUID tenantId, Channel channel, String address, SuppressionReason reason) {
        this.tenantId = tenantId;
        this.channel = channel;
        this.address = address;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public boolean isActiveAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getAddress() {
        return address;
    }

    public SuppressionReason getReason() {
        return reason;
    }

    public void setReason(SuppressionReason reason) {
        this.reason = reason;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
