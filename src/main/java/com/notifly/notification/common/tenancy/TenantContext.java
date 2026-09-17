package com.notifly.notification.common.tenancy;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Holds the {@link TenantScope} for the current thread.
 *
 * <p>Read by Hibernate on every session to decide which tenant's rows exist. Populated per
 * request by {@code TenantContextFilter}, and explicitly by the dispatcher, which runs on worker
 * threads that have no request to inherit from.
 *
 * <p>The default is {@link TenantScope#UNSCOPED} — sees nothing. That direction matters: a thread
 * that forgot to establish its scope reads no data, rather than reading everyone's.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT = ThreadLocal.withInitial(() -> TenantScope.UNSCOPED);

    private TenantContext() {
    }

    public static TenantScope current() {
        return CURRENT.get();
    }

    public static UUID currentTenantId() {
        return CURRENT.get().tenantId();
    }

    /** The tenant id, failing loudly if the caller is not actually inside a tenant. */
    public static UUID requireTenantId() {
        return CURRENT.get().requireRealTenant();
    }

    public static boolean isPlatformAdmin() {
        return CURRENT.get().platformAdmin();
    }

    public static void set(TenantScope scope) {
        CURRENT.set(scope == null ? TenantScope.UNSCOPED : scope);
    }

    /**
     * Clears the scope. Must be called in a finally block by whoever set it — thread pools reuse
     * threads, and a leaked scope would hand one tenant's context to the next task that runs.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code work} scoped to a tenant, restoring the previous scope afterwards. */
    public static <T> T callAs(TenantScope scope, Callable<T> work) throws Exception {
        TenantScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.call();
        } finally {
            CURRENT.set(previous);
        }
    }

    /** Runs {@code work} scoped to a tenant, restoring the previous scope afterwards. */
    public static <T> T getAs(TenantScope scope, Supplier<T> work) {
        TenantScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    /** Runs {@code work} scoped to a tenant, restoring the previous scope afterwards. */
    public static void runAs(TenantScope scope, Runnable work) {
        TenantScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            work.run();
        } finally {
            CURRENT.set(previous);
        }
    }

    /** Convenience for the dispatcher, which processes one tenant's work item at a time. */
    public static void runAsTenant(UUID tenantId, Runnable work) {
        runAs(TenantScope.of(tenantId), work);
    }
}
