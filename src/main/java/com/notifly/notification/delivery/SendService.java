package com.notifly.notification.delivery;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.channel.ChannelConfigService;
import com.notifly.notification.common.config.DispatchProperties;
import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.delivery.dto.NotificationResponse;
import com.notifly.notification.delivery.dto.RecipientDto;
import com.notifly.notification.delivery.dto.SendNotificationRequest;
import com.notifly.notification.delivery.dto.SendResponse;
import com.notifly.notification.ratelimit.RateLimiter;
import com.notifly.notification.security.UserPrincipal;
import com.notifly.notification.suppression.SuppressionRepository;
import com.notifly.notification.template.TemplateChannelBody;
import com.notifly.notification.template.TemplateChannelBodyRepository;
import com.notifly.notification.template.TemplateRenderer;
import com.notifly.notification.template.TemplateVersion;
import com.notifly.notification.template.TemplateService;
import com.notifly.notification.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a submission into queued work.
 *
 * <p>The ordering here is deliberate and is most of the design. Everything that can reject the
 * request — tenant status, template resolution, variable contract, channel configuration, rate
 * limits — happens <em>before</em> a single notification row is written. A caller either gets a
 * clean error with nothing queued, or an accepted submission where every notification is already
 * rendered and ready to dispatch. There is no state in which half a batch exists.
 *
 * <p>Content is rendered here rather than at dispatch, which costs storage and buys two things: a
 * later template edit cannot change what a queued message says, and a rendering failure surfaces
 * as a 400 to the caller instead of as a mysterious delivery failure hours later.
 */
