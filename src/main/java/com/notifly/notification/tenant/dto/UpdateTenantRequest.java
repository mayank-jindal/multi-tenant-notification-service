package com.notifly.notification.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * A partial update. Null fields are left unchanged, which is why every field is boxed — a
 * primitive {@code int} could not distinguish "set to zero" from "not supplied".
 *
 * <p>The slug is absent by design: it is referenced by JWT claims and admin URLs, so renaming it
 * would invalidate outstanding tokens and break links.
 *
 * @param name           new display name, or null to leave unchanged
 * @param dispatchWeight new fairness weight, or null to leave unchanged
 */
@Schema(description = "Fields to change on an existing tenant; omitted fields are left alone")
public record UpdateTenantRequest(

        @Size(max = 255, message = "must be at most 255 characters")
        String name,

        @Min(value = 1, message = "must be at least 1")
        @Max(value = 100, message = "must be at most 100")
        Integer dispatchWeight) {
}
