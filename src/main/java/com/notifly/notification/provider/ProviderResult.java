package com.notifly.notification.provider;

import com.notifly.notification.delivery.DeliveryOutcome;

/**
 * The outcome of one provider call.
 *
 * <p>The transient/permanent distinction is the whole basis of the retry policy, so a provider
 * adapter is required to make that judgement rather than throwing a bare exception and leaving
 * the dispatcher to guess. Guessing would mean either retrying invalid addresses forever or
 * giving up on a momentary timeout.
 *
 * @param outcome           whether the call succeeded, and if not whether retrying could help
 * @param providerMessageId the provider's identifier for the accepted message, null on failure
 * @param errorCode         short machine-readable failure code, null on success
 * @param errorMessage      human-readable detail, null on success
 */
public record ProviderResult(
        DeliveryOutcome outcome,
        String providerMessageId,
        String errorCode,
        String errorMessage) {

    public static ProviderResult success(String providerMessageId) {
        return new ProviderResult(DeliveryOutcome.SUCCESS, providerMessageId, null, null);
    }

    /** Timeouts, throttling, provider 5xx — worth another attempt after a backoff. */
    public static ProviderResult transientFailure(String errorCode, String message) {
        return new ProviderResult(DeliveryOutcome.TRANSIENT_FAILURE, null, errorCode, message);
    }

    /** Invalid address, rejected content, revoked token — retrying cannot help. */
    public static ProviderResult permanentFailure(String errorCode, String message) {
        return new ProviderResult(DeliveryOutcome.PERMANENT_FAILURE, null, errorCode, message);
    }

    public boolean isSuccess() {
        return outcome == DeliveryOutcome.SUCCESS;
    }
}