@Service
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class SendService {

    private static final Logger log = LoggerFactory.getLogger(SendService.class);

    private final NotificationRequestRepository requestRepository;
    private final NotificationRepository notificationRepository;
    private final TemplateService templateService;
    private final TemplateChannelBodyRepository bodyRepository;
    private final TemplateRenderer renderer;
    private final ChannelConfigService channelConfigService;
    private final SuppressionRepository suppressionRepository;
    private final RateLimiter rateLimiter;
    private final TenantService tenantService;
    private final DispatchProperties dispatchProperties;
    private final AuditService auditService;

    public SendService(NotificationRequestRepository requestRepository,
                       NotificationRepository notificationRepository,
                       TemplateService templateService,
                       TemplateChannelBodyRepository bodyRepository,
                       TemplateRenderer renderer,
                       ChannelConfigService channelConfigService,
                       SuppressionRepository suppressionRepository,
                       RateLimiter rateLimiter,
                       TenantService tenantService,
                       DispatchProperties dispatchProperties,
                       AuditService auditService) {
        this.requestRepository = requestRepository;
        this.notificationRepository = notificationRepository;
        this.templateService = templateService;
        this.bodyRepository = bodyRepository;
        this.renderer = renderer;
        this.channelConfigService = channelConfigService;
        this.suppressionRepository = suppressionRepository;
        this.rateLimiter = rateLimiter;
        this.tenantService = tenantService;
        this.dispatchProperties = dispatchProperties;
        this.auditService = auditService;
    }

    @Transactional
    public SendResponse send(SendNotificationRequest request, UserPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        tenantService.requireActive(tenantId);

        // ---- resolve the template and freeze the version this submission will use
        TemplateVersion version = templateService.requirePublishedVersion(tenantId, request.templateCode());
        Map<Channel, TemplateChannelBody> bodiesByChannel = new EnumMap<>(Channel.class);
        for (TemplateChannelBody body : bodyRepository.findAllByTemplateVersionId(version.getId())) {
            bodiesByChannel.put(body.getChannel(), body);
        }
        if (bodiesByChannel.isEmpty()) {
            throw Errors.badRequest(ErrorCode.TEMPLATE_NOT_PUBLISHED,
                    "Published version of " + request.templateCode() + " has no channel bodies");
        }

        // ---- work out which (recipient, channel) pairs are actually deliverable
        Set<Channel> requested = request.channels() == null || request.channels().isEmpty()
                ? bodiesByChannel.keySet()
                : request.channels();

        Set<Channel> usableChannels = new HashSet<>();
        for (Channel channel : requested) {
            if (!bodiesByChannel.containsKey(channel)) {
                continue;
            }
            // Resolved once per channel rather than once per recipient: a bulk send would
            // otherwise ask the same question ten thousand times.
            channelConfigService.requireUsable(tenantId, channel);
            usableChannels.add(channel);
        }

        if (usableChannels.isEmpty()) {
            throw Errors.badRequest(ErrorCode.CHANNEL_NOT_CONFIGURED,
                            "No requested channel is both present on the template and enabled for this tenant")
                    .with("templateChannels", bodiesByChannel.keySet().stream().map(Enum::name).toList())
                    .with("requestedChannels", requested.stream().map(Enum::name).toList());
        }

        // ---- validate the variable contract once per recipient, before anything is written
        List<PlannedNotification> planned = plan(request, usableChannels, version, bodiesByChannel);

        Map<Channel, Integer> suppressedCounts = new EnumMap<>(Channel.class);
        planned = removeSuppressed(tenantId, planned, suppressedCounts);

        int totalSuppressed = suppressedCounts.values().stream().mapToInt(Integer::intValue).sum();
        int skipped = (request.recipients().size() * usableChannels.size()) - planned.size() - totalSuppressed;

        if (planned.isEmpty()) {
            throw Errors.badRequest(ErrorCode.RECIPIENT_SUPPRESSED,
                            "Every recipient on every requested channel is suppressed or unreachable")
                    .with("suppressed", totalSuppressed)
                    .with("skipped", Math.max(skipped, 0));
        }

        // ---- rate limit before writing, so a rejected submission leaves nothing behind
        enforceRateLimits(tenantId, planned);

        // ---- everything has passed; now persist
        boolean scheduled = request.isScheduled();

        NotificationRequest submission = new NotificationRequest(version.getTemplateId(), version.getId());
        submission.setVariables(request.variablesOrEmpty());
        submission.setScheduledAt(scheduled ? request.scheduledAt() : null);
        submission.setStatus(scheduled ? NotificationRequestStatus.SCHEDULED : NotificationRequestStatus.ACCEPTED);
        submission.setSubmittedBy(principal == null ? null : principal.userId());
        submission.setNotificationCount(planned.size());
        NotificationRequest savedRequest = requestRepository.save(submission);

        List<Notification> created = new ArrayList<>(planned.size());
        Instant now = Instant.now();

        for (PlannedNotification plan : planned) {
            Notification notification = new Notification(
                    savedRequest.getId(), plan.channel(), plan.address());
            notification.setRecipientRef(plan.ref());
            notification.setRenderedSubject(plan.content().subject());
            notification.setRenderedBodyText(plan.content().bodyText());
            notification.setRenderedBodyHtml(plan.content().bodyHtml());
            notification.setPriority(request.priorityOrDefault());
            notification.setDedupeHash(plan.dedupeHash());

            // The configured retry budget is applied here. Relying on the entity's field default
            // would silently ignore notifly.dispatch.retry.max-attempts, leaving the setting
            // inert with nothing to indicate it had no effect.
            notification.setMaxAttempts(request.maxAttempts() != null
                    ? request.maxAttempts()
                    : dispatchProperties.retry().maxAttempts());

            if (scheduled) {
                notification.setScheduledAt(request.scheduledAt());
                notification.setNextAttemptAt(request.scheduledAt());
                notification.transitionTo(NotificationStatus.SCHEDULED, now);
            } else {
                notification.setNextAttemptAt(now);
                notification.transitionTo(NotificationStatus.QUEUED, now);
            }

            created.add(notification);
        }

        // Saved in one batch. Hibernate is configured with JDBC batching, so a thousand
        // recipients is a handful of round trips rather than a thousand.
        List<Notification> saved = notificationRepository.saveAll(created);

        auditService.record(AuditEventType.REQUEST_ACCEPTED, "NotificationRequest", savedRequest.getId(),
                Map.of("templateCode", request.templateCode(),
                        "templateVersion", version.getVersionNumber(),
                        "notifications", saved.size(),
                        "suppressed", totalSuppressed,
                        "scheduled", scheduled));

        log.info("Tenant {} accepted {} notifications from template {} ({} suppressed)",
                tenantId, saved.size(), request.templateCode(), totalSuppressed);

        return new SendResponse(
                savedRequest.getId(),
                savedRequest.getStatus().name(),
                savedRequest.getScheduledAt(),
                saved.size(),
                totalSuppressed,
                Math.max(skipped, 0),
                saved.size() <= SendResponse.INLINE_LIMIT
                        ? saved.stream().map(NotificationResponse::from).toList()
                        : null,
                false);
    }

    // ---------------------------------------------------------------- planning

    private List<PlannedNotification> plan(SendNotificationRequest request,
                                           Set<Channel> usableChannels,
                                           TemplateVersion version,
                                           Map<Channel, TemplateChannelBody> bodiesByChannel) {
        List<PlannedNotification> planned = new ArrayList<>();

        for (RecipientDto recipient : request.recipients()) {
            Map<String, Object> merged = new HashMap<>(request.variablesOrEmpty());
            merged.putAll(recipient.variablesOrEmpty());

            // Strict validation per recipient, because per-recipient overrides mean the merged
            // contract can differ between them.
            renderer.validateSuppliedVariables(version.getVariables(), merged);

            for (Channel channel : usableChannels) {
                String address = resolveAddress(recipient, channel);
                if (address == null || address.isBlank()) {
                    // Not an error: a recipient with only an email address on a send that also
                    // targets SMS is a normal, expected shape.
                    continue;
                }

                var content = renderer.render(bodiesByChannel.get(channel), version.getVariables(), merged);
                planned.add(new PlannedNotification(
                        channel, address.trim(), recipient.ref(), content,
                        dedupeHash(channel, address, content.bodyText())));
            }
        }
        return planned;
    }

    private String resolveAddress(RecipientDto recipient, Channel channel) {
        String address = recipient.addresses() == null ? null : recipient.addresses().get(channel);
        if (address != null) {
            return address;
        }
        // In-app messages are addressed by the tenant's own recipient reference, since there is
        // no external address to send to.
        return channel == Channel.IN_APP ? recipient.ref() : null;
    }

    private List<PlannedNotification> removeSuppressed(UUID tenantId,
                                                       List<PlannedNotification> planned,
                                                       Map<Channel, Integer> suppressedCounts) {
        Map<Channel, Set<String>> addressesByChannel = new EnumMap<>(Channel.class);
        for (PlannedNotification plan : planned) {
            addressesByChannel.computeIfAbsent(plan.channel(), c -> new HashSet<>()).add(plan.address());
        }

        Map<Channel, Set<String>> suppressedByChannel = new EnumMap<>(Channel.class);
        Instant now = Instant.now();
        addressesByChannel.forEach((channel, addresses) -> {
            // One query per channel for the whole batch, not one per recipient.
            List<String> suppressed = suppressionRepository
                    .findSuppressedAddresses(tenantId, channel, addresses, now);
            if (!suppressed.isEmpty()) {
                suppressedByChannel.put(channel, new HashSet<>(suppressed));
            }
        });

        if (suppressedByChannel.isEmpty()) {
            return planned;
        }

        List<PlannedNotification> kept = new ArrayList<>(planned.size());
        for (PlannedNotification plan : planned) {
            Set<String> suppressed = suppressedByChannel.get(plan.channel());
            if (suppressed != null && suppressed.contains(plan.address())) {
                suppressedCounts.merge(plan.channel(), 1, Integer::sum);
            } else {
                kept.add(plan);
            }
        }
        return kept;
    }

    private void enforceRateLimits(UUID tenantId, List<PlannedNotification> planned) {
        Map<Channel, Integer> perChannel = new EnumMap<>(Channel.class);
        for (PlannedNotification plan : planned) {
            perChannel.merge(plan.channel(), 1, Integer::sum);
        }

        List<Channel> acquired = new ArrayList<>();
        for (Map.Entry<Channel, Integer> entry : perChannel.entrySet()) {
            RateLimiter.Decision decision =
                    rateLimiter.tryAcquire(tenantId, entry.getKey(), entry.getValue());

            if (!decision.allowed()) {
                // Permits already taken for other channels are handed back. Without this, a
                // rejected multi-channel submission would silently consume another channel's
                // budget for work that was never queued.
                acquired.forEach(channel -> rateLimiter.release(tenantId, channel, perChannel.get(channel)));

                auditService.record(AuditEventType.RATE_LIMIT_EXCEEDED, "Tenant", tenantId,
                        Map.of("channel", entry.getKey().name(),
                                "requested", entry.getValue(),
                                "reason", String.valueOf(decision.reason())));

                if (decision.permanentlyDenied()) {
                    throw Errors.badRequest(ErrorCode.RATE_LIMIT_EXCEEDED, decision.reason())
                            .with("channel", entry.getKey().name());
                }
                throw Errors.rateLimited(decision.retryAfterSeconds(), entry.getKey().name())
                        .with("channel", entry.getKey().name());
            }
            acquired.add(entry.getKey());
        }
    }

    /**
     * A stable fingerprint of what is being sent where, for spotting accidental duplicates.
     *
     * <p>Not a uniqueness constraint: a tenant may legitimately send the same message to the same
     * person twice, and refusing that would be wrong.
     */
    private String dedupeHash(Channel channel, String address, String bodyText) {
        String material = channel.name() + "|" + address + "|" + (bodyText == null ? "" : bodyText);
        return Integer.toHexString(material.hashCode())
               + Integer.toHexString(material.length() * 31 + material.hashCode());
    }

    private record PlannedNotification(
            Channel channel,
            String address,
            String ref,
            TemplateRenderer.RenderedContent content,
            String dedupeHash) {
    }
}
