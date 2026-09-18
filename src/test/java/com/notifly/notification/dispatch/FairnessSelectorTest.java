package com.notifly.notification.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the fairness allocation.
 *
 * <p>This algorithm is the answer to "per-tenant fairness under load", so it is tested directly
 * rather than only observed through the dispatcher. Starvation is much easier to assert here than
 * to detect in an integration test.
 */
class FairnessSelectorTest {

    private final FairnessSelector selector = new FairnessSelector();

    private final UUID noisy = UUID.randomUUID();
    private final UUID quiet = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();

    @Test
    @DisplayName("a tenant with a huge backlog cannot take the whole cycle")
    void oneTenantCannotMonopoliseCapacity() {
        // The scenario the requirement exists for: one tenant enqueues a million notifications
        // while another sends a single password reset.
        List<TenantWorkload> workloads = List.of(
                new TenantWorkload(noisy, 1, 1_000_000),
                new TenantWorkload(quiet, 1, 1));

        Map<UUID, Integer> allocation = selector.allocate(workloads, 10, 100, 0);

        assertThat(allocation.get(quiet))
                .as("the quiet tenant must make progress in the same cycle")
                .isEqualTo(1);
        assertThat(allocation.get(noisy)).isLessThan(10);
    }

    @Test
    @DisplayName("every tenant with work gets at least one slot")
    void everyTenantGetsASlot() {
        List<TenantWorkload> workloads = List.of(
                new TenantWorkload(noisy, 1, 500),
                new TenantWorkload(quiet, 1, 500),
                new TenantWorkload(third, 1, 500));

        Map<UUID, Integer> allocation = selector.allocate(workloads, 3, 100, 0);

        assertThat(allocation).hasSize(3);
        assertThat(allocation.values()).allMatch(slots -> slots >= 1);
    }

    @Test
    @DisplayName("weights shape the share of what remains")
    void weightsAreHonoured() {
        List<TenantWorkload> workloads = List.of(
                new TenantWorkload(noisy, 9, 1000),
                new TenantWorkload(quiet, 1, 1000));

        Map<UUID, Integer> allocation = selector.allocate(workloads, 100, 1000, 0);

        assertThat(allocation.get(noisy))
                .as("weight 9 against weight 1 should win clearly")
                .isGreaterThan(allocation.get(quiet));
        assertThat(allocation.get(quiet)).isPositive();
    }

    @Test
    @DisplayName("no tenant is allocated more than it can use")
    void allocationNeverExceedsPendingWork() {
        List<TenantWorkload> workloads = List.of(
                new TenantWorkload(noisy, 1, 2),
                new TenantWorkload(quiet, 1, 3));

        Map<UUID, Integer> allocation = selector.allocate(workloads, 100, 100, 0);

        assertThat(allocation.get(noisy)).isLessThanOrEqualTo(2);
        assertThat(allocation.get(quiet)).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the per-tenant ceiling is respected")
    void perTenantCeilingIsRespected() {
        List<TenantWorkload> workloads = List.of(new TenantWorkload(noisy, 1, 1000));

        Map<UUID, Integer> allocation = selector.allocate(workloads, 100, 5, 0);

        assertThat(allocation.get(noisy)).isEqualTo(5);
    }

    @Test
    @DisplayName("capacity left over by ceilings is redistributed rather than wasted")
    void leftoverCapacityIsRedistributed() {
        List<TenantWorkload> workloads = List.of(
                new TenantWorkload(noisy, 1, 2),
                new TenantWorkload(quiet, 1, 100));

        Map<UUID, Integer> allocation = selector.allocate(workloads, 20, 100, 0);

        int total = allocation.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(total)
                .as("the noisy tenant can only use 2, so the rest should go to the other")
                .isEqualTo(20);
        assertThat(allocation.get(noisy)).isEqualTo(2);
        assertThat(allocation.get(quiet)).isEqualTo(18);
    }

    @Test
    @DisplayName("the total allocated never exceeds the cycle's capacity")
    void neverOverAllocates() {
        List<TenantWorkload> workloads = List.of(
                new TenantWorkload(noisy, 5, 1000),
                new TenantWorkload(quiet, 3, 1000),
                new TenantWorkload(third, 1, 1000));

        for (int slots = 1; slots <= 50; slots++) {
            Map<UUID, Integer> allocation = selector.allocate(workloads, slots, 1000, slots);
            int total = allocation.values().stream().mapToInt(Integer::intValue).sum();

            assertThat(total)
                    .as("allocating %d slots", slots)
                    .isLessThanOrEqualTo(slots);
        }
    }

    @Test
    @DisplayName("rotation means scarce capacity eventually reaches every tenant")
    void rotationPreventsPositionalStarvation() {
        // With one slot and three tenants, a fixed order would serve the first tenant forever.
        List<TenantWorkload> workloads = List.of(
                new TenantWorkload(noisy, 1, 100),
                new TenantWorkload(quiet, 1, 100),
                new TenantWorkload(third, 1, 100));

        Set<UUID> served = new HashSet<>();
        for (long cycle = 0; cycle < 9; cycle++) {
            served.addAll(selector.allocate(workloads, 1, 100, cycle).keySet());
        }

        assertThat(served)
                .as("every tenant should be reached within a few cycles")
                .containsExactlyInAnyOrder(noisy, quiet, third);
    }

    @Test
    @DisplayName("no work means no allocation")
    void emptyInputsAreHandled() {
        assertThat(selector.allocate(List.of(), 10, 10, 0)).isEmpty();
        assertThat(selector.allocate(
                List.of(new TenantWorkload(noisy, 1, 10)), 0, 10, 0)).isEmpty();
    }
}
