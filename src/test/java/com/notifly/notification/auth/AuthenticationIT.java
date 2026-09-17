package com.notifly.notification.auth;

import com.notifly.notification.AbstractIntegrationTest;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.common.tenancy.TenantScope;
import com.notifly.notification.tenant.Tenant;
import com.notifly.notification.tenant.TenantRepository;
import com.notifly.notification.tenant.TenantStatus;
import com.notifly.notification.user.User;
import com.notifly.notification.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication, token handling and the default-deny authorization posture, exercised over HTTP.
 */
@AutoConfigureMockMvc
class AuthenticationIT extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@notifly.io";
    private static final String ADMIN_PASSWORD = "admin123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String tenantAdminEmail;
    private String suspendedTenantAdminEmail;

    @BeforeEach
    void seedTenantUsers() {
        TenantContext.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant active = tenantRepository.save(new Tenant("acme-" + suffix, "Acme"));
        tenantAdminEmail = "admin@acme-" + suffix + ".test";
        userRepository.save(User.tenantAdmin(
                active.getId(), tenantAdminEmail, passwordEncoder.encode("tenant-pass"), "Acme Admin"));

        Tenant suspended = new Tenant("halted-" + suffix, "Halted Corp");
        suspended.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(suspended);
        suspendedTenantAdminEmail = "admin@halted-" + suffix + ".test";
        userRepository.save(User.tenantAdmin(
                suspended.getId(), suspendedTenantAdminEmail,
                passwordEncoder.encode("tenant-pass"), "Halted Admin"));
    }

    // ---------------------------------------------------------------- login

    @Test
    @DisplayName("the bootstrap platform admin can log in and has no tenant")
    void bootstrapAdminCanLogIn() throws Exception {
        mockMvc.perform(login(ADMIN_EMAIL, ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.role").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.user.tenantId").doesNotExist());
    }

    @Test
    @DisplayName("a tenant admin's token carries their tenant")
    void tenantAdminLoginCarriesTenant() throws Exception {
        mockMvc.perform(login(tenantAdminEmail, "tenant-pass"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.user.tenantId").isNotEmpty());
    }

    @Test
    @DisplayName("an unknown email and a wrong password are indistinguishable")
    void failuresDoNotRevealWhetherAnAccountExists() throws Exception {
        MvcResult unknownUser = mockMvc.perform(login("nobody@nowhere.test", "whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        MvcResult wrongPassword = mockMvc.perform(login(ADMIN_EMAIL, "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        // Identical bodies apart from the timestamp: nothing distinguishes the two cases, so the
        // endpoint cannot be used to discover which addresses are registered.
        assertThat(withoutTimestamp(unknownUser.getResponse().getContentAsString()))
                .isEqualTo(withoutTimestamp(wrongPassword.getResponse().getContentAsString()));
    }

    @Test
    @DisplayName("a suspended tenant's admin cannot obtain a token")
    void suspendedTenantAdminIsRefused() throws Exception {
        mockMvc.perform(login(suspendedTenantAdminEmail, "tenant-pass"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));
    }

    @Test
    @DisplayName("an invalid login body reports every violation as a problem document")
    void invalidLoginBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray())
                .andExpect(jsonPath("$.violations.length()").value(2));
    }

    @Test
    @DisplayName("a malformed JSON body does not leak parser internals")
    void malformedJsonIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---------------------------------------------------------------- tokens

    @Test
    @DisplayName("a valid token identifies the caller")
    void meReturnsTheAuthenticatedUser() throws Exception {
        String token = tokenFor(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.role").value("PLATFORM_ADMIN"));
    }

    @Test
    @DisplayName("a tampered token is rejected as invalid, not merely unauthenticated")
    void tamperedTokenIsRejected() throws Exception {
        String token = tokenFor(ADMIN_EMAIL, ADMIN_PASSWORD);
        // Flip the final character of the signature; the payload still parses, the signature fails.
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("a non-token bearer value is rejected")
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer complete-nonsense"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    // ---------------------------------------------------------------- authorization posture

    @Test
    @DisplayName("a request with no credentials is refused with a problem document")
    void missingCredentialsAreRefused() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("unknown paths are protected too, proving the chain defaults to deny")
    void unknownPathsRequireAuthentication() throws Exception {
        // 401 rather than 404: the endpoint does not exist, and the caller is still not told so.
        // This is what proves anyRequest().authenticated() rather than a list of protected paths.
        mockMvc.perform(get("/api/v1/some/endpoint/added/tomorrow"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the login endpoint and API docs are reachable without a token")
    void publicEndpointsAreReachable() throws Exception {
        mockMvc.perform(login(ADMIN_EMAIL, ADMIN_PASSWORD)).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String email, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private String tokenFor(String email, String password) throws Exception {
        String body = mockMvc.perform(login(email, password))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(start, body.indexOf('"', start));
    }

    private String withoutTimestamp(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"<any>\"");
    }

    /** Exposes the scoped seeding helper for readability in future tests. */
    @SuppressWarnings("unused")
    private void asTenant(UUID tenantId, Runnable work) {
        TenantContext.runAs(TenantScope.of(tenantId), work);
    }
}
