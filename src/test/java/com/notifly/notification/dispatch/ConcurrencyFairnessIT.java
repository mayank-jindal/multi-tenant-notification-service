package com.notifly.notification.dispatch;

import com.notifly.notification.AbstractIntegrationTest;
import com.notifly.notification.common.model.Channel;
import com.notifly.notification.common.tenancy.TenantContext;
import com.notifly.notification.common.tenancy.TenantScope;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The concurrency requirements, demonstrated rather than asserted on paper.
 *
 * <p>Two properties the brief names explicitly, and neither is observable from a unit test:
 *
 * <ul>
 *   <li><strong>Bounded worker pools.</strong> However much work is queued, the number of
 *       notifications being sent at once never exceeds the configured pool size.</li>
 *   <li><strong>Per-tenant fairness.</strong> A tenant with a large backlog does not starve
 *       tenants with small ones — the small ones finish while the large one is still working.</li>
 * </ul>
 *
 * <p>The provider is given a deliberate latency so that work is genuinely in flight long enough
 * to observe. With a zero-latency simulator everything completes between samples and the test
 * would prove nothing.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // A small pool makes the bound easy to observe and easy to violate if it were broken.
        "notifly.dispatch.workers.EMAIL=4",
        "notifly.dispatch.simulator.EMAIL.min-latency-ms=40",
        "notifly.dispatch.simulator.EMAIL.max-latency-ms=80",
        "notifly.dispatch.poll-interval-ms=100",
        "notifly.dispatch.batch-size=20",
        "notifly.dispatch.max-in-flight-per-tenant=6",
        // A small queue makes the buffered bound observable as well as the thread bound.
        "notifly.dispatch.queue-capacity=10"
})
class ConcurrencyFairnessIT extends AbstractIntegrationTest {

    private static final int POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 10;

    /**
     * A row is stamped SENDING when it is claimed, which happens before it reaches the pool's
     * queue. "In SENDING" therefore means claimed-and-buffered, not actively being sent — the
     * two bounds are different quantities and are asserted separately below.
     */
    private static final int MAX_CLAIMED = POOL_SIZE + QUEUE_CAPACITY;
    private static final int NOISY_VOLUME = 90;
    private static final int QUIET_VOLUME = 6;

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
    private ChannelWorkerPools workerPools;

