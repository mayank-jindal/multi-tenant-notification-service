package com.notifly.notification.common.tenancy;

import com.notifly.notification.AbstractIntegrationTest;
import com.notifly.notification.template.Template;
import com.notifly.notification.template.TemplateRepository;
import com.notifly.notification.tenant.Tenant;
import com.notifly.notification.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the tenant discriminator actually isolates data.
 *
 * <p>{@link #findByIdDoesNotLeakAcrossTenants()} is the reason {@code @TenantId} was chosen over a
 * Hibernate {@code @Filter}: a filter is silently ignored by {@code find()} by id, so that one
 * test would fail under a filter-based design while every other test here still passed.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. Hibernate binds the tenant
 * identifier when a session is opened, not per statement, so a single test-wide transaction would
 * pin every operation to whichever scope happened to be in force at the start — which is both
 * wrong and a good way to write a test that proves nothing. Each repository call here opens its
 * own session, exactly as a request does in production.
 */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TemplateRepository templateRepository;

    private UUID tenantA;
    private UUID tenantB;
    private UUID templateOfA;
    private UUID templateOfB;

    @BeforeEach
    void seed() {
        // Tenants are not tenant-owned, so they are created outside any scope.
        TenantContext.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = tenantRepository.save(new Tenant("acme-" + suffix, "Acme")).getId();
        tenantB = tenantRepository.save(new Tenant("globex-" + suffix, "Globex")).getId();

        // The same template code in both tenants: if isolation is broken, these collide.
        templateOfA = TenantContext.getAs(TenantScope.of(tenantA),
                () -> templateRepository.save(new Template("welcome", "Welcome")).getId());
        templateOfB = TenantContext.getAs(TenantScope.of(tenantB),
                () -> templateRepository.save(new Template("welcome", "Welcome")).getId());
    }

    @AfterEach
    void cleanUp() {
        TenantContext.runAs(TenantScope.root(), () -> {
            templateRepository.deleteAllById(List.of(templateOfA, templateOfB));
            tenantRepository.deleteAllById(List.of(tenantA, tenantB));
        });
        TenantContext.clear();
    }

    @Test
    @DisplayName("tenant_id is assigned from the scope in force, never by the caller")
    void tenantIdIsAssignedFromScope() {
        Optional<Template> found = TenantContext.getAs(TenantScope.of(tenantA),
                () -> templateRepository.findById(templateOfA));

        assertThat(found).isPresent();
        assertThat(found.get().getTenantId())
                .as("the entity was saved without anyone setting tenantId explicitly")
                .isEqualTo(tenantA);
    }

    @Test
    @DisplayName("the same template code exists independently in two tenants")
    void listingIsScopedToTheCurrentTenant() {
        List<Template> asA = TenantContext.getAs(TenantScope.of(tenantA), () -> templateRepository.findAll());
        List<Template> asB = TenantContext.getAs(TenantScope.of(tenantB), () -> templateRepository.findAll());

        assertThat(asA).extracting(Template::getId).containsExactly(templateOfA);
        assertThat(asB).extracting(Template::getId).containsExactly(templateOfB);
    }

    @Test
    @DisplayName("findById with another tenant's real id returns nothing")
    void findByIdDoesNotLeakAcrossTenants() {
        // The id is real and correct. The only thing denying access is the tenant scope.
        Optional<Template> leaked = TenantContext.getAs(TenantScope.of(tenantB),
                () -> templateRepository.findById(templateOfA));

        assertThat(leaked)
                .as("tenant B must not read tenant A's template by id")
                .isEmpty();
    }

    @Test
    @DisplayName("an unscoped thread sees no tenant data at all")
    void unscopedSeesNothing() {
        TenantContext.set(TenantScope.UNSCOPED);

        assertThat(templateRepository.findById(templateOfA)).isEmpty();
        assertThat(templateRepository.findAll())
                .as("the default scope must fail closed, not open")
                .isEmpty();
    }

    @Test
    @DisplayName("root scope sees every tenant, which is how platform admins read across tenants")
    void rootScopeSeesAllTenants() {
        List<Template> all = TenantContext.getAs(TenantScope.root(), () -> templateRepository.findAll());

        assertThat(all).extracting(Template::getId).contains(templateOfA, templateOfB);
    }

    @Test
    @DisplayName("root scope can read any tenant's row by id")
    void rootScopeCanReadAnyRowById() {
        Optional<Template> found = TenantContext.getAs(TenantScope.root(),
                () -> templateRepository.findById(templateOfA));

        assertThat(found).isPresent();
        assertThat(found.get().getTenantId()).isEqualTo(tenantA);
    }

    @Test
    @DisplayName("explicitly scoped finders agree with the discriminator")
    void explicitlyScopedFindersAgree() {
        Optional<Template> wrongTenant = TenantContext.getAs(TenantScope.root(),
                () -> templateRepository.findByIdAndTenantId(templateOfA, tenantB));
        Optional<Template> rightTenant = TenantContext.getAs(TenantScope.root(),
                () -> templateRepository.findByIdAndTenantId(templateOfA, tenantA));

        assertThat(wrongTenant).isEmpty();
        assertThat(rightTenant).isPresent();
    }

    @Test
    @DisplayName("a nested scope restores the outer one when it completes")
    void scopeIsRestoredAfterNestedCall() {
        TenantContext.set(TenantScope.of(tenantA));

        TenantContext.runAs(TenantScope.of(tenantB), () ->
                assertThat(TenantContext.currentTenantId()).isEqualTo(tenantB));

        assertThat(TenantContext.currentTenantId())
                .as("the outer scope must survive an inner one")
                .isEqualTo(tenantA);
    }

    @Test
    @DisplayName("the scope is cleared even when the scoped work throws")
    void scopeIsRestoredWhenWorkThrows() {
        TenantContext.set(TenantScope.of(tenantA));

        try {
            TenantContext.runAs(TenantScope.of(tenantB), () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // The point of the test is what the scope looks like afterwards.
        }

        assertThat(TenantContext.currentTenantId()).isEqualTo(tenantA);
    }
}
