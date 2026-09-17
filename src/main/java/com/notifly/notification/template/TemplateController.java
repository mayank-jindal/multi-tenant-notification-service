package com.notifly.notification.template;

import com.notifly.notification.common.dto.PageResponse;
import com.notifly.notification.template.dto.CreateTemplateRequest;
import com.notifly.notification.template.dto.CreateVersionRequest;
import com.notifly.notification.template.dto.TemplateResponse;
import com.notifly.notification.template.dto.TemplateVersionResponse;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Template authoring for the caller's own tenant.
 */
@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "Templates", description = "Tenant-defined message templates and their versions. Requires TENANT_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a tenant administrator", content = @Content),
        @ApiResponse(responseCode = "404", description = "No such template in the caller's tenant", content = @Content)
})
public class TemplateController {

    private static final int MAX_PAGE_SIZE = 200;

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @PostMapping
    @Operation(summary = "Create a template",
            description = "Optionally creates draft version 1 in the same request. A template with no published version cannot be sent.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "409", description = "Code already used in this tenant", content = @Content)
    })
    public ResponseEntity<TemplateResponse> create(@Valid @RequestBody CreateTemplateRequest request) {
        TemplateResponse created = templateService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/templates/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "List templates")
    public ResponseEntity<PageResponse<TemplateResponse>> list(
            @RequestParam(required = false) Boolean archived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(templateService.list(archived, pageable));
    }

    @GetMapping("/{templateId}")
    @Operation(summary = "Fetch one template")
    public ResponseEntity<TemplateResponse> get(@PathVariable UUID templateId) {
        return ResponseEntity.ok(templateService.get(templateId));
    }

    @PostMapping("/{templateId}/archive")
    @Operation(summary = "Archive a template",
            description = "Blocks new sends. Delivery history keeps referencing it, which is why templates are archived rather than deleted.")
    public ResponseEntity<TemplateResponse> archive(@PathVariable UUID templateId) {
        return ResponseEntity.ok(templateService.setArchived(templateId, true));
    }

    @PostMapping("/{templateId}/unarchive")
    @Operation(summary = "Return an archived template to use")
    public ResponseEntity<TemplateResponse> unarchive(@PathVariable UUID templateId) {
        return ResponseEntity.ok(templateService.setArchived(templateId, false));
    }

    // ---------------------------------------------------------------- versions

    @PostMapping("/{templateId}/versions")
    @Operation(summary = "Create a draft version",
            description = "Version numbers increase monotonically. New versions always start as drafts.")
    @ApiResponses(@ApiResponse(responseCode = "201", description = "Draft created"))
    public ResponseEntity<TemplateVersionResponse> createVersion(
            @PathVariable UUID templateId,
            @Valid @RequestBody CreateVersionRequest request) {
        TemplateVersionResponse created = templateService.createVersion(templateId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/templates/" + templateId + "/versions/" + created.versionNumber()))
                .body(created);
    }

    @GetMapping("/{templateId}/versions")
    @Operation(summary = "List a template's versions, newest first")
    public ResponseEntity<List<TemplateVersionResponse>> listVersions(@PathVariable UUID templateId) {
        return ResponseEntity.ok(templateService.listVersions(templateId));
    }

    @GetMapping("/{templateId}/versions/{versionNumber}")
    @Operation(summary = "Fetch one version")
    public ResponseEntity<TemplateVersionResponse> getVersion(@PathVariable UUID templateId,
                                                              @PathVariable int versionNumber) {
        return ResponseEntity.ok(templateService.getVersion(templateId, versionNumber));
    }

    @PutMapping("/{templateId}/versions/{versionNumber}")
    @Operation(summary = "Replace a draft's content",
            description = """
                    Drafts only. A published version is what sends resolve to and what historical
                    deliveries were rendered from, so editing one would retroactively change the
                    meaning of messages already sent. Create a new version instead.
                    """)
    @ApiResponses(@ApiResponse(responseCode = "409", description = "Version is published or archived", content = @Content))
    public ResponseEntity<TemplateVersionResponse> updateDraft(
            @PathVariable UUID templateId,
            @PathVariable int versionNumber,
            @Valid @RequestBody CreateVersionRequest request) {
        return ResponseEntity.ok(templateService.updateDraft(templateId, versionNumber, request));
    }

    @PostMapping("/{templateId}/versions/{versionNumber}/publish")
    @Operation(summary = "Publish a draft",
            description = """
                    Makes this the version sends resolve to, archiving whichever was published
                    before. At most one version is published at a time, enforced by a unique
                    index. Idempotent.
                    """)
    public ResponseEntity<TemplateVersionResponse> publish(@PathVariable UUID templateId,
                                                           @PathVariable int versionNumber) {
        return ResponseEntity.ok(templateService.publish(templateId, versionNumber));
    }
}
