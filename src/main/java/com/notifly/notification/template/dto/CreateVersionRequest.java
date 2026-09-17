package com.notifly.notification.template.dto;

import com.notifly.notification.common.model.Channel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * The content of a new draft version.
 *
 * @param variables declared variable contract; rendering is strict against this list
 * @param bodies    per-channel content, keyed by channel
 */
@Schema(description = "Content for a new draft template version")
public record CreateVersionRequest(

        @Valid
        @Size(max = 100, message = "a template may declare at most 100 variables")
        List<TemplateVariableDto> variables,

        @Valid
        @NotEmpty(message = "at least one channel body is required")
        @Schema(description = "Content per channel. A template may target any subset of EMAIL, SMS, PUSH and IN_APP.")
        Map<Channel, ChannelBodyDto> bodies) {

    public List<TemplateVariableDto> variablesOrEmpty() {
        return variables == null ? List.of() : variables;
    }
}
