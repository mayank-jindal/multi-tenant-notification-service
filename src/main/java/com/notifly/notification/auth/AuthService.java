package com.notifly.notification.auth;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.auth.dto.CurrentUserResponse;
import com.notifly.notification.auth.dto.LoginRequest;
import com.notifly.notification.auth.dto.LoginResponse;
import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.security.JwtService;
import com.notifly.notification.security.UserPrincipal;
import com.notifly.notification.tenant.Tenant;
import com.notifly.notification.tenant.TenantRepository;
import com.notifly.notification.user.User;
import com.notifly.notification.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates users and issues access tokens.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository,
                       TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    /**
     * Verifies credentials and issues a token.
     *
     * <p>Every failure path returns the same error regardless of cause. Telling a caller that an
     * address exists but the password is wrong turns this endpoint into an account enumeration
     * oracle, and the distinction is of no use to a legitimate user.
     *
     * <p>The password hash is compared even when no user was found, so that a request for an
     * unknown address takes the same time as one for a known address with a wrong password.
     * Without it, response timing alone reveals which addresses are registered.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        Optional<User> candidate = userRepository.findByEmailIgnoreCase(request.email().trim());

        if (candidate.isEmpty()) {
            burnTimeToMatchRealComparison(request.password());
            auditFailure(request.email(), "NO_SUCH_USER");
            throw Errors.unauthenticated(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        User user = candidate.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditFailure(request.email(), "BAD_PASSWORD");
            throw Errors.unauthenticated(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        if (!user.isActive()) {
            auditFailure(request.email(), "ACCOUNT_DISABLED");
            throw Errors.unauthenticated(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
        }

        // A suspended tenant's admins cannot obtain a token at all: letting them in to receive
        // 403s from every subsequent call would be a worse experience and a worse audit trail.
        if (user.getTenantId() != null) {
            Tenant tenant = tenantRepository.findById(user.getTenantId())
                    .orElseThrow(() -> Errors.unauthenticated(
                            ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));
            if (!tenant.isActive()) {
                auditFailure(request.email(), "TENANT_SUSPENDED");
                throw Errors.tenantSuspended(tenant.getId());
            }
        }

        user.recordLogin(Instant.now());
        userRepository.save(user);

        UserPrincipal principal = UserPrincipal.from(user);
        JwtService.IssuedToken issued = jwtService.issue(principal);

        auditService.recordUserAction(
                AuditEventType.USER_LOGIN, "User", user.getId(), user.getTenantId(),
                user.getId(), user.getEmail(), user.getRole().name(),
                Map.of("outcome", "SUCCESS"));

        log.debug("Issued access token for {}", user.getEmail());

        return new LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresIn(),
                issued.expiresAt(),
                new LoginResponse.CurrentUser(
                        user.getId(),
                        user.getEmail(),
                        user.getDisplayName(),
                        user.getRole().name(),
                        user.getTenantId()));
    }

    /** Describes the caller behind the supplied token. */
    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(UserPrincipal principal) {
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> Errors.notFound("User", principal.userId()));

        String tenantSlug = user.getTenantId() == null ? null
                : tenantRepository.findById(user.getTenantId()).map(Tenant::getSlug).orElse(null);

        return new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name(),
                user.getTenantId(),
                tenantSlug,
                user.getLastLoginAt());
    }

    /**
     * Performs a hash comparison against a dummy value so that an unknown email costs the same
     * as a known one. The result is deliberately discarded.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void burnTimeToMatchRealComparison(String suppliedPassword) {
        passwordEncoder.matches(suppliedPassword,
                "$2a$12$usesomesillystringfore2uDLvp1Q4Ss3aGWsE0YJ.gG2Y0KIrVi");
    }

    private void auditFailure(String email, String reason) {
        log.debug("Failed login for {}: {}", email, reason);
        auditService.recordSystemAction(
                AuditEventType.USER_LOGIN_FAILED, "User", null, null,
                Map.of("email", email, "reason", reason));
    }
}
