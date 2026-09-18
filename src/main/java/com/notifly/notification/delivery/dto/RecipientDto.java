package com.notifly.notification.delivery.dto;

import com.notifly.notification.common.model.Channel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * One recipient of a send.
 *
 * <p>Addresses are per channel because a channel's destination is channel-specific: an email
 * address is not a phone number. A single {@code address} field would have forced either one
 * request per channel or a guess about what the value means.
 *
 * @param ref       the tenant's own identifier for this person, used for the in-app channel
 * @param addresses destination per channel
 * @param variables per-recipient variable values, merged over the request-level ones
 */
@Schema(description = "A recipient and its per-channel destinations")
public record RecipientDto(

        @Schema(example = "user-42", description = "Required for IN_APP, optional elsewhere")
        @Size(max = 255, message = "must be at most 255 characters")
        String ref,

        @Schema(example = "{\"EMAIL\": \"sam@acme.com\", \"SMS\": \"+919876543210\"}")
        @NotEmpty(message = "at least one channel address is required")
        Map<Channel, @Size(max = 512) String> addresses,

        @Schema(description = "Overrides request-level variables for this recipient, enabling personalised bulk sends")
        Map<String, Object> variables) {

    public Map<String, Object> variablesOrEmpty() {
        return variables == null ? Map.of() : variables;
    }
}
