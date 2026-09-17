package com.notifly.notification.suppression;

import com.notifly.notification.common.model.Channel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SuppressionRepository extends JpaRepository<Suppression, UUID> {

    Optional<Suppression> findByTenantIdAndChannelAndAddress(UUID tenantId, Channel channel, String address);

    Page<Suppression> findAllByTenantId(UUID tenantId, Pageable pageable);

    Optional<Suppression> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Bulk suppression lookup for a batch of recipients. A bulk send checks all of its addresses
     * in one query rather than one per recipient, which is the difference between one round trip
     * and ten thousand.
     */
    @Query("""
            SELECT s.address FROM Suppression s
            WHERE s.tenantId = :tenantId
              AND s.channel = :channel
              AND s.address IN :addresses
              AND (s.expiresAt IS NULL OR s.expiresAt > :now)
            """)
    List<String> findSuppressedAddresses(@Param("tenantId") UUID tenantId,
                                         @Param("channel") Channel channel,
                                         @Param("addresses") Collection<String> addresses,
                                         @Param("now") Instant now);
}
