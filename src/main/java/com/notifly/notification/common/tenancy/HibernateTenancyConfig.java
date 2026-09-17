package com.notifly.notification.common.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Registers the tenant resolver with Hibernate.
 *
 * <p>Spring Boot does not wire a {@link org.hibernate.context.spi.CurrentTenantIdentifierResolver}
 * bean into the session factory on its own, so it is handed over explicitly here. Without this,
 * the {@code @TenantId} columns would still be mapped but nothing would ever filter on them —
 * the same silent failure mode as Flyway not being autoconfigured.
 */
@Configuration
public class HibernateTenancyConfig implements HibernatePropertiesCustomizer {

    private final TenantIdentifierResolver tenantIdentifierResolver;

    public HibernateTenancyConfig(TenantIdentifierResolver tenantIdentifierResolver) {
        this.tenantIdentifierResolver = tenantIdentifierResolver;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
    }
}
