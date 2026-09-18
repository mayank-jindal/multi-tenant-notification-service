package com.notifly.notification.delivery.dto;

import com.notifly.notification.common.model.Channel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A request to send a templated notification to one or more recipients.
 *
 * <p>The same shape covers all three cases the brief names: a single send is one recipient, a
 * bulk send is many, and a scheduled send is either with {@code scheduledAt} set. Separate
 * endpoints would have triplicated the validation, rendering and idempotency logic for no gain.
 *
 * @param templateCode the tenant's template identifier; its published version is used
 * @param recipients   who to send to
 * @param variables    request-level variable values, overridable per recipient
 * @param channels     restricts delivery to these channels; defaults to every channel that the
 *                     template, the recipient and the tenant's configuration all support
 * @param scheduledAt  when to dispatch; null or past means immediately
 * @param priority     1 is most urgent, 9 least
 * @param maxAttempts  overrides the configured retry budget for this send
 */
@Schema(description = "A templated send to one or more recipients")
public record SendNotificationRequest(

        @Schema(example = "order-shipped")
        @NotBlank(message = "templateCode is required")
        @Size(max = 64, message = "must be at most 64 characters")
        String templateCode,

        @Valid
        @NotEmpty(message = "at least one recipient is required")
        @Size(max = 1000, message = "a single request may carry at most 1000 recipients")
        List<RecipientDto> recipients,

        @Schema(example = "{\"customerName\": \"Sam\", \"orderId\": \"A-1001\"}")
        Map<String, Object> variables,

        @Schema(description = "Optional channel filter. Omit to use every channel available for each recipient.")
        Set<Channel> channels,

        @Schema(description = "ISO-8601 instant. Null or in the past dispatches immediately.",
                example = "2026-12-24T09:00:00Z")
        Instant scheduledAt,

        @Min(value = 1, message = "must be at least 1")
        @Max(value = 9, message = "must be at most 9")
        Integer priority,

        @Min(value = 1, message = "must be at least 1")
        @Max(value = 20, message = "must be at most 20")
        Integer maxAttempts) {

    public Map<String, Object> variablesOrEmpty() {
        return variables == null ? Map.of() : variables;
    }

    public boolean isScheduled() {
        return scheduledAt != null && scheduledAt.isAfter(Instant.now());
    }

    public short priorityOrDefault() {
        return priority == null ? (short) 5 : priority.shortValue();
    }
}
