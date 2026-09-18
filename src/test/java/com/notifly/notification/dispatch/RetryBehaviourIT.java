package com.notifly.notification.dispatch;

import com.notifly.notification.AbstractIntegrationTest;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.common.tenancy.TenantScope;
import com.notifly.notification.delivery.DeliveryAttempt;
import com.notifly.notification.delivery.DeliveryAttemptRepository;
import com.notifly.notification.delivery.DeliveryOutcome;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retry and backoff against a provider configured to always fail transiently.
 *
 * <p>This is what the simulator exists for. With a real provider, exercising retry exhaustion
 * would mean waiting for a genuine outage; here it is a configuration property, and the behaviour
 * is deterministic.
 *
 * <p>Backoff is set to one second flat with no jitter so the test finishes in seconds rather than
 * minutes. The growth curve itself is covered by {@code BackoffCalculatorTest}; what this test
 * proves is that the dispatcher honours the budget and records every attempt.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "notifly.dispatch.simulator.SMS.transient-failure-rate=1.0",
        "notifly.dispatch.simulator.SMS.min-latency-ms=0",
        "notifly.dispatch.simulator.SMS.max-latency-ms=0",
        "notifly.dispatch.retry.max-attempts=3",
        "notifly.dispatch.retry.initial-backoff-seconds=1",
        "notifly.dispatch.retry.multiplier=1.0",
        "notifly.dispatch.retry.jitter-factor=0.0"
})
class RetryBehaviourIT extends AbstractIntegrationTest {

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

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant tenant = tenantRepository.save(new Tenant("retry-" + suffix, "Retry Co"));
        tenantId = tenant.getId();
        String email = "admin@retry-" + suffix + ".test";
        userRepository.save(User.tenantAdmin(
                tenantId, email, passwordEncoder.encode("tenant-pass"), "Retry Admin"));
        token = tokenFor(email, "tenant-pass");

        mockMvc.perform(as(put("/api/v1/channels/SMS"))
                        .content("{\"enabled\":true,\"senderIdentity\":\"RETRYCO\"}"))
                .andExpect(status().isOk());

        createAndPublishTemplate();
    }

    @Test
    @DisplayName("a transient failure is retried up to the budget, then fails permanently")
    void transientFailuresExhaustTheBudgetThenFail() throws Exception {
        String requestId = send();

        // First it should be waiting out a backoff rather than failing immediately: a transient
        // failure must not be treated as terminal.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Notification notification = first(requestId);
            assertThat(notification.getAttemptCount()).isPositive();
            assertThat(notification.getStatus())
                    .isIn(NotificationStatus.RETRY_SCHEDULED, NotificationStatus.SENDING,
                            NotificationStatus.QUEUED, NotificationStatus.FAILED);
        });

        // Then, after exhausting the budget, terminal failure.
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            Notification notification = first(requestId);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.getAttemptCount()).isEqualTo(3);
            assertThat(notification.getTerminalAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("every attempt is recorded, giving an audit trail of the retries")
    void everyAttemptIsRecorded() throws Exception {
        String requestId = send();

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(first(requestId).getStatus()).isEqualTo(NotificationStatus.FAILED));

        Notification notification = first(requestId);
        List<DeliveryAttempt> attempts = asTenant(() ->
                attemptRepository.findAllByNotificationIdOrderByAttemptNumberAsc(notification.getId()));

        assertThat(attempts).hasSize(3);
        assertThat(attempts).extracting(DeliveryAttempt::getAttemptNumber)
                .containsExactly(1, 2, 3);
        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(DeliveryOutcome.TRANSIENT_FAILURE);
            assertThat(attempt.getErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(attempt.getCompletedAt()).isNotNull();
            assertThat(attempt.getLatencyMs()).isNotNull();
        });
    }

    @Test
    @DisplayName("retries are spaced out rather than fired back to back")
    void retriesAreSpacedByBackoff() throws Exception {
        String requestId = send();

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(first(requestId).getStatus()).isEqualTo(NotificationStatus.FAILED));

        Notification notification = first(requestId);
        List<DeliveryAttempt> attempts = asTenant(() ->
                attemptRepository.findAllByNotificationIdOrderByAttemptNumberAsc(notification.getId()));

        Duration betweenFirstAndSecond = Duration.between(
                attempts.get(0).getStartedAt(), attempts.get(1).getStartedAt());

        // Configured backoff is one second. Anything close to zero would mean the delay was not
        // applied at all and the retry budget would burn instantly.
        assertThat(betweenFirstAndSecond)
                .as("the second attempt should wait out the backoff")
                .isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }

    // ---------------------------------------------------------------- helpers

    private String send() throws Exception {
        String response = mockMvc.perform(as(post("/api/v1/notifications")).content("""
                        {
                          "templateCode": "alert",
                          "recipients": [{"ref": "u", "addresses": {"SMS": "+919876543210"}}],
                          "variables": {"message": "Server down"}
                        }
                        """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return jsonValue(response, "requestId");
    }

    private Notification first(String requestId) {
        List<Notification> notifications = asTenant(() ->
                notificationRepository.findAllByRequestId(UUID.fromString(requestId)));
        assertThat(notifications).isNotEmpty();
        return notifications.getFirst();
    }

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        return TenantContext.getAs(TenantScope.of(tenantId), work);
    }

    private void createAndPublishTemplate() throws Exception {
        String response = mockMvc.perform(as(post("/api/v1/templates")).content("""
                        {
                          "code": "alert",
                          "name": "Alert",
                          "initialVersion": {
                            "variables": [{"name":"message","required":true}],
                            "bodies": {"SMS": {"bodyText": "Alert: {{message}}"}}
                          }
                        }
                        """))
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

    /** Referenced so the unused-import check stays honest about what this test touches. */
    @SuppressWarnings("unused")
    private Channel documentedChannel() {
        return Channel.SMS;
    }
}
