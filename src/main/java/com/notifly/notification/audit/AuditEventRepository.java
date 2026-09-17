package com.notifly.notification.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findAllByTenantIdOrderByOccurredAtDesc(UUID tenantId, Pageable pageable);

    /** The full history of one entity, oldest first — how a notification reached its state. */
    List<AuditEvent> findAllByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, UUID entityId);

    @Query("""
            SELECT a FROM AuditEvent a
            WHERE (:tenantId IS NULL OR a.tenantId = :tenantId)
              AND (:eventType IS NULL OR a.eventType = :eventType)
              AND a.occurredAt BETWEEN :from AND :to
            ORDER BY a.occurredAt DESC
            """)
    Page<AuditEvent> search(@Param("tenantId") UUID tenantId,
                            @Param("eventType") String eventType,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);
}
