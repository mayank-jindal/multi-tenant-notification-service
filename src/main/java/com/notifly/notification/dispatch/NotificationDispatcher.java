package com.notifly.notification.dispatch;

import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.common.tenancy.TenantScope;
import com.notifly.notification.provider.ProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Delivers one claimed notification, on a worker thread.
 *
 * <p>Three steps, and the gap between them is the design:
 *
 * <ol>
 *   <li>Record the attempt and assemble the message — transactional, committed.</li>
 *   <li>Call the provider — <strong>outside any transaction</strong>.</li>
 *   <li>Apply the outcome — transactional.</li>
 * </ol>
 *
 * <p>Delivery is therefore <strong>at-least-once</strong>. The window between a provider accepting
 * a message and our recording that fact cannot be closed without a distributed transaction across
 * a third party, which does not exist. The lease and the pre-written attempt row make the window
 * small, bounded and visible in the audit trail rather than silent.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final AttemptRecorder attemptRecorder;

    public NotificationDispatcher(AttemptRecorder attemptRecorder) {
        this.attemptRecorder = attemptRecorder;
    }

    /**
     * Entry point for a worker thread.
     *
     * <p>The tenant scope is established <em>around</em> the transactional steps, never inside
     * them. Hibernate binds the tenant identifier when a session opens, so a scope set within an
     * open transaction has no effect on it — the work would read and write under the "no tenant"
     * sentinel and fail against the foreign key. That is the constraint recorded in ADR-013, and
     * this is the place it matters most, because worker threads have no request to inherit a
     * scope from.
     */
    public void dispatch(UUID tenantId, UUID notificationId) {
        TenantContext.runAs(TenantScope.of(tenantId), () -> {
            try {
                deliver(notificationId);
            } catch (RuntimeException ex) {
                // A worker must never die of an unhandled exception: the pool would silently lose
                // a thread and throughput would decay with no obvious cause. The expiring lease
                // is the safety net that returns this row to the queue.
                log.error("Dispatch failed for notification {}; leaving it to lease recovery",
                        notificationId, ex);
            }
        });
    }

    private void deliver(UUID notificationId) {
        AttemptRecorder.PreparedAttempt prepared = attemptRecorder.beginAttempt(notificationId);
        if (prepared == null) {
            return;
        }

        ProviderResult result;
        try {
            result = prepared.provider().send(prepared.message());
        } catch (RuntimeException ex) {
            // An adapter that throws has told us nothing about whether retrying could help.
            // Transient is the safe assumption: it risks a wasted retry, whereas assuming
            // permanent would discard a message that might well have succeeded.
            log.warn("Provider {} threw for notification {}",
                    prepared.provider().providerCode(), notificationId, ex);
            result = ProviderResult.transientFailure("PROVIDER_EXCEPTION",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        attemptRecorder.completeAttempt(notificationId, prepared.attemptId(), result);
    }
}
