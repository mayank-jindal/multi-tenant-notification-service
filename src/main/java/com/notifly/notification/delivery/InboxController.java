package com.notifly.notification.delivery;

import com.notifly.notification.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reading in-app messages.
 *
 * <p>Recipients have no accounts here — they are the tenant's customers. A tenant's own backend
 * therefore calls these endpoints on a recipient's behalf and names the recipient explicitly.
 */
@RestController
@RequestMapping("/api/v1/inbox")
@Tag(name = "In-app inbox",
        description = "Reading in-app messages on a recipient's behalf. Requires TENANT_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a tenant administrator", content = @Content)
})
public class InboxController {

    private static final int MAX_PAGE_SIZE = 100;

    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    @Operation(summary = "A recipient's in-app messages, newest first",
            description = """
                    Returns only messages that actually reached the recipient. A queued, failed or
                    cancelled notification was never delivered, so showing it in an inbox would be
                    showing someone a message that was never sent to them.
                    """)
    public ResponseEntity<PageResponse<InboxService.InboxMessage>> inbox(
            @Parameter(description = "The tenant's own identifier for the recipient", example = "cust-7")
            @RequestParam String recipientRef,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(inboxService.inbox(recipientRef,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many delivered messages a recipient has not read",
            description = "Cheap enough to poll for a notification badge.")
    public ResponseEntity<InboxService.UnreadCount> unreadCount(@RequestParam String recipientRef) {
        return ResponseEntity.ok(inboxService.unreadCount(recipientRef));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one message read",
            description = "Idempotent. A repeated call does not overwrite the original read time.")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Not an in-app message", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such message in this tenant", content = @Content)
    })
    public ResponseEntity<InboxService.InboxMessage> markRead(@PathVariable UUID notificationId) {
        return ResponseEntity.ok(inboxService.markRead(notificationId));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every unread message for a recipient as read")
    public ResponseEntity<InboxService.UnreadCount> markAllRead(@RequestParam String recipientRef) {
        return ResponseEntity.ok(inboxService.markAllRead(recipientRef));
    }
}
