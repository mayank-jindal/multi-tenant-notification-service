package com.notifly.notification.security;

import com.notifly.notification.common.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates a request from its bearer token.
 *
 * <p>A request without a token passes through unauthenticated rather than being rejected here —
 * whether that is acceptable is the authorization rules' decision, not this filter's. Public
 * endpoints exist, and a filter that rejected anonymous requests would make them unreachable.
 *
 * <p>A token that is present but bad <em>is</em> rejected here, because the caller made a claim
 * and the claim is false; letting it continue as anonymous would turn a 401 into a confusing 403.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AuthenticationErrorWriter errorWriter;

    public JwtAuthenticationFilter(JwtService jwtService, AuthenticationErrorWriter errorWriter) {
        this.jwtService = jwtService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            UserPrincipal principal = jwtService.parse(token);
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ApiException ex) {
            // Raised before the controller advice can see it, so the problem document is written
            // here rather than letting the container produce an HTML error page.
            SecurityContextHolder.clearContext();
            errorWriter.write(response, request, ex);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
