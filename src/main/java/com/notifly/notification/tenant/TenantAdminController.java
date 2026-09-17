package com.notifly.notification.tenant;

import com.notifly.notification.common.dto.PageResponse;
import com.notifly.notification.tenant.dto.CreateTenantRequest;
import com.notifly.notification.tenant.dto.SuspendTenantRequest;
import com.notifly.notification.tenant.dto.TenantResponse;
import com.notifly.notification.tenant.dto.UpdateTenantRequest;
import com.notifly.notification.user.dto.CreateUserRequest;
import com.notifly.notification.user.dto.UserResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Platform administration: tenant lifecycle and tenant-administrator provisioning.
 *
 * <p>Authorization is enforced on {@link TenantService}, not here. The controller maps HTTP to
 * calls and does nothing else, so the rules cannot be bypassed by reaching the service another
 * way.
 *
 * <p>There is no delete endpoint. Tenant data is referenced by an append-only audit trail and by
 * delivery history, so removal is expressed as suspension — reversible, and it preserves the
 * record of what the tenant did.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@Tag(name = "Platform administration", description = "Tenant lifecycle and administrator provisioning. Requires PLATFORM_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a platform administrator", content = @Content)
})
public class TenantAdminController {

    private static final int MAX_PAGE_SIZE = 200;

    private final TenantService tenantService;

    public TenantAdminController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    @Operation(summary = "Create a tenant",
            description = "Optionally provisions the tenant's first administrator in the same transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "409", description = "Slug or admin email already in use", content = @Content)
    })
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        TenantResponse created = tenantService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/tenants/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "List tenants")
    public ResponseEntity<PageResponse<TenantResponse>> list(
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false) TenantStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // The page size is clamped rather than validated away: a caller asking for a million rows
        // gets the maximum instead of an error, and the database is protected either way.
        int clamped = Math.clamp(size, 1, MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), clamped, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(tenantService.list(status, pageable));
    }

    @GetMapping("/{tenantId}")
    @Operation(summary = "Fetch one tenant")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "No such tenant", content = @Content))
    public ResponseEntity<TenantResponse> get(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(tenantService.get(tenantId));
    }

    @PatchMapping("/{tenantId}")
    @Operation(summary = "Update a tenant",
            description = "Partial update; omitted fields are left unchanged. The slug is immutable.")
    public ResponseEntity<TenantResponse> update(@PathVariable UUID tenantId,
                                                 @Valid @RequestBody UpdateTenantRequest request) {
        return ResponseEntity.ok(tenantService.update(tenantId, request));
    }

    @PostMapping("/{tenantId}/suspend")
    @Operation(summary = "Suspend a tenant",
            description = """
                    Blocks new submissions and stops the dispatcher claiming this tenant's queued
                    work. Queued notifications are held, not cancelled, so reactivating resumes
                    the backlog rather than losing it. Idempotent.
                    """)
    public ResponseEntity<TenantResponse> suspend(@PathVariable UUID tenantId,
                                                  @RequestBody(required = false) @Valid SuspendTenantRequest request) {
        return ResponseEntity.ok(tenantService.suspend(tenantId, request == null ? null : request.reason()));
    }

    @PostMapping("/{tenantId}/activate")
    @Operation(summary = "Reactivate a suspended tenant",
            description = "Held work becomes claimable again. Idempotent.")
    public ResponseEntity<TenantResponse> activate(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(tenantService.activate(tenantId));
    }

    // ---------------------------------------------------------------- administrators

    @PostMapping("/{tenantId}/users")
    @Operation(summary = "Provision a tenant administrator")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "409", description = "Email already in use", content = @Content)
    })
    public ResponseEntity<UserResponse> createAdmin(@PathVariable UUID tenantId,
                                                    @Valid @RequestBody CreateUserRequest request) {
        UserResponse created = tenantService.provisionAdmin(tenantId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/admin/tenants/" + tenantId + "/users/" + created.id()))
                .body(created);
    }

    @GetMapping("/{tenantId}/users")
    @Operation(summary = "List a tenant's administrators")
    public ResponseEntity<List<UserResponse>> listAdmins(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(tenantService.listAdmins(tenantId));
    }

    @PatchMapping("/{tenantId}/users/{userId}/enabled")
    @Operation(summary = "Enable or disable a tenant administrator",
            description = "Accounts are disabled rather than deleted so the audit trail stays resolvable.")
    public ResponseEntity<UserResponse> setEnabled(@PathVariable UUID tenantId,
                                                   @PathVariable UUID userId,
                                                   @RequestParam boolean enabled) {
        return ResponseEntity.ok(tenantService.setUserEnabled(tenantId, userId, enabled));
    }
}
