package com.notifly.notification.template;

/**
 * One entry in a template version's declared variable contract, persisted as JSON.
 *
 * @param name        variable name as it appears between the {@code {{ }}} delimiters
 * @param required    whether a send request must supply it; optional variables fall back to
 *                    {@code defaultValue}, or render as empty if that is absent
 * @param description human-readable purpose, surfaced in the template API for tenant admins
 * @param defaultValue value substituted when an optional variable is not supplied
 */
public record TemplateVariable(
        String name,
        boolean required,
        String description,
        String defaultValue) {

    public TemplateVariable {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Template variable name must not be blank");
        }
    }

    public static TemplateVariable required(String name) {
        return new TemplateVariable(name, true, null, null);
    }

    public static TemplateVariable optional(String name, String defaultValue) {
        return new TemplateVariable(name, false, null, defaultValue);
    }
}
