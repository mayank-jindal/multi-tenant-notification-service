package com.notifly.notification.common.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes the tenant scope for the duration of a request, from the authenticated principal.
 *
 * <p>Runs after authentication, because the scope is derived from the principal and never from
 * anything the client sends. A request may carry any {@code X-Tenant-ID} header it likes; it is
 * not read here, and that is the point — a header-driven tenancy would let any tenant admin read
 * another tenant's data by editing one value.
 *
 * <p>A platform administrator is given root scope, which disables discriminator filtering. That
 * is the only path by which unrestricted access is ever granted.
 *
 * <p>Registered inside the security filter chain by {@code SecurityConfig} rather than as a
 * {@code @Component}. Auto-registering it as a servlet filter would leave its position relative
 * to authentication up to bean ordering, and it must run after authentication or it would find no
 * principal to derive a scope from.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            TenantContext.set(resolveScope());
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat pools its request threads. Leaving a scope behind would hand this tenant's
            // context to whichever request the thread serves next.
            TenantContext.clear();
        }
    }

    private TenantScope resolveScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return TenantScope.UNSCOPED;
        }
        if (authentication.getPrincipal() instanceof TenantScoped principal) {
            if (principal.isPlatformAdmin()) {
                return TenantScope.root();
            }
            if (principal.getTenantId() == null) {
                log.warn("Tenant principal {} has no tenant; denying all tenant data",
                        authentication.getName());
                return TenantScope.UNSCOPED;
            }
            return TenantScope.of(principal.getTenantId());
        }
        // An authenticated principal we do not recognise gets no tenant data. Failing closed
        // here means an authentication mechanism added later cannot silently inherit root.
        return TenantScope.UNSCOPED;
    }
}
