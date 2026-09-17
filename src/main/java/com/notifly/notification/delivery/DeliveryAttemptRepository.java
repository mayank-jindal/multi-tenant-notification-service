package com.notifly.notification.delivery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findAllByNotificationIdOrderByAttemptNumberAsc(UUID notificationId);

    Page<DeliveryAttempt> findAllByTenantIdOrderByStartedAtDesc(UUID tenantId, Pageable pageable);

    /**
     * The attempt a crashed worker left behind, so the reaper can close it out rather than
     * leaving an attempt that never resolves.
     */
    @Query("""
            SELECT a FROM DeliveryAttempt a
            WHERE a.notificationId = :notificationId
              AND a.outcome IS NULL
            ORDER BY a.attemptNumber DESC
            LIMIT 1
            """)
    Optional<DeliveryAttempt> findInFlightAttempt(@Param("notificationId") UUID notificationId);

    long countByNotificationId(UUID notificationId);
}
