package com.notifly.notification.security;

import com.notifly.notification.common.tenancy.TenantScoped;
import com.notifly.notification.user.User;
import com.notifly.notification.user.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated principal.
 *
 * <p>Implements {@link TenantScoped}, which is what allows {@code TenantContextFilter} to derive
 * the tenant scope without knowing anything about how authentication works. This is the join
 * between the security layer and the isolation layer, and it is the only one.
 *
 * <p>Carries no password. The hash is needed once, during authentication, and holding it on the
 * principal would leave it reachable from every request thread for the life of the request.
 */
public record UserPrincipal(
        UUID userId,
        String email,
        UUID tenantId,
        UserRole role,
        boolean enabled) implements UserDetails, TenantScoped {

    public static UserPrincipal from(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getTenantId(),
                user.getRole(),
                user.isActive());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    /**
     * Always null. Authentication happens before the principal is built, so there is never a
     * reason for a credential to travel with it.
     */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonExpired() {
        return enabled;
    }

    @Override
    public boolean isAccountNonLocked() {
        return enabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return enabled;
    }

    // ---------------------------------------------------------------- TenantScoped

    @Override
    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public boolean isPlatformAdmin() {
        return role == UserRole.PLATFORM_ADMIN;
    }

    /** The tenant this principal acts within, failing loudly for a platform admin. */
    public UUID requireTenantId() {
        if (tenantId == null) {
            throw new IllegalStateException("Platform admin has no tenant of its own");
        }
        return tenantId;
    }
}
