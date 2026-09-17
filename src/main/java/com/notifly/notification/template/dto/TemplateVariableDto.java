package com.notifly.notification.template.dto;

import com.notifly.notification.template.TemplateVariable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One entry in a template version's declared variable contract.
 *
 * @param name         variable name as it appears between the {@code {{ }}} delimiters
 * @param required     whether a send must supply it
 * @param description  human-readable purpose
 * @param defaultValue value used when an optional variable is not supplied
 */
@Schema(description = "A variable a template declares")
public record TemplateVariableDto(

        @Schema(example = "customerName")
        @NotBlank(message = "name is required")
        @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_.]{0,63}$",
                message = "must start with a letter or underscore and contain only letters, digits, underscores and dots")
        String name,

        boolean required,

        @Size(max = 500, message = "must be at most 500 characters")
        String description,

        @Size(max = 500, message = "must be at most 500 characters")
        String defaultValue) {

    public TemplateVariable toDomain() {
        return new TemplateVariable(name, required, description, defaultValue);
    }

    public static TemplateVariableDto from(TemplateVariable variable) {
        return new TemplateVariableDto(
                variable.name(), variable.required(), variable.description(), variable.defaultValue());
    }
}
