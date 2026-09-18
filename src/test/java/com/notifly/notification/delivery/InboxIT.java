package com.notifly.notification.delivery;

import com.notifly.notification.AbstractIntegrationTest;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.common.tenancy.TenantScope;
import com.notifly.notification.tenant.Tenant;
import com.notifly.notification.tenant.TenantRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The in-app channel, end to end: sent through the same pipeline as every other channel, then
 * read back through the inbox.
 *
 * <p>This is the one channel with no third party in it, so unlike email or SMS the delivery here
 * is genuine rather than simulated — the message really is stored and really is readable.
 */
@AutoConfigureMockMvc
class InboxIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private NotificationRepository notificationRepository;

    private String tokenA;
    private String tokenB;
    private UUID tenantA;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant a = tenantRepository.save(new Tenant("inbox-a-" + suffix, "Inbox A"));
        tenantA = a.getId();
        userRepository.save(User.tenantAdmin(tenantA, "a@inbox-" + suffix + ".test",
                passwordEncoder.encode("pass-1234"), "A"));
        tokenA = tokenFor("a@inbox-" + suffix + ".test", "pass-1234");

        Tenant b = tenantRepository.save(new Tenant("inbox-b-" + suffix, "Inbox B"));
        userRepository.save(User.tenantAdmin(b.getId(), "b@inbox-" + suffix + ".test",
                passwordEncoder.encode("pass-1234"), "B"));
        tokenB = tokenFor("b@inbox-" + suffix + ".test", "pass-1234");

        configureInApp(tokenA);
        configureInApp(tokenB);
        createTemplate(tokenA);
        createTemplate(tokenB);
    }

    @Test
    @DisplayName("an in-app message is delivered and appears in the recipient's inbox")
    void messageIsDeliveredAndReadable() throws Exception {
        sendTo(tokenA, "cust-7", "Your order shipped");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(as(tokenA, get("/api/v1/inbox?recipientRef=cust-7")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content.length()").value(1))
                        .andExpect(jsonPath("$.content[0].body").value("Your order shipped"))
                        .andExpect(jsonPath("$.content[0].read").value(false)));
    }

    @Test
    @DisplayName("in-app delivery is confirmed, not merely sent")
    void inAppReachesDelivered() throws Exception {
        sendTo(tokenA, "cust-8", "Confirmed");

        // Every other channel stops at SENT, because confirmation would need a provider webhook.
        // In-app has no provider: writing the row is the delivery, so it can honestly say so.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Notification> notifications = TenantContext.getAs(TenantScope.of(tenantA),
                    () -> notificationRepository.findAll().stream()
                            .filter(n -> "cust-8".equals(n.getRecipientRef()))
                            .toList());

            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().getStatus()).isEqualTo(NotificationStatus.DELIVERED);
            assertThat(notifications.getFirst().getDeliveredAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("unread count tracks reading, and marking read is idempotent")
    void unreadCountAndMarkRead() throws Exception {
        sendTo(tokenA, "cust-9", "First");
        sendTo(tokenA, "cust-9", "Second");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(as(tokenA, get("/api/v1/inbox/unread-count?recipientRef=cust-9")))
                        .andExpect(jsonPath("$.unread").value(2)));

        String body = mockMvc.perform(as(tokenA, get("/api/v1/inbox?recipientRef=cust-9")))
                .andReturn().getResponse().getContentAsString();
        String firstId = jsonValue(body, "id");

        String read = mockMvc.perform(as(tokenA, post("/api/v1/inbox/" + firstId + "/read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andReturn().getResponse().getContentAsString();
        String firstReadAt = jsonValue(read, "readAt");

        mockMvc.perform(as(tokenA, get("/api/v1/inbox/unread-count?recipientRef=cust-9")))
                .andExpect(jsonPath("$.unread").value(1));

        // Reading twice must not rewrite when it was first read.
        String again = mockMvc.perform(as(tokenA, post("/api/v1/inbox/" + firstId + "/read")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(jsonValue(again, "readAt"))
                .as("a repeated read must not overwrite the original timestamp")
                .isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("mark-all-read clears the whole inbox")
    void markAllRead() throws Exception {
        sendTo(tokenA, "cust-10", "One");
        sendTo(tokenA, "cust-10", "Two");
        sendTo(tokenA, "cust-10", "Three");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(as(tokenA, get("/api/v1/inbox/unread-count?recipientRef=cust-10")))
                        .andExpect(jsonPath("$.unread").value(3)));

        mockMvc.perform(as(tokenA, post("/api/v1/inbox/read-all?recipientRef=cust-10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));

        mockMvc.perform(as(tokenA, get("/api/v1/inbox/unread-count?recipientRef=cust-10")))
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("one tenant cannot read another tenant's inbox, even with the same recipient reference")
    void inboxIsTenantScoped() throws Exception {
        // Both tenants use the reference "shared-customer". They are different people, and the
        // tenant scope is the only thing keeping them apart.
        sendTo(tokenA, "shared-customer", "Message for tenant A");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(as(tokenA, get("/api/v1/inbox?recipientRef=shared-customer")))
                        .andExpect(jsonPath("$.content.length()").value(1)));

        mockMvc.perform(as(tokenB, get("/api/v1/inbox?recipientRef=shared-customer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(as(tokenB, get("/api/v1/inbox/unread-count?recipientRef=shared-customer")))
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("tenant B cannot mark tenant A's message read by its real id")
    void cannotMarkAnotherTenantsMessageRead() throws Exception {
        sendTo(tokenA, "cust-11", "Private");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(as(tokenA, get("/api/v1/inbox?recipientRef=cust-11")))
                        .andExpect(jsonPath("$.content.length()").value(1)));

        String body = mockMvc.perform(as(tokenA, get("/api/v1/inbox?recipientRef=cust-11")))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(as(tokenB, post("/api/v1/inbox/" + jsonValue(body, "id") + "/read")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a non-inbox channel cannot be marked read")
    void onlyInAppCanBeMarkedRead() throws Exception {
        mockMvc.perform(as(tokenA, put("/api/v1/channels/EMAIL"))
                        .content("{\"enabled\":true,\"senderIdentity\":\"a@test.test\"}"))
                .andExpect(status().isOk());

        String requestId = jsonValue(mockMvc.perform(as(tokenA, post("/api/v1/notifications"))
                        .content("""
                                {"templateCode":"inbox-note",
                                 "recipients":[{"ref":"cust-12","addresses":{"EMAIL":"x@example.com"}}],
                                 "variables":{"text":"email not inbox"},
                                 "channels":["EMAIL"]}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "requestId");

        UUID emailId = TenantContext.getAs(TenantScope.of(tenantA), () ->
                notificationRepository.findAllByRequestId(UUID.fromString(requestId)).getFirst().getId());

        mockMvc.perform(as(tokenA, post("/api/v1/inbox/" + emailId + "/read")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.channel").value("EMAIL"));
    }

    @Test
    @DisplayName("a missing recipient reference is rejected rather than returning everyone's mail")
    void recipientReferenceIsRequired() throws Exception {
        mockMvc.perform(as(tokenA, get("/api/v1/inbox?recipientRef=")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------------------------------------------------------------- helpers

    private void sendTo(String token, String recipientRef, String text) throws Exception {
        mockMvc.perform(as(token, post("/api/v1/notifications")).content("""
                        {
                          "templateCode": "inbox-note",
                          "recipients": [{"ref": "%s", "addresses": {"IN_APP": "%s"}}],
                          "variables": {"text": "%s"},
                          "channels": ["IN_APP"]
                        }
                        """.formatted(recipientRef, recipientRef, text)))
                .andExpect(status().isAccepted());
    }

    private void configureInApp(String token) throws Exception {
        mockMvc.perform(as(token, put("/api/v1/channels/IN_APP"))
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    private void createTemplate(String token) throws Exception {
        String response = mockMvc.perform(as(token, post("/api/v1/templates")).content("""
                        {
                          "code": "inbox-note",
                          "name": "Inbox note",
                          "initialVersion": {
                            "variables": [{"name":"text","required":true}],
                            "bodies": {
                              "IN_APP": {"subject": "Notification", "bodyText": "{{text}}"},
                              "EMAIL": {"subject": "Notification", "bodyText": "{{text}}"}
                            }
                          }
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(as(token, post(
                        "/api/v1/templates/" + jsonValue(response, "id") + "/versions/1/publish")))
                .andExpect(status().isOk());
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
        return jsonValue(body, "accessToken");
    }

    private String jsonValue(String json, String field) {
        int start = json.indexOf("\"" + field + "\":\"") + field.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }
}
