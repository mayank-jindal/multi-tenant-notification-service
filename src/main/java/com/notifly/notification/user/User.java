package com.notifly.notification.user;

import com.notifly.notification.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An authentication principal.
 *
 * <p>Deliberately not a {@code TenantOwnedEntity}: a platform admin has no tenant, and modelling
 * that as a nullable column here is more honest than inventing a synthetic system tenant purely
 * to satisfy a non-null constraint. The database enforces the pairing — platform admins must
 * have no tenant, tenant admins must have one.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "email", nullable = false)
    private String email;

    /** BCrypt hash. The plaintext password never leaves the request thread. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
        // for JPA
    }

    private User(UUID tenantId, String email, String passwordHash, String displayName, UserRole role) {
        this.tenantId = tenantId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    /** Creates a platform administrator, who by definition belongs to no tenant. */
    public static User platformAdmin(String email, String passwordHash, String displayName) {
        return new User(null, email, passwordHash, displayName, UserRole.PLATFORM_ADMIN);
    }

    /** Creates an administrator scoped to a single tenant. */
    public static User tenantAdmin(UUID tenantId, String email, String passwordHash, String displayName) {
        return new User(tenantId, email, passwordHash, displayName, UserRole.TENANT_ADMIN);
    }

    public boolean isPlatformAdmin() {
        return role == UserRole.PLATFORM_ADMIN;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
