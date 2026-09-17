package com.notifly.notification.template.dto;

import com.notifly.notification.template.Template;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * @param id                   the template's id
 * @param code                 stable identifier quoted when sending
 * @param name                 human-readable name
 * @param description          what this template is for
 * @param archived             whether it still accepts new sends
 * @param publishedVersion     the version number a send currently resolves to, null if none
 * @param latestVersion        the highest version number that exists, null if none
 * @param createdAt            when the template was created
 * @param updatedAt            when it last changed
 */
@Schema(description = "A template")
public record TemplateResponse(
        UUID id,
        String code,
        String name,
        String description,
        boolean archived,
        Integer publishedVersion,
        Integer latestVersion,
        Instant createdAt,
        Instant updatedAt) {

    public static TemplateResponse from(Template template, Integer publishedVersion, Integer latestVersion) {
        return new TemplateResponse(
                template.getId(),
                template.getCode(),
                template.getName(),
                template.getDescription(),
                template.isArchived(),
                publishedVersion,
                latestVersion,
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
