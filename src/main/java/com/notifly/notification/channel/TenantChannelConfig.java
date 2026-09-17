package com.notifly.notification.channel;

import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.model.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A tenant's configuration for one channel.
 *
 * <p>A channel with no configuration row is treated as not configured, and submissions to it are
 * rejected at validation time. That is intentional: silently accepting a send for a channel the
 * tenant never set up would produce a notification that can only ever fail.
 */
@Entity
@Table(name = "tenant_channel_configs")
public class TenantChannelConfig extends TenantOwnedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private Channel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "sender_identity")
    private String senderIdentity;

    /**
     * AES-GCM ciphertext of the provider credential document. Encrypted in the service layer on
     * the way in and decrypted only when a provider actually needs it, so the plaintext is never
     * held in an entity field and cannot leak through a DTO mapping mistake.
     */
    @Column(name = "credentials_cipher", columnDefinition = "text")
    private String credentialsCipher;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode = "SIMULATOR";

    /** Optional per-channel override of how many of this tenant's sends may be in flight. */
    @Column(name = "max_in_flight")
    private Integer maxInFlight;

    protected TenantChannelConfig() {
        // for JPA
    }

    public TenantChannelConfig(Channel channel) {
        this.channel = channel;
    }

    public boolean isUsable() {
        return enabled;
    }

    public Channel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSenderIdentity() {
        return senderIdentity;
    }

    public void setSenderIdentity(String senderIdentity) {
        this.senderIdentity = senderIdentity;
    }

    public String getCredentialsCipher() {
        return credentialsCipher;
    }

    public void setCredentialsCipher(String credentialsCipher) {
        this.credentialsCipher = credentialsCipher;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }

    public Integer getMaxInFlight() {
        return maxInFlight;
    }

    public void setMaxInFlight(Integer maxInFlight) {
        this.maxInFlight = maxInFlight;
    }
}
