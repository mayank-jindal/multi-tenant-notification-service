package com.notifly.notification;

import com.notifly.notification.common.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests, backed by a real PostgreSQL instance.
 *
 * <p>A real database rather than H2 because the behaviour being tested is frequently
 * Postgres-specific — {@code FOR UPDATE SKIP LOCKED}, partial indexes, {@code jsonb} columns and
 * the tenant discriminator. An in-memory substitute would pass while the production database
 * failed, which is worse than no test.
 *
 * <p>The container is {@code static} so one instance is shared by every test class in the run;
 * starting Postgres per class would dominate the suite's runtime. Flyway migrates it once on
 * first use.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notifications")
            .withUsername("notify")
            .withPassword("notify")
            .withReuse(true);

    static {
        POSTGRES.start();
    }

    /**
     * Tests run on the JUnit thread, which is reused across tests. A scope left behind by one
     * test would silently change what the next one can see, so it is always cleared.
     */
    @AfterEach
    void clearTenantScope() {
        TenantContext.clear();
    }
}
