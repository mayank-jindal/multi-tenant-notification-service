-- V1 — Baseline.
--
-- Establishes the tenant table, the root of the ownership graph. Every other tenant-owned
-- table added in later migrations carries a tenant_id referencing this table, which is the
-- mechanism by which the shared-schema isolation model (ADR-001) is enforced.

CREATE TABLE tenants (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            VARCHAR(64)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    -- Fairness weight used by the dispatcher when round-robining across tenants (ADR-005).
    -- A higher weight earns a tenant proportionally more claim slots per dispatch cycle.
    dispatch_weight INTEGER      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_tenants_slug     UNIQUE (slug),
    CONSTRAINT ck_tenants_status   CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_tenants_weight   CHECK (dispatch_weight BETWEEN 1 AND 100),
    CONSTRAINT ck_tenants_slug_fmt CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$')
);

COMMENT ON TABLE  tenants                 IS 'Root of the multi-tenant ownership graph.';
COMMENT ON COLUMN tenants.slug            IS 'Stable, human-readable tenant identifier used in JWT claims and admin URLs.';
COMMENT ON COLUMN tenants.dispatch_weight IS 'Relative share of dispatch capacity under contention.';
