package com.notifly.notification.idempotency;

import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes submission idempotent.
 *
 * <p>The first of the two independent duplicate-prevention layers. This one covers client
 * retries — a network timeout where the caller never learned the request succeeded — by
 * recognising a repeated {@code Idempotency-Key} and returning the original outcome instead of
 * creating a second batch of work. The other layer, the dispatch lease, covers worker crashes.
 * Neither subsumes the other.
 *
 * <p>The key is claimed <em>before</em> the send runs, not after. Claiming afterwards would leave
 * a window in which two concurrent identical requests both find no key, both send, and both then
 * try to record it — the duplicate would already have happened by the time the conflict was
 * detected.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** How long a key is remembered. Long enough to cover any plausible client retry. */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyKeyRepository keyRepository;

    public IdempotencyService(IdempotencyKeyRepository keyRepository) {
        this.keyRepository = keyRepository;
    }

    /**
     * Claims a key for this request.
     *
     * @return the id of the original notification request when this is a replay, or empty when
     *         the caller should proceed with the send
     * @throws com.notifly.notification.common.error.ApiException if the key was used with a
     *                                                            different body, or if an
     *                                                            identical request is still in
     *                                                            flight
     */
    @Transactional
    public Optional<UUID> claim(UUID tenantId, String key, String requestBody, String path) {
        String fingerprint = fingerprint(requestBody);

        Optional<IdempotencyKey> existing = keyRepository.findByTenantIdAndIdempotencyKey(tenantId, key);
        if (existing.isPresent()) {
            return Optional.of(resolveExisting(existing.get(), key, fingerprint));
        }

        try {
            keyRepository.saveAndFlush(new IdempotencyKey(
                    tenantId, key, fingerprint, path, Instant.now().plus(RETENTION)));
            return Optional.empty();

        } catch (DataIntegrityViolationException ex) {
            // Another request claimed the same key between the read and the write. The unique
            // constraint is what makes this safe; without it both would have proceeded to send.
            IdempotencyKey raced = keyRepository.findByTenantIdAndIdempotencyKey(tenantId, key)
                    .orElseThrow(() -> Errors.conflict(ErrorCode.CONFLICT,
                            "Concurrent submission with this Idempotency-Key; retry shortly"));
            return Optional.of(resolveExisting(raced, key, fingerprint));
        }
    }

    /** Records the outcome against a claimed key, so a later replay can return it. */
    @Transactional
    public void recordOutcome(UUID tenantId, String key, UUID notificationRequestId) {
        keyRepository.findByTenantIdAndIdempotencyKey(tenantId, key).ifPresent(record -> {
            record.recordResponse(notificationRequestId, 202, null);
            keyRepository.save(record);
        });
    }

    /**
     * Releases a claimed key after a failed send, so the caller can correct the request and retry
     * with the same key. Leaving it claimed would strand the key against a request that never
     * produced anything.
     */
    @Transactional
    public void release(UUID tenantId, String key) {
        keyRepository.findByTenantIdAndIdempotencyKey(tenantId, key)
                .filter(record -> record.getNotificationRequestId() == null)
                .ifPresent(keyRepository::delete);
    }

    private UUID resolveExisting(IdempotencyKey record, String key, String fingerprint) {
        if (!record.matchesFingerprint(fingerprint)) {
            // Reusing a key with a different body is a client bug. Returning the earlier result
            // would answer a question the caller did not ask.
            throw Errors.idempotencyKeyReused(key);
        }
        if (record.getNotificationRequestId() == null) {
            throw Errors.conflict(ErrorCode.CONFLICT,
                    "An identical request with this Idempotency-Key is still in progress");
        }

        log.debug("Idempotent replay of key {}", key);
        return record.getNotificationRequestId();
    }

    /** SHA-256 of the raw request body, so a differing body is detected rather than assumed equal. */
    private String fingerprint(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint request body", ex);
        }
    }
}
