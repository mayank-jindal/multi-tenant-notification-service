package com.notifly.notification.ratelimit;

import com.notifly.notification.common.model.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RateLimitPolicyRepository extends JpaRepository<RateLimitPolicy, UUID> {

    /**
     * Every policy that could apply to this (tenant, channel) pair, including the global
     * fallbacks. The caller picks the most specific enabled one; fetching all four candidates in
     * a single query keeps policy resolution to one round trip on a hot path.
     */
    @Query("""
            SELECT p FROM RateLimitPolicy p
            WHERE p.enabled = true
              AND (p.tenantId = :tenantId OR p.tenantId IS NULL)
              AND (p.channel  = :channel  OR p.channel  IS NULL)
            """)
    List<RateLimitPolicy> findCandidates(@Param("tenantId") UUID tenantId, @Param("channel") Channel channel);

    List<RateLimitPolicy> findAllByTenantId(UUID tenantId);

    @Query("SELECT p FROM RateLimitPolicy p WHERE p.tenantId IS NULL")
    List<RateLimitPolicy> findGlobalPolicies();

    Optional<RateLimitPolicy> findByTenantIdAndChannel(UUID tenantId, Channel channel);

    @Query("SELECT p FROM RateLimitPolicy p WHERE p.tenantId IS NULL AND p.channel = :channel")
    Optional<RateLimitPolicy> findGlobalByChannel(@Param("channel") Channel channel);

    @Query("SELECT p FROM RateLimitPolicy p WHERE p.tenantId IS NULL AND p.channel IS NULL")
    Optional<RateLimitPolicy> findGlobalDefault();
}
