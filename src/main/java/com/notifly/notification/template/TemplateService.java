package com.notifly.notification.template;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.common.dto.PageResponse;
import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.template.dto.ChannelBodyDto;
import com.notifly.notification.template.dto.CreateTemplateRequest;
import com.notifly.notification.template.dto.CreateVersionRequest;
import com.notifly.notification.template.dto.TemplateResponse;
import com.notifly.notification.template.dto.TemplateVariableDto;
import com.notifly.notification.template.dto.TemplateVersionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Template authoring: templates, their immutable versions, and publication.
 *
 * <p>The shape to understand here is that a template holds no content. Content lives in versions,
 * exactly one of which is published at a time, and a published version is never edited again.
 * Editing in place would mean a template change silently rewrites what already-queued
 * notifications say and makes historical delivery records unexplainable.
 *
 * <p>All access is scoped by the tenant discriminator. No tenant id appears in any signature,
 * because these operations are always on the caller's own tenant.
 */
@Service
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository versionRepository;
    private final TemplateChannelBodyRepository bodyRepository;
    private final AuditService auditService;

    public TemplateService(TemplateRepository templateRepository,
                           TemplateVersionRepository versionRepository,
                           TemplateChannelBodyRepository bodyRepository,
                           AuditService auditService) {
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
        this.bodyRepository = bodyRepository;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- templates

    @Transactional
    public TemplateResponse create(CreateTemplateRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        String code = request.code().trim().toLowerCase();

        if (templateRepository.existsByTenantIdAndCode(tenantId, code)) {
            throw Errors.alreadyExists("Template", "code", code);
        }

        Template template = new Template(code, request.name().trim());
        template.setDescription(request.description());
        Template saved = templateRepository.save(template);

        Integer initialVersionNumber = null;
        if (request.initialVersion() != null) {
            initialVersionNumber = createVersionInternal(saved, request.initialVersion()).getVersionNumber();
        }

        auditService.record(AuditEventType.TEMPLATE_CREATED, "Template", saved.getId(),
                Map.of("code", code, "name", saved.getName(),
                        "initialVersion", String.valueOf(initialVersionNumber)));

        log.info("Tenant {} created template {}", tenantId, code);
        return TemplateResponse.from(saved, null, initialVersionNumber);
    }

    @Transactional(readOnly = true)
    public PageResponse<TemplateResponse> list(Boolean archived, Pageable pageable) {
        UUID tenantId = TenantContext.requireTenantId();
        Page<Template> page = archived == null
                ? templateRepository.findAllByTenantId(tenantId, pageable)
                : templateRepository.findAllByTenantIdAndArchived(tenantId, archived, pageable);

        return PageResponse.of(page, this::withVersionSummary);
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(UUID templateId) {
        return withVersionSummary(requireTemplate(templateId));
    }

    @Transactional
    public TemplateResponse setArchived(UUID templateId, boolean archived) {
        Template template = requireTemplate(templateId);
        template.setArchived(archived);
        Template saved = templateRepository.save(template);

        auditService.record(archived ? AuditEventType.TEMPLATE_ARCHIVED : AuditEventType.TEMPLATE_UPDATED,
                "Template", templateId, Map.of("archived", archived, "code", template.getCode()));
        return withVersionSummary(saved);
    }

    // ---------------------------------------------------------------- versions

    @Transactional
    public TemplateVersionResponse createVersion(UUID templateId, CreateVersionRequest request) {
        Template template = requireTemplate(templateId);
        if (template.isArchived()) {
            throw Errors.conflict(ErrorCode.CONFLICT, "Cannot add versions to an archived template");
        }

        TemplateVersion version = createVersionInternal(template, request);
        auditService.record(AuditEventType.TEMPLATE_VERSION_CREATED, "TemplateVersion", version.getId(),
                Map.of("templateId", templateId.toString(), "versionNumber", version.getVersionNumber()));

        return TemplateVersionResponse.from(version, bodyRepository.findAllByTemplateVersionId(version.getId()));
    }

    @Transactional(readOnly = true)
    public List<TemplateVersionResponse> listVersions(UUID templateId) {
        requireTemplate(templateId);
        return versionRepository.findAllByTemplateIdOrderByVersionNumberDesc(templateId).stream()
                .map(v -> TemplateVersionResponse.from(v, bodyRepository.findAllByTemplateVersionId(v.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TemplateVersionResponse getVersion(UUID templateId, int versionNumber) {
        requireTemplate(templateId);
        TemplateVersion version = versionRepository
                .findByTemplateIdAndVersionNumber(templateId, versionNumber)
                .orElseThrow(() -> Errors.notFound("TemplateVersion", versionNumber));

        return TemplateVersionResponse.from(version, bodyRepository.findAllByTemplateVersionId(version.getId()));
    }

    /**
     * Replaces a draft's content.
     *
     * <p>Rejected for anything but a draft. A published version is what sends resolve to and what
     * historical deliveries were rendered from; allowing an edit would retroactively change the
     * meaning of messages already sent.
     */
    @Transactional
    public TemplateVersionResponse updateDraft(UUID templateId, int versionNumber, CreateVersionRequest request) {
        requireTemplate(templateId);
        TemplateVersion version = versionRepository
                .findByTemplateIdAndVersionNumber(templateId, versionNumber)
                .orElseThrow(() -> Errors.notFound("TemplateVersion", versionNumber));

        if (!version.isEditable()) {
            throw Errors.conflict(ErrorCode.CONFLICT,
                    "Version " + versionNumber + " is " + version.getStatus()
                    + " and cannot be edited; create a new version instead");
        }

        validateBodies(request);
        version.setVariables(request.variablesOrEmpty().stream().map(TemplateVariableDto::toDomain).toList());
        versionRepository.save(version);

        bodyRepository.deleteAllByTemplateVersionId(version.getId());
        bodyRepository.flush();
        writeBodies(version, request);

        auditService.record(AuditEventType.TEMPLATE_UPDATED, "TemplateVersion", version.getId(),
                Map.of("templateId", templateId.toString(), "versionNumber", versionNumber));

        return TemplateVersionResponse.from(version, bodyRepository.findAllByTemplateVersionId(version.getId()));
    }

    /**
     * Publishes a draft, archiving whichever version was published before it.
     *
     * <p>The swap happens in one transaction because a unique partial index permits at most one
     * published version per template — the old one must be archived before the new one is
     * published, and a failure between the two would leave the template with none.
     */
    @Transactional
    public TemplateVersionResponse publish(UUID templateId, int versionNumber) {
        Template template = requireTemplate(templateId);
        if (template.isArchived()) {
            throw Errors.conflict(ErrorCode.CONFLICT, "Cannot publish a version of an archived template");
        }

        TemplateVersion version = versionRepository
                .findByTemplateIdAndVersionNumber(templateId, versionNumber)
                .orElseThrow(() -> Errors.notFound("TemplateVersion", versionNumber));

        if (version.isPublished()) {
            return TemplateVersionResponse.from(
                    version, bodyRepository.findAllByTemplateVersionId(version.getId()));
        }
        if (!version.isEditable()) {
            throw Errors.conflict(ErrorCode.CONFLICT,
                    "Version " + versionNumber + " is archived and cannot be republished");
        }

        List<TemplateChannelBody> bodies = bodyRepository.findAllByTemplateVersionId(version.getId());
        if (bodies.isEmpty()) {
            throw Errors.badRequest(ErrorCode.VALIDATION_FAILED,
                    "Cannot publish a version with no channel bodies");
        }

        Integer previous = null;
        var currentlyPublished = versionRepository
                .findByTemplateIdAndStatus(templateId, TemplateVersionStatus.PUBLISHED);
        if (currentlyPublished.isPresent()) {
            TemplateVersion old = currentlyPublished.get();
            previous = old.getVersionNumber();
            old.archive();
            versionRepository.save(old);
            // Forced before the new publish so the unique index sees the old row released.
            versionRepository.flush();
        }

        version.publish(Instant.now());
        versionRepository.save(version);

        auditService.record(AuditEventType.TEMPLATE_VERSION_PUBLISHED, "TemplateVersion", version.getId(),
                Map.of("templateId", templateId.toString(),
                        "versionNumber", versionNumber,
                        "previousVersion", String.valueOf(previous)));

        log.info("Published template {} version {} (was {})", template.getCode(), versionNumber, previous);
        return TemplateVersionResponse.from(version, bodies);
    }

    // ---------------------------------------------------------------- internal use

    /**
     * Resolves a template code to its published version for the send path.
     *
     * <p>No role requirement: the caller has already been authorized to send.
     */
    @PreAuthorize("permitAll()")
    @Transactional(readOnly = true)
    public TemplateVersion requirePublishedVersion(UUID tenantId, String code) {
        Template template = templateRepository.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> Errors.notFound("Template", code));

        if (template.isArchived()) {
            throw Errors.badRequest(ErrorCode.TEMPLATE_NOT_PUBLISHED,
                    "Template " + code + " is archived and cannot be used for new sends");
        }

        return versionRepository.findByTemplateIdAndStatus(template.getId(), TemplateVersionStatus.PUBLISHED)
                .orElseThrow(() -> Errors.badRequest(ErrorCode.TEMPLATE_NOT_PUBLISHED,
                        "Template " + code + " has no published version")
                        .with("templateCode", code));
    }

    // ---------------------------------------------------------------- helpers

    private TemplateVersion createVersionInternal(Template template, CreateVersionRequest request) {
        validateBodies(request);

        int next = versionRepository.nextVersionNumber(template.getId());
        TemplateVersion version = new TemplateVersion(template.getId(), next);
        version.setVariables(request.variablesOrEmpty().stream().map(TemplateVariableDto::toDomain).toList());
        TemplateVersion saved = versionRepository.save(version);

        writeBodies(saved, request);
        return saved;
    }

    private void writeBodies(TemplateVersion version, CreateVersionRequest request) {
        request.bodies().forEach((channel, dto) -> {
            TemplateChannelBody body = new TemplateChannelBody(version.getId(), channel);
            body.setSubject(dto.subject());
            body.setBodyText(dto.bodyText());
            body.setBodyHtml(dto.bodyHtml());
            bodyRepository.save(body);
        });
    }

    private void validateBodies(CreateVersionRequest request) {
        if (request.bodies() == null || request.bodies().isEmpty()) {
            throw Errors.badRequest(ErrorCode.VALIDATION_FAILED, "At least one channel body is required");
        }

        // Duplicate variable names would make the contract ambiguous: two declarations of the
        // same name could disagree on whether it is required.
        Set<String> seen = new HashSet<>();
        for (TemplateVariableDto variable : request.variablesOrEmpty()) {
            if (!seen.add(variable.name())) {
                throw Errors.badRequest(ErrorCode.VALIDATION_FAILED,
                        "Variable '" + variable.name() + "' is declared more than once");
            }
        }

        request.bodies().forEach((channel, body) -> {
            if (body == null || !body.hasContent()) {
                throw Errors.badRequest(ErrorCode.VALIDATION_FAILED,
                        "Channel " + channel + " must have bodyText or bodyHtml");
            }
            // An HTML-only email has no fallback for clients that will not render it, and SMS,
            // push and in-app have no HTML rendering at all.
            if (channel != Channel.EMAIL && (body.bodyText() == null || body.bodyText().isBlank())) {
                throw Errors.badRequest(ErrorCode.VALIDATION_FAILED,
                        "Channel " + channel + " requires bodyText; only EMAIL supports an HTML body");
            }
        });
    }

    private Template requireTemplate(UUID templateId) {
        UUID tenantId = TenantContext.requireTenantId();
        // Scoped explicitly as well as by the discriminator: the redundancy costs nothing and
        // makes the intent legible at the call site.
        return templateRepository.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> Errors.notFound("Template", templateId));
    }

    private TemplateResponse withVersionSummary(Template template) {
        List<TemplateVersion> versions =
                versionRepository.findAllByTemplateIdOrderByVersionNumberDesc(template.getId());

        Integer published = versions.stream()
                .filter(TemplateVersion::isPublished)
                .map(TemplateVersion::getVersionNumber)
                .findFirst()
                .orElse(null);

        Integer latest = versions.isEmpty() ? null : versions.getFirst().getVersionNumber();
        return TemplateResponse.from(template, published, latest);
    }
}
