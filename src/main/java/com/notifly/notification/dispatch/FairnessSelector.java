package com.notifly.notification.dispatch;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides how a dispatch cycle's capacity is divided between tenants.
 *
 * <p>This is what stops one tenant's bulk send from starving everyone else. Claiming in pure
 * insertion order would mean a tenant who enqueues a million notifications occupies every worker
 * until they drain — which can be hours — while another tenant's single password reset waits
 * behind them. That behaviour is exactly what the requirement's "per-tenant fairness under load"
 * rules out.
 *
 * <p>The allocation is weighted round-robin. Every tenant with work gets at least one slot, the
 * remainder is shared in proportion to dispatch weight, and anything left over after tenants hit
 * their own limits is redistributed rather than wasted. A rotating offset decides who is served
 * first when there are fewer slots than tenants, so the same tenant is not repeatedly favoured by
 * position alone.
 *
 * <p>Pure and stateless apart from the caller-supplied rotation, so it is unit-testable without a
 * database, a thread pool or a clock.
 */
@Component
public class FairnessSelector {

    /**
     * Allocates claim slots across tenants.
     *
     * @param workloads    tenants with claimable work
     * @param totalSlots   how many notifications this cycle may claim on this channel
     * @param maxPerTenant ceiling on any single tenant's allocation
     * @param rotation     monotonically increasing counter; rotates who is considered first
     * @return slots per tenant, omitting tenants allocated nothing
     */
    public Map<UUID, Integer> allocate(List<TenantWorkload> workloads,
                                       int totalSlots,
                                       int maxPerTenant,
                                       long rotation) {
        Map<UUID, Integer> allocation = new LinkedHashMap<>();
        if (workloads.isEmpty() || totalSlots <= 0 || maxPerTenant <= 0) {
            return allocation;
        }

        List<TenantWorkload> ordered = rotate(workloads, rotation);

        // Ceiling for each tenant: never more than it can use, never more than it is allowed.
        Map<UUID, Integer> ceiling = new LinkedHashMap<>();
        for (TenantWorkload workload : ordered) {
            ceiling.put(workload.tenantId(),
                    (int) Math.min(workload.pending(), maxPerTenant));
        }

        int remaining = totalSlots;

        // Pass one: a guaranteed slot each, in rotated order. This is the part that makes the
        // scheme fair rather than merely proportional — a tenant with weight 1 competing against
        // weight 100 still makes progress every cycle.
        for (TenantWorkload workload : ordered) {
            if (remaining == 0) {
                break;
            }
            if (ceiling.get(workload.tenantId()) > 0) {
                allocation.merge(workload.tenantId(), 1, Integer::sum);
                remaining--;
            }
        }

        if (remaining == 0) {
            return allocation;
        }

        // Pass two: share what is left in proportion to weight.
        int totalWeight = ordered.stream()
                .filter(w -> ceiling.get(w.tenantId()) > allocation.getOrDefault(w.tenantId(), 0))
                .mapToInt(TenantWorkload::weight)
                .sum();

        if (totalWeight > 0) {
            int toShare = remaining;
            for (TenantWorkload workload : ordered) {
                int already = allocation.getOrDefault(workload.tenantId(), 0);
                int headroom = ceiling.get(workload.tenantId()) - already;
                if (headroom <= 0) {
                    continue;
                }
                int share = Math.min(headroom, (int) Math.floor((double) toShare * workload.weight() / totalWeight));
                if (share > 0) {
                    allocation.merge(workload.tenantId(), share, Integer::sum);
                    remaining -= share;
                }
            }
        }

        // Pass three: rounding and per-tenant ceilings leave slots unassigned. Handing them out
        // round-robin keeps the pool busy instead of idling on arithmetic.
        boolean progressed = true;
        while (remaining > 0 && progressed) {
            progressed = false;
            for (TenantWorkload workload : ordered) {
                if (remaining == 0) {
                    break;
                }
                int already = allocation.getOrDefault(workload.tenantId(), 0);
                if (already < ceiling.get(workload.tenantId())) {
                    allocation.merge(workload.tenantId(), 1, Integer::sum);
                    remaining--;
                    progressed = true;
                }
            }
        }

        return allocation;
    }

    /**
     * Rotates the list so a different tenant leads each cycle.
     *
     * <p>Without this, when slots are scarce the tenants at the front of the list would be served
     * every cycle and those at the back never — fair shares on paper, starvation in practice.
     */
    private List<TenantWorkload> rotate(List<TenantWorkload> workloads, long rotation) {
        int size = workloads.size();
        int offset = (int) Math.floorMod(rotation, size);
        if (offset == 0) {
            return workloads;
        }
        List<TenantWorkload> rotated = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rotated.add(workloads.get((offset + i) % size));
        }
        return rotated;
    }
}
