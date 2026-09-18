package com.notifly.notification.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The result of a submission.
 *
 * <p>Reports what was accepted and what was skipped rather than only a success flag: a caller
 * sending to a thousand recipients needs to know that four were suppressed and two had no usable
 * channel, and an opaque "accepted" would hide that.
 *
 * @param requestId     identifier for the submission, for later lookup
 * @param status        the submission's aggregate state
 * @param scheduledAt   when dispatch begins, null for immediate sends
 * @param accepted      how many notifications were queued
 * @param suppressed    how many were skipped because the address is suppressed
 * @param skipped       how many recipient/channel pairs had no usable channel
 * @param notifications the created notifications; omitted for large batches
 * @param idempotentReplay whether this response is a replay of an earlier identical submission
 */
@Schema(description = "The outcome of a send submission")
public record SendResponse(
        UUID requestId,
        String status,
        Instant scheduledAt,
        int accepted,
        int suppressed,
        int skipped,
        List<NotificationResponse> notifications,
        boolean idempotentReplay) {

    /** Above this many notifications the inline list is omitted, to keep responses bounded. */
    public static final int INLINE_LIMIT = 50;
}
