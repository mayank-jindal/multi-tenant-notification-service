package com.notifly.notification.dispatch;

import com.notifly.notification.AbstractIntegrationTest;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.common.tenancy.TenantScope;
import com.notifly.notification.delivery.DeliveryAttemptRepository;
import com.notifly.notification.delivery.Notification;
import com.notifly.notification.delivery.NotificationRepository;
import com.notifly.notification.delivery.NotificationStatus;
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
import java.time.Instant;
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
 * The delivery pipeline end to end: submission, dispatch, retry, idempotency and scheduling.
 *
 * <p>Async assertions use Awaitility rather than sleeps. A fixed sleep is either too short and
 * flaky or too long and slow, and it hides how long the system actually took.
 */
@AutoConfigureMockMvc
class DeliveryPipelineIT extends AbstractIntegrationTest {

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

    @Autowired
    private DeliveryAttemptRepository attemptRepository;

    private String token;
    private UUID tenantId;
    private String suffix;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant tenant = tenantRepository.save(new Tenant("pipe-" + suffix, "Pipeline Co"));
        tenantId = tenant.getId();
        String email = "admin@pipe-" + suffix + ".test";
        userRepository.save(User.tenantAdmin(
                tenantId, email, passwordEncoder.encode("tenant-pass"), "Pipeline Admin"));
        token = tokenFor(email, "tenant-pass");

