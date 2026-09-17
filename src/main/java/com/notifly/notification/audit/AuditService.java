package com.notifly.notification.audit;

import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Writes the append-only audit trail.
 *
 * <p>Covers both halves of the requirement: notification state transitions and administrative
 * configuration changes.
 *
 * <p>Audit writes use {@link Propagation#REQUIRES_NEW}. The reason is specific: an audit entry
 * recording a <em>rejected</em> action — a failed login, a refused send — must survive the
 * rollback of the transaction that rejected it. Joining the caller's transaction would roll the
 * evidence back along with the action, so the trail would record only successes and quietly omit
 * everything anyone would want to investigate.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * Records an action taken by the currently authenticated user, resolving the actor from the
     * security context.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String entityType, UUID entityId, Map<String, Object> details) {
        AuditEvent event = new AuditEvent(eventType, entityType, entityId);
        event.setDetails(details);
        applyCurrentActor(event);
        save(event);
    }

    /** Records an action with an explicitly supplied actor, for paths with no security context. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUserAction(String eventType, String entityType, UUID entityId, UUID tenantId,
                                 UUID actorId, String actorEmail, String actorRole,
                                 Map<String, Object> details) {
        AuditEvent event = new AuditEvent(eventType, entityType, entityId);
        event.setTenantId(tenantId);
        event.setActorKind(ActorKind.USER);
        event.setActorUserId(actorId);
        event.setActorEmail(actorEmail);
        event.setActorRole(actorRole);
        event.setDetails(details);
        save(event);
    }

    /**
     * Records an action taken by the platform itself — the dispatcher, a scheduler, the lease
     * reaper. These have no principal, and attributing them to one would make the trail lie.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystemAction(String eventType, String entityType, UUID entityId, UUID tenantId,
                                   Map<String, Object> details) {
        AuditEvent event = new AuditEvent(eventType, entityType, entityId);
        event.setTenantId(tenantId);
        event.setActorKind(ActorKind.SYSTEM);
        event.setDetails(details);
        save(event);
    }

    /** Records a notification state transition, the highest-volume event in the trail. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTransition(UUID tenantId, UUID notificationId, String fromState, String toState,
                                 Map<String, Object> details) {
        AuditEvent event = new AuditEvent(
                AuditEventType.NOTIFICATION_TRANSITION, "Notification", notificationId);
        event.setTenantId(tenantId);
        event.setFromState(fromState);
        event.setToState(toState);
        event.setActorKind(ActorKind.SYSTEM);
        event.setDetails(details);
        save(event);
    }

    private void applyCurrentActor(AuditEvent event) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            event.setActorKind(ActorKind.USER);
            event.setActorUserId(principal.userId());
            event.setActorEmail(principal.email());
            event.setActorRole(principal.role().name());
            event.setTenantId(principal.tenantId());
        } else {
            event.setActorKind(ActorKind.SYSTEM);
            var scope = TenantContext.current();
            event.setTenantId(scope.isRoot() || scope.isUnscoped() ? null : scope.tenantId());
        }
    }

    /**
     * Persists the event, swallowing any failure.
     *
     * <p>A broken audit write must not turn a successful business operation into a failed one.
     * The failure is logged at ERROR so it is visible and alertable, but the caller's work
     * stands. The alternative — failing the request because its audit row could not be written —
     * trades a working system for a perfectly recorded broken one.
     */
    private void save(AuditEvent event) {
        try {
            auditEventRepository.save(event);
        } catch (RuntimeException ex) {
            log.error("Failed to write audit event {} for {} {}",
                    event.getEventType(), event.getEntityType(), event.getEntityId(), ex);
        }
    }
}
