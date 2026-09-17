package com.notifly.notification.tenant;

import com.notifly.notification.AbstractIntegrationTest;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.user.User;
import com.notifly.notification.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Platform administration endpoints and the role boundary that guards them.
 */
@AutoConfigureMockMvc
class PlatformAdminIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String platformToken;
    private String tenantToken;
    private UUID existingTenantId;
    private String suffix;

    @BeforeEach
    void authenticate() throws Exception {
        TenantContext.clear();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        platformToken = tokenFor("admin@notifly.io", "admin123");

        Tenant tenant = tenantRepository.save(new Tenant("existing-" + suffix, "Existing Corp"));
        existingTenantId = tenant.getId();
        String email = "ta-" + suffix + "@existing.test";
        userRepository.save(User.tenantAdmin(
                tenant.getId(), email, passwordEncoder.encode("tenant-pass"), "Tenant Admin"));
        tenantToken = tokenFor(email, "tenant-pass");
    }

    // ---------------------------------------------------------------- tenant lifecycle

    @Test
    @DisplayName("a platform admin can create a tenant with its first administrator")
    void createTenantWithAdmin() throws Exception {
        String body = """
                {
                  "slug": "acme-%s",
                  "name": "Acme Corporation",
                  "dispatchWeight": 3,
                  "adminEmail": "owner-%s@acme.test",
                  "adminPassword": "supersecret",
                  "adminName": "Acme Owner"
                }
                """.formatted(suffix, suffix);

        String location = mockMvc.perform(asPlatform(post("/api/v1/admin/tenants")).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.slug").value("acme-" + suffix))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.dispatchWeight").value(3))
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).isNotNull();

        // The provisioned administrator can authenticate, which is the only proof that matters.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner-%s@acme.test","password":"supersecret"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.user.tenantId").isNotEmpty());
    }

    @Test
    @DisplayName("a duplicate slug is refused with a conflict")
    void duplicateSlugIsRefused() throws Exception {
        mockMvc.perform(asPlatform(post("/api/v1/admin/tenants"))
                        .content("""
                                {"slug":"existing-%s","name":"Impostor"}
                                """.formatted(suffix)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.field").value("slug"));
    }

    @Test
    @DisplayName("an invalid slug is rejected before anything is written")
    void invalidSlugIsRejected() throws Exception {
        mockMvc.perform(asPlatform(post("/api/v1/admin/tenants"))
                        .content("""
                                {"slug":"Not A Valid Slug!","name":"Nope"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("slug"));
    }

    @Test
    @DisplayName("a partial update leaves omitted fields alone")
    void patchLeavesOmittedFieldsUnchanged() throws Exception {
        mockMvc.perform(asPlatform(patch("/api/v1/admin/tenants/" + existingTenantId))
                        .content("{\"dispatchWeight\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatchWeight").value(7))
                .andExpect(jsonPath("$.name").value("Existing Corp"));
    }

    @Test
    @DisplayName("suspending blocks the tenant's admins from authenticating, and activating restores them")
    void suspendAndActivate() throws Exception {
        String email = "cycle-" + suffix + "@existing.test";
        mockMvc.perform(asPlatform(post("/api/v1/admin/tenants/" + existingTenantId + "/users"))
                        .content("""
                                {"email":"%s","password":"cyclepass1","displayName":"Cycle"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(asPlatform(post("/api/v1/admin/tenants/" + existingTenantId + "/suspend"))
                        .content("{\"reason\":\"Payment overdue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"cyclepass1\"}".formatted(email)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));

        mockMvc.perform(asPlatform(post("/api/v1/admin/tenants/" + existingTenantId + "/activate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"cyclepass1\"}".formatted(email)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("suspending twice is idempotent")
    void suspendIsIdempotent() throws Exception {
        mockMvc.perform(asPlatform(post("/api/v1/admin/tenants/" + existingTenantId + "/suspend")))
                .andExpect(status().isOk());
        mockMvc.perform(asPlatform(post("/api/v1/admin/tenants/" + existingTenantId + "/suspend")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("an unknown tenant is a not-found problem document")
    void unknownTenantIsNotFound() throws Exception {
        mockMvc.perform(asPlatform(get("/api/v1/admin/tenants/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("a malformed id is a validation error, not a server error")
    void malformedIdIsRejected() throws Exception {
        mockMvc.perform(asPlatform(get("/api/v1/admin/tenants/not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("listing is paginated and clamps an absurd page size")
    void listingIsPaginatedAndClamped() throws Exception {
        mockMvc.perform(asPlatform(get("/api/v1/admin/tenants?page=0&size=999999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.size").value(200))
                .andExpect(jsonPath("$.page").value(0));
    }

    // ---------------------------------------------------------------- rate limits

    @Test
    @DisplayName("a rate limit whose capacity is below its refill rate is rejected")
    void unreachableRateIsRejected() throws Exception {
        mockMvc.perform(asPlatform(put("/api/v1/admin/rate-limits/global"))
                        .content("""
                                {"channel":"SMS","capacity":10,"refillTokens":100,"refillPeriodSeconds":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("can never be reached")));
    }

    @Test
    @DisplayName("setting the same limit twice is idempotent rather than creating a duplicate")
    void rateLimitUpsertIsIdempotent() throws Exception {
        String body = """
                {"channel":"PUSH","capacity":500,"refillTokens":100,"refillPeriodSeconds":1}
                """;

        String firstId = mockMvc.perform(asPlatform(put("/api/v1/admin/rate-limits/global")).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String secondId = mockMvc.perform(asPlatform(put("/api/v1/admin/rate-limits/global")).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(500))
                .andReturn().getResponse().getContentAsString();

        assertThat(idOf(firstId))
                .as("a second PUT must update the same policy, not create a second one")
                .isEqualTo(idOf(secondId));
    }

    @Test
    @DisplayName("a tenant's effective limits include the platform defaults it inherits")
    void effectiveLimitsIncludeInheritedDefaults() throws Exception {
        mockMvc.perform(asPlatform(put("/api/v1/admin/rate-limits/global"))
                        .content("{\"capacity\":1000,\"refillTokens\":200,\"refillPeriodSeconds\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(asPlatform(put("/api/v1/admin/rate-limits/tenants/" + existingTenantId))
                        .content("{\"channel\":\"EMAIL\",\"capacity\":50,\"refillTokens\":10,\"refillPeriodSeconds\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(asPlatform(get("/api/v1/admin/rate-limits/tenants/" + existingTenantId)))
                .andExpect(status().isOk())
                // Most specific first: the tenant's own EMAIL override precedes the inherited default.
                .andExpect(jsonPath("$[0].tenantId").value(existingTenantId.toString()))
                .andExpect(jsonPath("$[0].channel").value("EMAIL"));
    }

    // ---------------------------------------------------------------- the role boundary

    @Nested
    @DisplayName("a tenant admin is refused every platform administration endpoint")
    class RoleBoundary {

        @Test
        void cannotCreateTenants() throws Exception {
            mockMvc.perform(asTenant(post("/api/v1/admin/tenants"))
                            .content("{\"slug\":\"sneaky-x\",\"name\":\"Sneaky\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        void cannotListTenants() throws Exception {
            mockMvc.perform(asTenant(get("/api/v1/admin/tenants")))
                    .andExpect(status().isForbidden());
        }

        @Test
        void cannotSuspendTheirOwnTenantToEscapeLimits() throws Exception {
            mockMvc.perform(asTenant(post("/api/v1/admin/tenants/" + existingTenantId + "/suspend")))
                    .andExpect(status().isForbidden());
        }

        @Test
        void cannotProvisionUsers() throws Exception {
            mockMvc.perform(asTenant(post("/api/v1/admin/tenants/" + existingTenantId + "/users"))
                            .content("""
                                    {"email":"x@y.test","password":"password1","displayName":"X"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void cannotRaiseTheirOwnRateLimit() throws Exception {
            mockMvc.perform(asTenant(put("/api/v1/admin/rate-limits/tenants/" + existingTenantId))
                            .content("""
                                    {"channel":"EMAIL","capacity":999999,"refillTokens":999999,"refillPeriodSeconds":1}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void cannotDeleteRateLimitPolicies() throws Exception {
            mockMvc.perform(asTenant(delete("/api/v1/admin/rate-limits/" + UUID.randomUUID())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("but may read the limits that apply to their own tenant")
        void mayReadOwnEffectiveLimits() throws Exception {
            mockMvc.perform(asTenant(get("/api/v1/admin/rate-limits/tenants/" + existingTenantId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("and may not read another tenant's limits")
        void mayNotReadAnotherTenantsLimits() throws Exception {
            UUID other = tenantRepository.save(new Tenant("other-" + suffix, "Other")).getId();

            mockMvc.perform(asTenant(get("/api/v1/admin/rate-limits/tenants/" + other)))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder asPlatform(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder asTenant(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + tenantToken)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String tokenFor(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(start, body.indexOf('"', start));
    }

    private String idOf(String json) {
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }
}
