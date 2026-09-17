package com.notifly.notification.tenant;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.common.dto.PageResponse;
import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.tenant.dto.CreateTenantRequest;
import com.notifly.notification.tenant.dto.TenantResponse;
import com.notifly.notification.tenant.dto.UpdateTenantRequest;
import com.notifly.notification.user.User;
import com.notifly.notification.user.UserRepository;
import com.notifly.notification.user.dto.CreateUserRequest;
import com.notifly.notification.user.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant lifecycle and tenant-administrator provisioning.
 *
 * <p>Every method here is platform-admin only. The authorization sits on the service rather than
 * the controller so that it holds however the method is reached — including from a scheduled job
 * or another service that might be added later.
 */
@Service
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public TenantService(TenantRepository tenantRepository,
                         UserRepository userRepository,
                         PasswordEncoder passwordEncoder,
                         AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- lifecycle

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        String slug = request.slug().trim().toLowerCase();
        if (tenantRepository.existsBySlug(slug)) {
            throw Errors.alreadyExists("Tenant", "slug", slug);
        }

        Tenant tenant = new Tenant(slug, request.name().trim());
        if (request.dispatchWeight() != null) {
            tenant.setDispatchWeight(request.dispatchWeight());
        }
        Tenant saved = tenantRepository.save(tenant);

        Map<String, Object> details = new HashMap<>();
        details.put("slug", saved.getSlug());
        details.put("name", saved.getName());
        details.put("dispatchWeight", saved.getDispatchWeight());

        // Provisioning the first administrator in the same transaction matters: a tenant created
        // without one is unusable, and a half-created tenant left behind by a failure here would
        // hold its slug hostage.
        if (request.includesAdmin()) {
            UserResponse admin = provisionAdmin(saved.getId(), new CreateUserRequest(
                    request.adminEmail(),
                    request.adminPassword(),
                    request.adminName() == null ? request.name() + " Admin" : request.adminName()));
            details.put("adminUserId", admin.id().toString());
        }

        auditService.record(AuditEventType.TENANT_CREATED, "Tenant", saved.getId(), details);
        log.info("Created tenant {} ({})", saved.getSlug(), saved.getId());
        return TenantResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<TenantResponse> list(TenantStatus status, Pageable pageable) {
        Page<Tenant> page = status == null
                ? tenantRepository.findAll(pageable)
                : tenantRepository.findAllByStatus(status, pageable);
        return PageResponse.of(page, TenantResponse::from);
    }

    @Transactional(readOnly = true)
    public TenantResponse get(UUID tenantId) {
        return TenantResponse.from(require(tenantId));
    }

    @Transactional
    public TenantResponse update(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = require(tenantId);
        Map<String, Object> changes = new HashMap<>();

        if (request.name() != null && !request.name().equals(tenant.getName())) {
            changes.put("name", Map.of("from", tenant.getName(), "to", request.name()));
            tenant.setName(request.name().trim());
        }
        if (request.dispatchWeight() != null && request.dispatchWeight() != tenant.getDispatchWeight()) {
            changes.put("dispatchWeight",
                    Map.of("from", tenant.getDispatchWeight(), "to", request.dispatchWeight()));
            tenant.setDispatchWeight(request.dispatchWeight());
        }

        if (changes.isEmpty()) {
            // Nothing changed, so nothing is audited. Recording a no-op edit would pad the trail
            // with entries that tell an investigator nothing.
            return TenantResponse.from(tenant);
        }

        Tenant saved = tenantRepository.save(tenant);
        auditService.record(AuditEventType.TENANT_UPDATED, "Tenant", tenantId, changes);
        return TenantResponse.from(saved);
    }

    /**
     * Suspends a tenant: no new submissions, and the dispatcher stops claiming their queued work.
     *
     * <p>Queued notifications are deliberately left in place rather than cancelled. Suspension is
     * usually temporary — a billing problem, an investigation — and destroying a backlog that
     * cannot be reconstructed would make a reversible action irreversible.
     */
    @Transactional
    public TenantResponse suspend(UUID tenantId, String reason) {
        Tenant tenant = require(tenantId);
        if (tenant.getStatus() == TenantStatus.SUSPENDED) {
            return TenantResponse.from(tenant);
        }
        tenant.setStatus(TenantStatus.SUSPENDED);
        Tenant saved = tenantRepository.save(tenant);

        auditService.record(AuditEventType.TENANT_SUSPENDED, "Tenant", tenantId,
                Map.of("reason", reason == null ? "unspecified" : reason));
        log.info("Suspended tenant {} ({})", saved.getSlug(), tenantId);
        return TenantResponse.from(saved);
    }

    @Transactional
    public TenantResponse activate(UUID tenantId) {
        Tenant tenant = require(tenantId);
        if (tenant.getStatus() == TenantStatus.ACTIVE) {
            return TenantResponse.from(tenant);
        }
        tenant.setStatus(TenantStatus.ACTIVE);
        Tenant saved = tenantRepository.save(tenant);

        auditService.record(AuditEventType.TENANT_ACTIVATED, "Tenant", tenantId, Map.of());
        log.info("Reactivated tenant {} ({}); held work resumes dispatch", saved.getSlug(), tenantId);
        return TenantResponse.from(saved);
    }

    // ---------------------------------------------------------------- administrators

    @Transactional
    public UserResponse provisionAdmin(UUID tenantId, CreateUserRequest request) {
        require(tenantId);
        String email = request.email().trim();

        // Email is unique platform-wide, not per tenant, because it is the login identifier and
        // authentication happens before any tenant is known.
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw Errors.alreadyExists("User", "email", email);
        }

        User user = User.tenantAdmin(
                tenantId, email, passwordEncoder.encode(request.password()), request.displayName().trim());
        User saved = userRepository.save(user);

        auditService.record(AuditEventType.USER_CREATED, "User", saved.getId(),
                Map.of("email", email, "role", saved.getRole().name(), "tenantId", tenantId.toString()));
        log.info("Provisioned tenant admin {} for tenant {}", email, tenantId);
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listAdmins(UUID tenantId) {
        require(tenantId);
        return userRepository.findAllByTenantId(tenantId).stream().map(UserResponse::from).toList();
    }

    /**
     * Enables or disables a tenant administrator.
     *
     * <p>Accounts are disabled rather than deleted: the audit trail references the user id, and a
     * deleted account would leave history attributed to an id nobody can resolve.
     */
    @Transactional
    public UserResponse setUserEnabled(UUID tenantId, UUID userId, boolean enabled) {
        require(tenantId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> Errors.notFound("User", userId));

        if (!tenantId.equals(user.getTenantId())) {
            // Refusing rather than 404 would confirm the user exists elsewhere; refusing as
            // not-found keeps one tenant's user list unguessable from another's URL space.
            throw Errors.notFound("User", userId);
        }

        user.setStatus(enabled ? com.notifly.notification.user.UserStatus.ACTIVE
                : com.notifly.notification.user.UserStatus.DISABLED);
        User saved = userRepository.save(user);

        auditService.record(enabled ? AuditEventType.USER_UPDATED : AuditEventType.USER_DISABLED,
                "User", userId, Map.of("enabled", enabled, "email", user.getEmail()));
        return UserResponse.from(saved);
    }

    // ---------------------------------------------------------------- helpers

    /** Loads a tenant or raises the canonical not-found error. */
    public Tenant require(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> Errors.notFound("Tenant", tenantId));
    }

    /** Loads an active tenant, rejecting a suspended one. Used by the send path. */
    @PreAuthorize("permitAll()")
    @Transactional(readOnly = true)
    public Tenant requireActive(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> Errors.badRequest(ErrorCode.TENANT_REQUIRED, "Unknown tenant"));
        if (!tenant.isActive()) {
            throw Errors.tenantSuspended(tenantId);
        }
        return tenant;
    }
}
