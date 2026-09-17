package com.notifly.notification.ratelimit;

import com.notifly.notification.ratelimit.dto.RateLimitPolicyRequest;
import com.notifly.notification.ratelimit.dto.RateLimitPolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Throughput governance.
 *
 * <p>Writes are {@code PUT} rather than {@code POST} because a limit for a given
 * (tenant, channel) pair is a single configured fact, not a collection: setting it twice with the
 * same body must leave the same state, and the database enforces that with a unique index.
 */
@RestController
@RequestMapping("/api/v1/admin/rate-limits")
@Tag(name = "Rate limits", description = "Token bucket policies. Writes require PLATFORM_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content),
        @ApiResponse(responseCode = "403", description = "Insufficient role", content = @Content)
})
public class RateLimitAdminController {

    private final RateLimitPolicyService policyService;

    public RateLimitAdminController(RateLimitPolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping("/global")
    @Operation(summary = "List platform-wide rate limits")
    public ResponseEntity<List<RateLimitPolicyResponse>> listGlobal() {
        return ResponseEntity.ok(policyService.listGlobal());
    }

    @PutMapping("/global")
    @Operation(summary = "Set a platform-wide rate limit",
            description = """
                    Applies to every tenant that has no more specific policy. Omit `channel` to
                    limit all channels at once. Idempotent.
                    """)
    @ApiResponses(@ApiResponse(responseCode = "400",
            description = "capacity is below refillTokens, making the configured rate unreachable",
            content = @Content))
    public ResponseEntity<RateLimitPolicyResponse> setGlobal(
            @Valid @RequestBody RateLimitPolicyRequest request) {
        return ResponseEntity.ok(policyService.upsertGlobal(request));
    }

    @GetMapping("/tenants/{tenantId}")
    @Operation(summary = "List the limits in force for a tenant",
            description = """
                    Includes the platform defaults the tenant inherits, not only its own
                    overrides, because an inherited limit is still a limit. Ordered most specific
                    first, so the first entry matching a channel is the one that applies.

                    A tenant administrator may read their own tenant's limits; reading another
                    tenant's requires PLATFORM_ADMIN.
                    """)
    public ResponseEntity<List<RateLimitPolicyResponse>> listForTenant(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(policyService.listEffective(tenantId));
    }

    @PutMapping("/tenants/{tenantId}")
    @Operation(summary = "Set a tenant's rate limit",
            description = "Overrides the platform default for this tenant. Idempotent.")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "No such tenant", content = @Content))
    public ResponseEntity<RateLimitPolicyResponse> setForTenant(
            @PathVariable UUID tenantId,
            @Valid @RequestBody RateLimitPolicyRequest request) {
        return ResponseEntity.ok(policyService.upsertForTenant(tenantId, request));
    }

    @DeleteMapping("/{policyId}")
    @Operation(summary = "Remove a rate limit policy",
            description = "The next most general policy then applies; removing a tenant override restores the platform default.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed"),
            @ApiResponse(responseCode = "404", description = "No such policy", content = @Content)
    })
    public ResponseEntity<Void> delete(@PathVariable UUID policyId) {
        policyService.delete(policyId);
        return ResponseEntity.noContent().build();
    }
}