    private TenantFixture noisy;
    private TenantFixture quietOne;
    private TenantFixture quietTwo;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        noisy = createTenant("noisy-" + suffix);
        quietOne = createTenant("quiet1-" + suffix);
        quietTwo = createTenant("quiet2-" + suffix);
    }

    @Test
    @DisplayName("concurrent sends never exceed the bounded pool, and no tenant is starved")
    void poolStaysBoundedAndEveryTenantProgresses() throws Exception {
        // The noisy tenant floods the queue first, so a naive FIFO dispatcher would drain all 90
        // of its notifications before touching either quiet tenant.
        submitBulk(noisy, NOISY_VOLUME);
        submitBulk(quietOne, QUIET_VOLUME);
        submitBulk(quietTwo, QUIET_VOLUME);

        AtomicInteger peakInFlight = new AtomicInteger();
        AtomicInteger peakActiveThreads = new AtomicInteger();
        List<Integer> noisyRemainingWhenQuietFinished = new ArrayList<>();

        // Sample while the backlog drains. Both measures are recorded: rows in SENDING, which is
        // what the database believes, and active pool threads, which is what the JVM believes.
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> {
                    peakInFlight.accumulateAndGet((int) countInFlight(), Math::max);
                    peakActiveThreads.accumulateAndGet(workerPools.activeCount(Channel.EMAIL), Math::max);

                    if (noisyRemainingWhenQuietFinished.isEmpty()
                        && remaining(quietOne) == 0 && remaining(quietTwo) == 0) {
                        noisyRemainingWhenQuietFinished.add((int) remaining(noisy));
                    }

                    assertThat(remaining(noisy)).isZero();
                    assertThat(remaining(quietOne)).isZero();
                    assertThat(remaining(quietTwo)).isZero();
                });

        // ---- bounded pools
        assertThat(peakActiveThreads.get())
                .as("active worker threads must never exceed the configured pool size")
                .isLessThanOrEqualTo(POOL_SIZE);

        // Claimed work is bounded by the pool plus its queue, which is the real backpressure
        // guarantee: beyond that the dispatcher stops claiming and work stays in the database
        // rather than accumulating in memory.
        assertThat(peakInFlight.get())
                .as("claimed-and-buffered work must stay within the pool plus its bounded queue")
                .isLessThanOrEqualTo(MAX_CLAIMED);

        assertThat(peakActiveThreads.get())
                .as("the pool should actually have run work concurrently, or this proves nothing")
                .isGreaterThan(1);

        // ---- fairness
        assertThat(noisyRemainingWhenQuietFinished)
                .as("the moment both quiet tenants finished should have been observed")
                .isNotEmpty();

        assertThat(noisyRemainingWhenQuietFinished.getFirst())
                .as("""
                        the quiet tenants must finish while the noisy one still has a backlog; \
                        if this is zero the dispatcher drained the noisy tenant first and \
                        fairness is not working""")
                .isPositive();
    }

    @Test
    @DisplayName("every notification reaches a terminal state exactly once, with one attempt each")
    void noDuplicateDeliveriesUnderConcurrency() throws Exception {
        submitBulk(noisy, 40);

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(remaining(noisy)).isZero());

        List<Notification> notifications = TenantContext.getAs(TenantScope.of(noisy.tenantId()),
                () -> notificationRepository.findAll());

        assertThat(notifications).hasSize(40);
        assertThat(notifications).allSatisfy(notification -> {
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
            // Exactly one attempt: a reliable provider plus correct claiming means no row was
            // ever picked up twice. More than one here would mean two workers held the same
            // lease, which is the duplicate-delivery failure the claim is designed to prevent.
            assertThat(notification.getAttemptCount()).isEqualTo(1);
            assertThat(notification.getLeaseToken())
                    .as("a completed notification must not still hold a lease")
                    .isNull();
        });

        // Provider message ids are unique, so nothing was delivered twice.
        assertThat(notifications.stream().map(Notification::getProviderMessageId).distinct().count())
                .isEqualTo(40);
    }

    // ---------------------------------------------------------------- helpers

    private long countInFlight() {
        return TenantContext.getAs(TenantScope.root(), () ->
                notificationRepository.findAll().stream()
                        .filter(n -> n.getStatus() == NotificationStatus.SENDING)
                        .count());
    }

    private long remaining(TenantFixture tenant) {
        return TenantContext.getAs(TenantScope.of(tenant.tenantId()), () ->
                notificationRepository.findAll().stream()
                        .filter(n -> !n.getStatus().isTerminal() && n.getStatus() != NotificationStatus.SENT)
                        .count());
    }

    private void submitBulk(TenantFixture tenant, int count) throws Exception {
        String recipients = IntStream.range(0, count)
                .mapToObj(i -> """
                        {"ref":"u%d","addresses":{"EMAIL":"user%d@%s.test"}}
                        """.formatted(i, i, tenant.slug()))
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();

        mockMvc.perform(as(tenant, post("/api/v1/notifications")).content("""
                        {
                          "templateCode": "bulk",
                          "recipients": [%s],
                          "variables": {"name": "Recipient"}
                        }
                        """.formatted(recipients)))
                .andExpect(status().isAccepted());
    }

    private TenantFixture createTenant(String slug) throws Exception {
        Tenant tenant = tenantRepository.save(new Tenant(slug, slug));
        String email = "admin@" + slug + ".test";
        userRepository.save(User.tenantAdmin(
                tenant.getId(), email, passwordEncoder.encode("tenant-pass"), slug));

        TenantFixture fixture = new TenantFixture(tenant.getId(), slug, tokenFor(email, "tenant-pass"));

        mockMvc.perform(as(fixture, put("/api/v1/channels/EMAIL"))
                        .content("{\"enabled\":true,\"senderIdentity\":\"no-reply@%s.test\"}".formatted(slug)))
                .andExpect(status().isOk());

        String response = mockMvc.perform(as(fixture, post("/api/v1/templates")).content("""
                        {
                          "code": "bulk",
                          "name": "Bulk",
                          "initialVersion": {
                            "variables": [{"name":"name","required":true}],
                            "bodies": {"EMAIL": {"subject":"Hello","bodyText":"Hello {{name}}"}}
                          }
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(as(fixture, post(
                        "/api/v1/templates/" + jsonValue(response, "id") + "/versions/1/publish")))
                .andExpect(status().isOk());

        return fixture;
    }

    private MockHttpServletRequestBuilder as(TenantFixture tenant, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + tenant.token())
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

    private record TenantFixture(UUID tenantId, String slug, String token) {
    }
}
