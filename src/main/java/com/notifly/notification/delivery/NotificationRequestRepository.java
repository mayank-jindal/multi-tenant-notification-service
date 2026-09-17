package com.notifly.notification.delivery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, UUID> {

    Optional<NotificationRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<NotificationRequest> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    /** Submissions held for a future time, used by the schedule promoter. */
    @Query("""
            SELECT r FROM NotificationRequest r
            WHERE r.status = com.notifly.notification.delivery.NotificationRequestStatus.SCHEDULED
              AND r.scheduledAt <= :now
            ORDER BY r.scheduledAt ASC
            """)
    List<NotificationRequest> findDue(@Param("now") Instant now, Pageable pageable);
}
