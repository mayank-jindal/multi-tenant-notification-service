package com.notifly.notification.common.crypto;

import com.notifly.notification.common.config.NotiflyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for credential encryption. No Spring context — this is pure cryptography and should
 * be testable, and fast, without one.
 */
class CredentialCipherTest {

    private CredentialCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = cipherWith("dev-only-test-key", new MockEnvironment());
    }

    @Test
    @DisplayName("what is encrypted can be decrypted")
    void roundTrips() {
        String secret = "dummy-provider-credential-ABCD";

        String encrypted = cipher.encrypt(secret);

        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    @DisplayName("the same plaintext never produces the same ciphertext twice")
    void encryptionIsNonDeterministic() {
        // A fresh random IV per encryption. Without it, identical credentials across tenants
        // would produce identical ciphertext, and anyone reading the table could tell which
        // tenants share a key without decrypting anything.
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ciphertexts.add(cipher.encrypt("the-same-secret"));
        }

        assertThat(ciphertexts).hasSize(50);
    }

    @Test
    @DisplayName("tampered ciphertext fails to decrypt rather than yielding wrong plaintext")
    void tamperingIsDetected() {
        // This is what GCM buys over CBC: authentication. A silently altered credential would be
        // sent to a provider as though it were genuine.
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("original-secret"));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    @Test
    @DisplayName("a different key cannot decrypt another key's ciphertext")
    void wrongKeyCannotDecrypt() {
        String encrypted = cipher.encrypt("secret-value");
        CredentialCipher otherCipher = cipherWith("dev-only-a-different-key", new MockEnvironment());

        assertThatThrownBy(() -> otherCipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("truncated ciphertext is rejected instead of read past its end")
    void truncatedCiphertextIsRejected() {
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[4])))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("nulls pass through so an unset credential needs no special case")
    void nullsPassThrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.hint(null)).isNull();
    }

    @Test
    @DisplayName("the hint reveals only the last four characters")
    void hintMasksAllButTheTail() {
        String hint = cipher.hint("dummy-provider-credential-ABCD");

        assertThat(hint).endsWith("ABCD");
        assertThat(hint).doesNotContain("dummy-provider");
        assertThat(hint).hasSize(12);
    }

    @Test
    @DisplayName("a short credential is masked completely rather than partly revealed")
    void shortValuesAreFullyMasked() {
        // Revealing four of six characters would give away most of a short secret.
        assertThat(cipher.hint("abc123"))
                .doesNotContain("c123")
                .matches("•+");
    }

    @Test
    @DisplayName("the development key is refused under a production profile")
    void developmentKeyIsRefusedInProduction() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        CredentialCipher productionCipher = cipherWith("dev-only-insecure-key", production);

        assertThatThrownBy(productionCipher::rejectDevelopmentKeyOutsideDevelopment)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("a real key is accepted under a production profile")
    void realKeyIsAcceptedInProduction() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        CredentialCipher productionCipher =
                cipherWith("a-genuinely-random-production-key-9f3b2c", production);

        productionCipher.rejectDevelopmentKeyOutsideDevelopment();

        assertThat(productionCipher.decrypt(productionCipher.encrypt("x"))).isEqualTo("x");
    }

    private CredentialCipher cipherWith(String key, MockEnvironment environment) {
        NotiflyProperties properties = new NotiflyProperties(
                new NotiflyProperties.Security(
                        new NotiflyProperties.Jwt("irrelevant-for-this-test", Duration.ofHours(1), "notifly"),
                        new NotiflyProperties.Encryption(key)),
                new NotiflyProperties.Bootstrap("a@b.test", "password", "Admin"));

        return new CredentialCipher(properties, environment);
    }
}
