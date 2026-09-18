package com.notifly.notification.template;

import com.notifly.notification.AbstractIntegrationTest;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.tenant.Tenant;
import com.notifly.notification.tenant.TenantRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Channel configuration and template authoring over HTTP, including the cross-tenant boundary.
 *
 * <p>The isolation assertions here matter more than the repository-level ones in
 * {@code TenantIsolationIT}: this is the path a real caller takes, with a real token, and it is
 * where a leak would actually happen.
 */
@AutoConfigureMockMvc
class TenantAdminApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String tokenA;
    private String tokenB;
    private String platformToken;
    private String suffix;

    @BeforeEach
    void setUpTenants() throws Exception {
        TenantContext.clear();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        platformToken = tokenFor("admin@notifly.io", "admin123");

        tokenA = createTenantWithAdmin("alpha-" + suffix);
        tokenB = createTenantWithAdmin("beta-" + suffix);
    }

    // ---------------------------------------------------------------- channel configuration

    @Test
    @DisplayName("every channel is listed, including ones never configured")
    void unconfiguredChannelsAreListedExplicitly() throws Exception {
        mockMvc.perform(as(tokenA, get("/api/v1/channels")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.channel=='PUSH')].configured").value(false));
    }

    @Test
    @DisplayName("stored credentials are never returned, only a masked hint")
    void credentialsAreWriteOnly() throws Exception {
        String secret = "dummy-provider-credential-ABCD";

        String response = mockMvc.perform(as(tokenA, put("/api/v1/channels/EMAIL"))
                        .content("""
                                {
                                  "enabled": true,
                                  "senderIdentity": "no-reply@alpha.test",
                                  "credentials": "%s",
                                  "providerCode": "SIMULATOR"
                                }
                                """.formatted(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialsSet").value(true))
                .andExpect(jsonPath("$.credentialsHint").value("••••••••ABCD"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .as("the plaintext credential must not appear anywhere in the response")
                .doesNotContain(secret)
                .doesNotContain("dummy-provider");

        // And it stays absent on subsequent reads, not just on the write that set it.
        String onRead = mockMvc.perform(as(tokenA, get("/api/v1/channels/EMAIL")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(onRead).doesNotContain(secret);
    }

    @Test
    @DisplayName("configuring a channel twice updates it rather than failing on the unique constraint")
    void channelConfigIsUpsert() throws Exception {
        mockMvc.perform(as(tokenA, put("/api/v1/channels/SMS"))
                        .content("{\"enabled\":true,\"senderIdentity\":\"ACME\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(tokenA, put("/api/v1/channels/SMS"))
                        .content("{\"senderIdentity\":\"ACMECORP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderIdentity").value("ACMECORP"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("enabling a channel that was never configured is a clear error")
    void enablingUnconfiguredChannelIsRejected() throws Exception {
        mockMvc.perform(as(tokenA, post("/api/v1/channels/PUSH/enable")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_CONFIGURED"));
    }

    // ---------------------------------------------------------------- templates

    @Test
    @DisplayName("a template can be created with its first draft and then published")
    void createAndPublishTemplate() throws Exception {
        String templateId = createTemplate(tokenA, "order-shipped");

        mockMvc.perform(as(tokenA, get("/api/v1/templates/" + templateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").value(1))
                .andExpect(jsonPath("$.publishedVersion").doesNotExist());

        mockMvc.perform(as(tokenA, post("/api/v1/templates/" + templateId + "/versions/1/publish")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());

        mockMvc.perform(as(tokenA, get("/api/v1/templates/" + templateId)))
                .andExpect(jsonPath("$.publishedVersion").value(1));
    }

    @Test
    @DisplayName("publishing a new version archives the previous one, leaving exactly one published")
    void publishingArchivesThePreviousVersion() throws Exception {
        String templateId = createTemplate(tokenA, "welcome");
        mockMvc.perform(as(tokenA, post("/api/v1/templates/" + templateId + "/versions/1/publish")))
                .andExpect(status().isOk());

        mockMvc.perform(as(tokenA, post("/api/v1/templates/" + templateId + "/versions"))
                        .content(versionBody("Version two {{customerName}}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(2));

        mockMvc.perform(as(tokenA, post("/api/v1/templates/" + templateId + "/versions/2/publish")))
                .andExpect(status().isOk());

        // The unique partial index guarantees this; the test is here because a failure would be
        // silent until a send resolved to the wrong content.
        mockMvc.perform(as(tokenA, get("/api/v1/templates/" + templateId + "/versions")))
                .andExpect(jsonPath("$[?(@.status=='PUBLISHED')]", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[?(@.status=='PUBLISHED')].versionNumber").value(2))
                .andExpect(jsonPath("$[?(@.versionNumber==1)].status").value("ARCHIVED"));
    }

    @Test
    @DisplayName("a published version cannot be edited")
    void publishedVersionsAreImmutable() throws Exception {
        String templateId = createTemplate(tokenA, "receipt");
        mockMvc.perform(as(tokenA, post("/api/v1/templates/" + templateId + "/versions/1/publish")))
                .andExpect(status().isOk());

        mockMvc.perform(as(tokenA, put("/api/v1/templates/" + templateId + "/versions/1"))
                        .content(versionBody("Sneaky rewrite {{customerName}}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("create a new version")));
    }

    @Test
    @DisplayName("a draft can be edited freely")
    void draftsAreEditable() throws Exception {
        String templateId = createTemplate(tokenA, "draft-edit");

        mockMvc.perform(as(tokenA, put("/api/v1/templates/" + templateId + "/versions/1"))
                        .content(versionBody("Revised copy {{customerName}}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.bodies.EMAIL.bodyText").value("Revised copy {{customerName}}"));
    }

    @Test
    @DisplayName("a duplicate template code within one tenant is refused")
    void duplicateCodeWithinTenantIsRefused() throws Exception {
        createTemplate(tokenA, "duplicate-me");

        mockMvc.perform(as(tokenA, post("/api/v1/templates"))
                        .content("""
                                {"code":"duplicate-me","name":"Again","initialVersion":%s}
                                """.formatted(versionBody("Body {{customerName}}"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("the same template code is available independently to a different tenant")
    void codesAreUniquePerTenantNotGlobally() throws Exception {
        createTemplate(tokenA, "shared-code");
        createTemplate(tokenB, "shared-code");
    }

    @Test
    @DisplayName("a non-email channel must carry plain text, since only email renders HTML")
    void nonEmailChannelsRequirePlainText() throws Exception {
        mockMvc.perform(as(tokenA, post("/api/v1/templates"))
                        .content("""
                                {
                                  "code": "html-sms-%s",
                                  "name": "Bad SMS",
                                  "initialVersion": {
                                    "variables": [],
                                    "bodies": { "SMS": { "bodyHtml": "<b>hi</b>" } }
                                  }
                                }
                                """.formatted(suffix)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("requires bodyText")));
    }

    @Test
    @DisplayName("a variable declared twice is rejected as an ambiguous contract")
    void duplicateVariableDeclarationIsRejected() throws Exception {
        mockMvc.perform(as(tokenA, post("/api/v1/templates"))
                        .content("""
                                {
                                  "code": "dupe-var-%s",
                                  "name": "Dupe",
                                  "initialVersion": {
                                    "variables": [
                                      {"name":"customerName","required":true},
                                      {"name":"customerName","required":false}
                                    ],
                                    "bodies": { "SMS": { "bodyText": "Hi {{customerName}}" } }
                                  }
                                }
                                """.formatted(suffix)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("declared more than once")));
    }

    // ---------------------------------------------------------------- the tenant boundary

    @Nested
    @DisplayName("one tenant cannot reach another tenant's data through the API")
    class CrossTenantBoundary {

        @Test
        @DisplayName("tenant B cannot read tenant A's template by its real id")
        void cannotReadAnotherTenantsTemplate() throws Exception {
            String templateOfA = createTemplate(tokenA, "private-to-a");

            mockMvc.perform(as(tokenB, get("/api/v1/templates/" + templateOfA)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("tenant B cannot publish tenant A's template")
        void cannotPublishAnotherTenantsTemplate() throws Exception {
            String templateOfA = createTemplate(tokenA, "publish-target");

            mockMvc.perform(as(tokenB, post("/api/v1/templates/" + templateOfA + "/versions/1/publish")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("tenant B cannot edit tenant A's draft")
        void cannotEditAnotherTenantsDraft() throws Exception {
            String templateOfA = createTemplate(tokenA, "edit-target");

            mockMvc.perform(as(tokenB, put("/api/v1/templates/" + templateOfA + "/versions/1"))
                            .content(versionBody("Hijacked {{customerName}}")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a tenant's template list contains only its own templates")
        void listingIsScopedToTheCallersTenant() throws Exception {
            createTemplate(tokenA, "only-a-1");
            createTemplate(tokenA, "only-a-2");
            createTemplate(tokenB, "only-b-1");

            mockMvc.perform(as(tokenB, get("/api/v1/templates?size=200")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.code=='only-a-1')]").isEmpty())
                    .andExpect(jsonPath("$.content[?(@.code=='only-a-2')]").isEmpty())
                    .andExpect(jsonPath("$.content[?(@.code=='only-b-1')]").isNotEmpty());
        }

        @Test
        @DisplayName("channel configuration is per tenant, not shared")
        void channelConfigIsNotShared() throws Exception {
            mockMvc.perform(as(tokenA, put("/api/v1/channels/EMAIL"))
                            .content("{\"enabled\":true,\"senderIdentity\":\"a@alpha.test\"}"))
                    .andExpect(status().isOk());

            mockMvc.perform(as(tokenB, get("/api/v1/channels/EMAIL")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configured").value(false))
                    .andExpect(jsonPath("$.senderIdentity").doesNotExist());
        }

        @Test
        @DisplayName("a platform admin has no tenant, so tenant-scoped endpoints refuse them")
        void platformAdminIsNotATenantAdmin() throws Exception {
            // Platform admins manage tenants; they do not author one tenant's templates. The role
            // check refuses them before tenancy is even considered.
            mockMvc.perform(as(platformToken, get("/api/v1/templates")))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------- helpers

    private String createTenantWithAdmin(String slug) throws Exception {
        Tenant tenant = tenantRepository.save(new Tenant(slug, slug));
        String email = "admin@" + slug + ".test";
        userRepository.save(User.tenantAdmin(
                tenant.getId(), email, passwordEncoder.encode("tenant-pass"), slug + " Admin"));
        return tokenFor(email, "tenant-pass");
    }

    private String createTemplate(String token, String code) throws Exception {
        String body = """
                {"code":"%s","name":"%s","initialVersion":%s}
                """.formatted(code, code, versionBody("Hello {{customerName}}"));

        String response = mockMvc.perform(as(token, post("/api/v1/templates")).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        int start = response.indexOf("\"id\":\"") + 6;
        return response.substring(start, response.indexOf('"', start));
    }

    private String versionBody(String text) {
        return """
                {
                  "variables": [{"name":"customerName","required":true,"description":"Recipient name"}],
                  "bodies": {
                    "EMAIL": {"subject":"Hello","bodyText":"%s","bodyHtml":"<p>%s</p>"},
                    "SMS": {"bodyText":"%s"}
                  }
                }
                """.formatted(text, text, text);
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token)
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
}
