package com.notifly.notification.channel;

import com.notifly.notification.audit.AuditEventType;
import com.notifly.notification.audit.AuditService;
import com.notifly.notification.channel.dto.ChannelConfigResponse;
import com.notifly.notification.channel.dto.UpdateChannelConfigRequest;
import com.notifly.notification.common.crypto.CredentialCipher;
import com.notifly.notification.common.error.ErrorCode;
import com.notifly.notification.common.error.Errors;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A tenant's per-channel configuration.
 *
 * <p>Scoping comes from the tenant discriminator rather than from a tenant id in the URL: these
 * endpoints operate on "my tenant", taken from the caller's token. There is no path by which a
 * tenant admin can name another tenant here, so there is nothing to authorize beyond the role.
 */
@Service
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class ChannelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfigService.class);

    private final TenantChannelConfigRepository configRepository;
    private final CredentialCipher credentialCipher;
    private final AuditService auditService;

    public ChannelConfigService(TenantChannelConfigRepository configRepository,
                                CredentialCipher credentialCipher,
                                AuditService auditService) {
        this.configRepository = configRepository;
        this.credentialCipher = credentialCipher;
        this.auditService = auditService;
    }

    /**
     * Every channel, including ones never configured.
     *
     * <p>Unconfigured channels appear as explicit placeholders rather than being omitted. A
     * tenant admin asking "what can I send on" needs to see that PUSH exists and is not set up —
     * an absent entry looks the same as a channel that does not exist.
     */
    @Transactional(readOnly = true)
    public List<ChannelConfigResponse> listAll() {
        UUID tenantId = TenantContext.requireTenantId();
        Map<Channel, TenantChannelConfig> configured = configRepository.findAllByTenantId(tenantId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(TenantChannelConfig::getChannel, c -> c));

        return Arrays.stream(Channel.values())
                .map(channel -> configured.containsKey(channel)
                        ? ChannelConfigResponse.from(configured.get(channel))
                        : ChannelConfigResponse.unconfigured(channel))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChannelConfigResponse get(Channel channel) {
        UUID tenantId = TenantContext.requireTenantId();
        return configRepository.findByTenantIdAndChannel(tenantId, channel)
                .map(ChannelConfigResponse::from)
                .orElseGet(() -> ChannelConfigResponse.unconfigured(channel));
    }

    /**
     * Creates or updates one channel's configuration.
     *
     * <p>Upsert rather than separate create and update: a tenant admin thinks in terms of "set up
     * email", not "does a row already exist for email", and the unique constraint on
     * (tenant, channel) means there is only ever one answer.
     */
    @Transactional
    public ChannelConfigResponse upsert(Channel channel, UpdateChannelConfigRequest request) {
        UUID tenantId = TenantContext.requireTenantId();

        TenantChannelConfig config = configRepository.findByTenantIdAndChannel(tenantId, channel)
                .orElseGet(() -> new TenantChannelConfig(channel));

        Map<String, Object> changes = new HashMap<>();
        boolean creating = config.getId() == null;

        if (request.enabled() != null && request.enabled() != config.isEnabled()) {
            changes.put("enabled", Map.of("from", config.isEnabled(), "to", request.enabled()));
            config.setEnabled(request.enabled());
        }
        if (request.senderIdentity() != null) {
            changes.put("senderIdentity", Map.of(
                    "from", String.valueOf(config.getSenderIdentity()), "to", request.senderIdentity()));
            config.setSenderIdentity(request.senderIdentity().trim());
        }
        if (request.providerCode() != null) {
            changes.put("providerCode", Map.of(
                    "from", String.valueOf(config.getProviderCode()), "to", request.providerCode()));
            config.setProviderCode(request.providerCode().trim());
        }
        if (request.maxInFlight() != null) {
            changes.put("maxInFlight", Map.of(
                    "from", String.valueOf(config.getMaxInFlight()), "to", request.maxInFlight()));
            config.setMaxInFlight(request.maxInFlight());
        }
        if (request.credentials() != null && !request.credentials().isBlank()) {
            config.setCredentialsCipher(credentialCipher.encrypt(request.credentials().trim()));
            config.setCredentialsHint(credentialCipher.hint(request.credentials()));
            // The audit trail records only that a credential was replaced. Writing the value, or
            // even its length, into an append-only table that many people can read would defeat
            // the point of encrypting it in the first place.
            changes.put("credentials", "replaced");
        }

        TenantChannelConfig saved = configRepository.save(config);

        auditService.record(
                creating ? AuditEventType.CHANNEL_CONFIG_UPDATED : AuditEventType.CHANNEL_CONFIG_UPDATED,
                "TenantChannelConfig", saved.getId(),
                Map.of("channel", channel.name(), "created", creating, "changes", changes));

        log.info("Tenant {} configured channel {} (provider {})", tenantId, channel, saved.getProviderCode());
        return ChannelConfigResponse.from(saved);
    }

    @Transactional
    public ChannelConfigResponse setEnabled(Channel channel, boolean enabled) {
        UUID tenantId = TenantContext.requireTenantId();
        TenantChannelConfig config = configRepository.findByTenantIdAndChannel(tenantId, channel)
                .orElseThrow(() -> Errors.badRequest(ErrorCode.CHANNEL_NOT_CONFIGURED,
                        "Channel " + channel + " has not been configured for this tenant"));

        config.setEnabled(enabled);
        TenantChannelConfig saved = configRepository.save(config);

        auditService.record(enabled ? AuditEventType.CHANNEL_ENABLED : AuditEventType.CHANNEL_DISABLED,
                "TenantChannelConfig", saved.getId(), Map.of("channel", channel.name()));
        return ChannelConfigResponse.from(saved);
    }

    // ---------------------------------------------------------------- internal use

    /**
     * The configuration a send must have, rejecting anything unusable.
     *
     * <p>Called from the send path on behalf of whoever is sending, so it carries no role
     * requirement of its own — the caller has already been authorized.
     */
    @PreAuthorize("permitAll()")
    @Transactional(readOnly = true)
    public TenantChannelConfig requireUsable(UUID tenantId, Channel channel) {
        TenantChannelConfig config = configRepository.findByTenantIdAndChannel(tenantId, channel)
                .orElseThrow(() -> Errors.badRequest(ErrorCode.CHANNEL_NOT_CONFIGURED,
                        "Channel " + channel + " is not configured for this tenant")
                        .with("channel", channel.name()));

        if (!config.isEnabled()) {
            throw Errors.badRequest(ErrorCode.CHANNEL_DISABLED,
                    "Channel " + channel + " is disabled for this tenant")
                    .with("channel", channel.name());
        }
        return config;
    }

    /**
     * Decrypts a stored credential for a provider call.
     *
     * <p>The only path that produces plaintext, and it is reachable only from the dispatch
     * pipeline — never from a controller.
     */
    @PreAuthorize("permitAll()")
    public String decryptCredentials(TenantChannelConfig config) {
        return config.hasCredentials() ? credentialCipher.decrypt(config.getCredentialsCipher()) : null;
    }
}
