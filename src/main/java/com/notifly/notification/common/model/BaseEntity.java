package com.notifly.notification.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity, timestamps and optimistic locking for mutable entities.
 *
 * <p>The version column matters more than it looks: the dispatcher and the tenant-facing API can
 * touch the same notification concurrently, and optimistic locking is what turns that into a
 * detectable conflict rather than a lost update.
 *
 * <p>Append-only tables ({@code delivery_attempts}, {@code audit_events}) deliberately do not
 * extend this class — they have no update path, so an update timestamp and a version column
 * would be dead columns implying a mutability that does not exist.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Equality is by persistent identity only. Entities are compared across sessions and
     * detached states, where field-by-field comparison would be both expensive and wrong.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass().getSimpleName());
    }
}