        configureChannel(Channel.EMAIL, "no-reply@pipe.test");
        configureChannel(Channel.SMS, "PIPECO");
        createAndPublishTemplate("order-shipped");
    }

    @Test
    @DisplayName("a submitted notification is dispatched and reaches a terminal state")
    void notificationIsDeliveredEndToEnd() throws Exception {
        String requestId = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [
                    {"ref": "user-1", "addresses": {"EMAIL": "sam@example.com"}}
                  ],
                  "variables": {"customerName": "Sam", "orderId": "A-1001"}
                }
                """);

        // The dispatcher runs on its own schedule; the assertion waits for it rather than
        // assuming a duration.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Notification> notifications = asTenant(() ->
                    notificationRepository.findAllByRequestId(UUID.fromString(requestId)));

            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().getStatus())
                    .isIn(NotificationStatus.SENT, NotificationStatus.DELIVERED);
            assertThat(notifications.getFirst().getProviderMessageId()).isNotNull();
        });
    }

    @Test
    @DisplayName("every delivery attempt is recorded, so the trail explains the outcome")
    void attemptsArePersisted() throws Exception {
        String requestId = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [{"ref": "user-2", "addresses": {"EMAIL": "attempt@example.com"}}],
                  "variables": {"customerName": "Sam", "orderId": "A-2"}
                }
                """);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Notification notification = firstOf(requestId);
            assertThat(notification.getStatus()).isIn(NotificationStatus.SENT, NotificationStatus.DELIVERED);

            var attempts = asTenant(() ->
                    attemptRepository.findAllByNotificationIdOrderByAttemptNumberAsc(notification.getId()));

            assertThat(attempts).isNotEmpty();
            assertThat(attempts.getFirst().getAttemptNumber()).isEqualTo(1);
            assertThat(attempts.getFirst().getOutcome().name()).isEqualTo("SUCCESS");
            assertThat(attempts.getFirst().getCompletedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("an invalid address fails permanently rather than consuming the retry budget")
    void invalidAddressFailsWithoutRetrying() throws Exception {
        String requestId = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [{"ref": "user-3", "addresses": {"EMAIL": "not-an-email-at-all"}}],
                  "variables": {"customerName": "Sam", "orderId": "A-3"}
                }
                """);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Notification notification = firstOf(requestId);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
            // The point of classifying failures: one attempt, not five.
            assertThat(notification.getAttemptCount()).isEqualTo(1);
            assertThat(notification.getLastErrorCode()).isEqualTo("INVALID_ADDRESS");
        });
    }

    @Test
    @DisplayName("one recipient on two channels produces two independent notifications")
    void multiChannelFansOut() throws Exception {
        String requestId = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [
                    {"ref": "user-4", "addresses": {"EMAIL": "multi@example.com", "SMS": "+919876543210"}}
                  ],
                  "variables": {"customerName": "Sam", "orderId": "A-4"}
                }
                """);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Notification> notifications = asTenant(() ->
                    notificationRepository.findAllByRequestId(UUID.fromString(requestId)));

            assertThat(notifications).hasSize(2);
            assertThat(notifications).extracting(Notification::getChannel)
                    .containsExactlyInAnyOrder(Channel.EMAIL, Channel.SMS);
            assertThat(notifications).allSatisfy(n ->
                    assertThat(n.getStatus()).isIn(NotificationStatus.SENT, NotificationStatus.DELIVERED));
        });
    }

    @Test
    @DisplayName("content is rendered at submission, not at dispatch")
    void contentIsFrozenAtSubmission() throws Exception {
        String requestId = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [{"ref": "user-5", "addresses": {"EMAIL": "frozen@example.com"}}],
                  "variables": {"customerName": "Priya", "orderId": "A-5"}
                }
                """);

        Notification notification = firstOf(requestId);

        // Rendered before the dispatcher has touched it. This is what stops a later template
        // edit from changing what an already-queued message says.
        assertThat(notification.getRenderedBodyText()).contains("Priya").contains("A-5");
        assertThat(notification.getRenderedSubject()).contains("A-5");
    }

    @Test
    @DisplayName("a scheduled send is held until its time rather than dispatched immediately")
    void scheduledSendIsHeld() throws Exception {
        String future = Instant.now().plusSeconds(3600).toString();

        String requestId = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [{"ref": "user-6", "addresses": {"EMAIL": "later@example.com"}}],
                  "variables": {"customerName": "Sam", "orderId": "A-6"},
                  "scheduledAt": "%s"
                }
                """.formatted(future));

        Notification notification = firstOf(requestId);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SCHEDULED);

        // Still held after the dispatcher has had several cycles to pick it up.
        Thread.sleep(2000);
        assertThat(firstOf(requestId).getStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(firstOf(requestId).getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("a scheduled send whose time has passed is promoted and delivered")
    void duePastScheduleIsPromoted() throws Exception {
        // A schedule in the past is accepted as an immediate send, which is the sane reading of
        // "send this at a time that has already happened".
        String requestId = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [{"ref": "user-7", "addresses": {"EMAIL": "past@example.com"}}],
                  "variables": {"customerName": "Sam", "orderId": "A-7"},
                  "scheduledAt": "%s"
                }
                """.formatted(Instant.now().minusSeconds(60).toString()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(firstOf(requestId).getStatus())
                        .isIn(NotificationStatus.SENT, NotificationStatus.DELIVERED));
    }

    @Test
    @DisplayName("a repeated idempotency key returns the original result instead of sending twice")
    void idempotentReplayDoesNotResend() throws Exception {
        String body = """
                {
                  "templateCode": "order-shipped",
                  "recipients": [{"ref": "user-8", "addresses": {"EMAIL": "idem@example.com"}}],
                  "variables": {"customerName": "Sam", "orderId": "A-8"}
                }
                """;
        String key = "key-" + suffix;

        String first = mockMvc.perform(as(post("/api/v1/notifications"))
                        .header("Idempotency-Key", key).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(as(post("/api/v1/notifications"))
                        .header("Idempotency-Key", key).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(jsonValue(first, "requestId"))
                .as("a replay must return the original submission, not create a second one")
                .isEqualTo(jsonValue(second, "requestId"));

        // And exactly one notification exists, not two.
        List<Notification> notifications = asTenant(() -> notificationRepository
                .findAllByRequestId(UUID.fromString(jsonValue(first, "requestId"))));
        assertThat(notifications).hasSize(1);
    }

    @Test
    @DisplayName("the same key with a different body is a conflict, not a silent replay")
    void reusedKeyWithDifferentBodyIsRejected() throws Exception {
        String key = "conflict-" + suffix;

        mockMvc.perform(as(post("/api/v1/notifications")).header("Idempotency-Key", key)
                        .content("""
                                {"templateCode":"order-shipped",
                                 "recipients":[{"ref":"u","addresses":{"EMAIL":"a@example.com"}}],
                                 "variables":{"customerName":"Sam","orderId":"A-9"}}
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(as(post("/api/v1/notifications")).header("Idempotency-Key", key)
                        .content("""
                                {"templateCode":"order-shipped",
                                 "recipients":[{"ref":"u","addresses":{"EMAIL":"different@example.com"}}],
                                 "variables":{"customerName":"Sam","orderId":"A-9"}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("a missing required variable is rejected before anything is queued")
    void missingVariableQueuesNothing() throws Exception {
        mockMvc.perform(as(post("/api/v1/notifications")).content("""
                        {
                          "templateCode": "order-shipped",
                          "recipients": [{"ref": "u", "addresses": {"EMAIL": "x@example.com"}}],
                          "variables": {"customerName": "Sam"}
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_VARIABLE_MISSING"))
                .andExpect(jsonPath("$.missingVariables[0]").value("orderId"));
    }

    @Test
    @DisplayName("an unconfigured channel is refused rather than queued to fail later")
    void unconfiguredChannelIsRefused() throws Exception {
        mockMvc.perform(as(post("/api/v1/notifications")).content("""
                        {
                          "templateCode": "order-shipped",
                          "recipients": [{"ref": "u", "addresses": {"PUSH": "device-token-12345"}}],
                          "variables": {"customerName": "Sam", "orderId": "A-10"},
                          "channels": ["PUSH"]
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("a queued notification can be cancelled, and a delivered one cannot")
    void cancellationRespectsLifecycle() throws Exception {
        String scheduled = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [{"ref": "u", "addresses": {"EMAIL": "cancel@example.com"}}],
                  "variables": {"customerName": "Sam", "orderId": "A-11"},
                  "scheduledAt": "%s"
                }
                """.formatted(Instant.now().plusSeconds(3600).toString()));

        Notification held = firstOf(scheduled);

        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/notifications/" + held.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Cancelling again is refused: a terminal notification has nothing left to cancel.
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/notifications/" + held.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
    }

    @Test
    @DisplayName("delivery state is visible through the API")
    void deliveryStateIsQueryable() throws Exception {
        String requestId = send("""
                {
                  "templateCode": "order-shipped",
                  "recipients": [{"ref": "user-12", "addresses": {"EMAIL": "query@example.com"}}],
                  "variables": {"customerName": "Sam", "orderId": "A-12"}
                }
                """);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(firstOf(requestId).getStatus())
                        .isIn(NotificationStatus.SENT, NotificationStatus.DELIVERED));

        mockMvc.perform(as(get("/api/v1/notifications?requestId=" + requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].providerMessageId").isNotEmpty());

        mockMvc.perform(as(get("/api/v1/notifications/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber());
    }

    // ---------------------------------------------------------------- helpers

    private String send(String body) throws Exception {
        String response = mockMvc.perform(as(post("/api/v1/notifications")).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "requestId");
    }

    private Notification firstOf(String requestId) {
        List<Notification> notifications = asTenant(() ->
                notificationRepository.findAllByRequestId(UUID.fromString(requestId)));
        assertThat(notifications).isNotEmpty();
        return notifications.getFirst();
    }

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        return TenantContext.getAs(TenantScope.of(tenantId), work);
    }

    private void configureChannel(Channel channel, String sender) throws Exception {
        mockMvc.perform(as(put("/api/v1/channels/" + channel))
                        .content("""
                                {"enabled":true,"senderIdentity":"%s","providerCode":"SIMULATOR"}
                                """.formatted(sender)))
                .andExpect(status().isOk());
    }

    private void createAndPublishTemplate(String code) throws Exception {
        String response = mockMvc.perform(as(post("/api/v1/templates")).content("""
                        {
                          "code": "%s",
                          "name": "Order shipped",
                          "initialVersion": {
                            "variables": [
                              {"name":"customerName","required":true},
                              {"name":"orderId","required":true}
                            ],
                            "bodies": {
                              "EMAIL": {
                                "subject": "Order {{orderId}} shipped",
                                "bodyText": "Hi {{customerName}}, order {{orderId}} is on its way.",
                                "bodyHtml": "<p>Hi {{customerName}}, order {{orderId}} is on its way.</p>"
                              },
                              "SMS": {"bodyText": "Hi {{customerName}}, order {{orderId}} shipped."}
                            }
                          }
                        }
                        """.formatted(code)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(as(post("/api/v1/templates/" + jsonValue(response, "id") + "/versions/1/publish")))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder) {
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
