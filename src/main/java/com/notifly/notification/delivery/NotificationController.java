package com.notifly.notification.delivery;

import com.notifly.notification.common.dto.PageResponse;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.delivery.dto.NotificationResponse;
import com.notifly.notification.delivery.dto.SendNotificationRequest;
import com.notifly.notification.delivery.dto.SendResponse;
import com.notifly.notification.idempotency.IdempotencyService;
import com.notifly.notification.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Submitting notifications and reading their delivery state.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Sending notifications and tracking delivery. Requires TENANT_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a tenant administrator", content = @Content)
})
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final SendService sendService;
    private final NotificationQueryService queryService;
    private final IdempotencyService idempotencyService;

    public NotificationController(SendService sendService,
                                  NotificationQueryService queryService,
                                  IdempotencyService idempotencyService) {
        this.sendService = sendService;
        this.queryService = queryService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    @Operation(
            summary = "Send a templated notification",
            description = """
                    Covers all three cases: a single send is one recipient, a bulk send is many,
                    and a scheduled send is either with `scheduledAt` set to a future instant.

                    Everything that can reject the request — tenant status, template resolution,
                    the variable contract, channel configuration and rate limits — is checked
                    before any notification is written. A rejected request queues nothing.

                    Supply an `Idempotency-Key` header to make retries safe: a repeated key with
                    the same body returns the original result instead of sending again. The same
                    key with a different body is a conflict.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted and queued"),
            @ApiResponse(responseCode = "400", description = "Validation, template, variable or channel problem", content = @Content),
            @ApiResponse(responseCode = "403", description = "Tenant suspended", content = @Content),
            @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different body", content = @Content),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded; see the Retry-After header", content = @Content)
    })
    public ResponseEntity<SendResponse> send(
            @Valid @RequestBody SendNotificationRequest request,
            @Parameter(description = "Makes this submission idempotent for 24 hours")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal principal) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.accepted().body(sendService.send(request, principal));
        }

        UUID tenantId = TenantContext.requireTenantId();
        // The key is claimed before the send runs. Claiming afterwards would leave a window in
        // which two concurrent identical requests both send before either records the key.
        Optional<UUID> replayOf = idempotencyService.claim(
                tenantId, idempotencyKey, canonical(request), "/api/v1/notifications");

        if (replayOf.isPresent()) {
            return ResponseEntity.accepted().body(queryService.describeRequest(replayOf.get(), true));
        }

        try {
            SendResponse response = sendService.send(request, principal);
            idempotencyService.recordOutcome(tenantId, idempotencyKey, response.requestId());
            return ResponseEntity.accepted().body(response);

        } catch (RuntimeException ex) {
            // Release the claim so the caller can fix the request and retry with the same key.
            // Leaving it held would strand the key against a submission that never happened.
            idempotencyService.release(tenantId, idempotencyKey);
            throw ex;
        }
    }

    @GetMapping("/{notificationId}")
    @Operation(summary = "Fetch one notification's delivery state")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "No such notification in this tenant", content = @Content))
    public ResponseEntity<NotificationResponse> get(@PathVariable UUID notificationId) {
        return ResponseEntity.ok(queryService.get(notificationId));
    }

    @GetMapping
    @Operation(summary = "Search notifications",
            description = "Every filter is optional and they compose. Results are newest first.")
    public ResponseEntity<PageResponse<NotificationResponse>> search(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) UUID requestId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(queryService.search(status, channel, requestId, from, to, pageable));
    }

    @GetMapping("/{notificationId}/attempts")
    @Operation(summary = "The attempt history of one notification",
            description = "Every provider interaction, in order, with its outcome and latency — how the notification reached its current state.")
    public ResponseEntity<List<NotificationQueryService.DeliveryAttemptResponse>> attempts(
            @PathVariable UUID notificationId) {
        return ResponseEntity.ok(queryService.attempts(notificationId));
    }

    @GetMapping("/summary")
    @Operation(summary = "Delivery counts by status and channel")
    public ResponseEntity<NotificationQueryService.DeliverySummary> summary() {
        return ResponseEntity.ok(queryService.summary());
    }

    @DeleteMapping("/{notificationId}")
    @Operation(summary = "Cancel a notification",
            description = "Only before a provider has accepted it. Once sent, it cannot be recalled.")
    @ApiResponses(@ApiResponse(responseCode = "409", description = "Already dispatched or terminal", content = @Content))
    public ResponseEntity<NotificationResponse> cancel(@PathVariable UUID notificationId) {
        return ResponseEntity.ok(queryService.cancel(notificationId));
    }

    @DeleteMapping("/requests/{requestId}")
    @Operation(summary = "Cancel every still-cancellable notification in a submission",
            description = "Notifications already handed to a provider are left alone.")
    public ResponseEntity<SendResponse> cancelRequest(@PathVariable UUID requestId) {
        return ResponseEntity.ok(queryService.cancelRequest(requestId));
    }

    @GetMapping("/requests/{requestId}")
    @Operation(summary = "Describe a submission and the notifications it produced")
    public ResponseEntity<SendResponse> getRequest(@PathVariable UUID requestId) {
        return ResponseEntity.ok(queryService.describeRequest(requestId, false));
    }

    /**
     * A stable string form of the request, for idempotency fingerprinting.
     *
     * <p>Derived from the parsed object rather than the raw bytes so that whitespace or key order
     * does not make two identical requests look different. Records give a deterministic
     * {@code toString}, which is enough here — the fingerprint only has to detect a genuinely
     * different body, not be a canonical JSON serialisation.
     */
    private String canonical(SendNotificationRequest request) {
        return request.toString();
    }
}
