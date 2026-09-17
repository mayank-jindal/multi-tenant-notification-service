package com.notifly.notification.common.crypto;

import com.notifly.notification.common.config.NotiflyProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts provider credentials at rest with AES-256-GCM.
 *
 * <p>GCM rather than CBC because it is authenticated: tampering with stored ciphertext produces a
 * decryption failure rather than silently different plaintext. A unique random IV is generated
 * per encryption and prepended to the ciphertext — reusing an IV under GCM is catastrophic, not
 * merely weak, so it is never derived or cached.
 *
 * <p>The configured key is a passphrase of any length, hashed to exactly 32 bytes. That is key
 * stretching only in the loosest sense and is not a substitute for a real secret; it exists so
 * that a misconfigured short key fails safely rather than throwing an unhelpful key-length error.
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final NotiflyProperties.Encryption config;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(NotiflyProperties properties, Environment environment) {
        this.config = properties.security().encryption();
        this.environment = environment;
        this.key = deriveKey(config.key());
    }

    @PostConstruct
    void rejectDevelopmentKeyOutsideDevelopment() {
        if (!config.isDevelopmentKey()) {
            return;
        }
        if (environment.matchesProfiles("prod", "production", "staging")) {
            throw new IllegalStateException(
                    "The development credential encryption key is in use under a production profile. "
                    + "Set the ENCRYPTION_KEY environment variable to a strong random value.");
        }
        log.warn("Using the development credential encryption key. Set ENCRYPTION_KEY before any real deployment.");
    }

    /** Encrypts plaintext, returning Base64 of {@code iv || ciphertext || tag}. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            // The message must not echo the plaintext, which is the thing being protected.
            throw new IllegalStateException("Failed to encrypt credential", ex);
        }
    }

    /** Decrypts what {@link #encrypt} produced. */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Ciphertext is too short to contain an IV");
            }

            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to decrypt credential; the encryption key may have changed", ex);
        }
    }

    /**
     * A masked form safe to return over the API: the last four characters, everything else
     * replaced. Short values are masked entirely rather than partially revealed.
     */
    public String hint(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        String trimmed = plaintext.trim();
        if (trimmed.length() <= 8) {
            return "\u2022".repeat(Math.min(trimmed.length(), 8));
        }
        return "\u2022".repeat(8) + trimmed.substring(trimmed.length() - 4);
    }

    private SecretKey deriveKey(String passphrase) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(passphrase.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive the credential encryption key", ex);
        }
    }
}
