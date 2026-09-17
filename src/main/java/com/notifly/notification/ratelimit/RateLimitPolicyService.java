package com.notifly.notification.ratelimit;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.ratelimit.dto.RateLimitPolicyRequest;
import com.notifly.notification.ratelimit.dto.RateLimitPolicyResponse;
import com.notifly.notification.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages token bucket policies and resolves which one applies to a given send.
 *
 * <p>Policies form a two-dimensional fallback: tenant-specific or platform-wide, crossed with
 * channel-specific or all-channels. Resolution takes the most specific enabled match, so a
 * platform admin can set one floor for everybody and still carve out exceptions without
 * duplicating the rest.
 */
@Service
public class RateLimitPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitPolicyService.class);

    private final RateLimitPolicyRepository policyRepository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public RateLimitPolicyService(RateLimitPolicyRepository policyRepository,
                                  TenantRepository tenantRepository,
                                  AuditService auditService) {
        this.policyRepository = policyRepository;
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- resolution

    /**
     * The policy governing a (tenant, channel) pair, or empty if nothing limits it.
     *
     * <p>Called on the send path, so it is a single query returning all four candidates rather
     * than up to four queries walking the fallback chain.
     *
     * <p>Not {@code @PreAuthorize}d: this is an internal decision made on behalf of whoever is
     * sending, including the dispatcher, which has no principal at all.
     */
    @Transactional(readOnly = true)
    public Optional<RateLimitPolicy> resolve(UUID tenantId, Channel channel) {
        return policyRepository.findCandidates(tenantId, channel).stream()
                .max(Comparator.comparingInt(RateLimitPolicy::specificity));
    }

    // ---------------------------------------------------------------- administration

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    public RateLimitPolicyResponse upsertGlobal(RateLimitPolicyRequest request) {
        RateLimitPolicy existing = (request.channel() == null
                ? policyRepository.findGlobalDefault()
                : policyRepository.findGlobalByChannel(request.channel())).orElse(null);

        return RateLimitPolicyResponse.from(upsert(existing, null, request));
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    public RateLimitPolicyResponse upsertForTenant(UUID tenantId, RateLimitPolicyRequest request) {
        if (!tenantRepository.existsById(tenantId)) {
            throw Errors.notFound("Tenant", tenantId);
        }
        RateLimitPolicy existing = policyRepository
                .findByTenantIdAndChannel(tenantId, request.channel())
                .orElse(null);

        return RateLimitPolicyResponse.from(upsert(existing, tenantId, request));
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional(readOnly = true)
    public List<RateLimitPolicyResponse> listGlobal() {
        return policyRepository.findGlobalPolicies().stream()
                .map(RateLimitPolicyResponse::from)
                .toList();
    }

    /**
     * Every policy that could apply to a tenant, including the platform defaults it inherits.
     *
     * <p>Returning only the tenant's own rows would answer a different question from the one a
     * reader is actually asking — "what limits this tenant" — and would make an inherited limit
     * look like no limit at all.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or (hasRole('TENANT_ADMIN') and #tenantId == authentication.principal.tenantId)")
    @Transactional(readOnly = true)
    public List<RateLimitPolicyResponse> listEffective(UUID tenantId) {
        List<RateLimitPolicy> own = policyRepository.findAllByTenantId(tenantId);
        List<RateLimitPolicy> global = policyRepository.findGlobalPolicies();

        return java.util.stream.Stream.concat(own.stream(), global.stream())
                .sorted(Comparator.comparingInt(RateLimitPolicy::specificity).reversed())
                .map(RateLimitPolicyResponse::from)
                .toList();
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    public void delete(UUID policyId) {
        RateLimitPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> Errors.notFound("RateLimitPolicy", policyId));

        policyRepository.delete(policy);
        auditService.record(AuditEventType.RATE_LIMIT_POLICY_DELETED, "RateLimitPolicy", policyId,
                Map.of("tenantId", String.valueOf(policy.getTenantId()),
                        "channel", String.valueOf(policy.getChannel())));
    }

    // ---------------------------------------------------------------- helper

    private RateLimitPolicy upsert(RateLimitPolicy existing, UUID tenantId, RateLimitPolicyRequest request) {
        // A burst capacity below the sustained refill rate is almost always a typo: the bucket
        // could never hold one period's worth of tokens, so the configured rate would be
        // unreachable and the real limit would silently be the capacity instead.
        if (request.capacity() < request.refillTokens()) {
            throw Errors.badRequest("VALIDATION_FAILED",
                    "capacity (%d) must be at least refillTokens (%d), otherwise the configured rate can never be reached"
                            .formatted(request.capacity(), request.refillTokens()));
        }

        Map<String, Object> details = new HashMap<>();
        details.put("tenantId", String.valueOf(tenantId));
        details.put("channel", String.valueOf(request.channel()));
        details.put("capacity", request.capacity());
        details.put("refillTokens", request.refillTokens());
        details.put("refillPeriodSeconds", request.refillPeriodOrDefault());

        RateLimitPolicy policy;
        String eventType;

        if (existing == null) {
            policy = new RateLimitPolicy(tenantId, request.channel(), request.capacity(),
                    request.refillTokens(), request.refillPeriodOrDefault());
            eventType = AuditEventType.RATE_LIMIT_POLICY_CREATED;
        } else {
            details.put("previous", Map.of(
                    "capacity", existing.getCapacity(),
                    "refillTokens", existing.getRefillTokens(),
                    "refillPeriodSeconds", existing.getRefillPeriodSeconds()));
            existing.setCapacity(request.capacity());
            existing.setRefillTokens(request.refillTokens());
            existing.setRefillPeriodSeconds(request.refillPeriodOrDefault());
            policy = existing;
            eventType = AuditEventType.RATE_LIMIT_POLICY_UPDATED;
        }
        policy.setEnabled(request.enabledOrDefault());

        RateLimitPolicy saved = policyRepository.save(policy);
        auditService.record(eventType, "RateLimitPolicy", saved.getId(), details);
        log.info("Rate limit set: {} -> {}/{}s (capacity {})",
                RateLimitPolicyResponse.from(saved).scope(),
                saved.getRefillTokens(), saved.getRefillPeriodSeconds(), saved.getCapacity());
        return saved;
    }
}
