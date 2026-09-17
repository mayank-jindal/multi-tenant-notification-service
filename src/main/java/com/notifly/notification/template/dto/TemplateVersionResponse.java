package com.notifly.notification.template.dto;

import com.notifly.notification.common.model.Channel;
import com.notifly.notification.template.TemplateChannelBody;
import com.notifly.notification.template.TemplateVersion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @param id            the version's id
 * @param templateId    the template it belongs to
 * @param versionNumber monotonically increasing within the template
 * @param status        DRAFT, PUBLISHED or ARCHIVED
 * @param variables     the declared variable contract
 * @param bodies        per-channel content
 * @param channels      which channels this version can be sent on
 * @param publishedAt   when it was published, null for a draft
 * @param createdAt     when it was created
 */
@Schema(description = "One immutable version of a template")
public record TemplateVersionResponse(
        UUID id,
        UUID templateId,
        int versionNumber,
        String status,
        List<TemplateVariableDto> variables,
        Map<String, ChannelBodyDto> bodies,
        List<String> channels,
        Instant publishedAt,
        Instant createdAt) {

    public static TemplateVersionResponse from(TemplateVersion version, List<TemplateChannelBody> bodies) {
        Map<String, ChannelBodyDto> bodyMap = bodies.stream()
                .collect(Collectors.toMap(b -> b.getChannel().name(), ChannelBodyDto::from));

        List<String> channels = bodies.stream()
                .map(TemplateChannelBody::getChannel)
                .sorted()
                .map(Channel::name)
                .toList();

        return new TemplateVersionResponse(
                version.getId(),
                version.getTemplateId(),
                version.getVersionNumber(),
                version.getStatus().name(),
                version.getVariables().stream().map(TemplateVariableDto::from).toList(),
                bodyMap,
                channels,
                version.getPublishedAt(),
                version.getCreatedAt());
    }
}
