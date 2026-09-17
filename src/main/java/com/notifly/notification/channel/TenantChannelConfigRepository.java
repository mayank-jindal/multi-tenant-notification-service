package com.notifly.notification.channel;

import com.notifly.notification.common.model.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantChannelConfigRepository extends JpaRepository<TenantChannelConfig, UUID> {

    Optional<TenantChannelConfig> findByTenantIdAndChannel(UUID tenantId, Channel channel);

    List<TenantChannelConfig> findAllByTenantId(UUID tenantId);

    boolean existsByTenantIdAndChannelAndEnabledTrue(UUID tenantId, Channel channel);
}
