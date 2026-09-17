-- V5 — Throughput governance, submission de-duplication, and delivery suppression.

-- Token bucket configuration. A policy with tenant_id IS NULL is the platform-wide default;
-- a policy with a tenant_id overrides it for that tenant. Within either scope, channel IS NULL
-- means "all channels", and a channel-specific row overrides it.
--
-- Resolution order for a given (tenant, channel), most specific first:
--   1. tenant + channel   2. tenant + all channels   3. global + channel   4. global + all
CREATE TABLE rate_limit_policies (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID        NULL REFERENCES tenants (id) ON DELETE CASCADE,
    channel              VARCHAR(16) NULL,
    -- Bucket depth: the largest burst permitted before throttling begins.
    capacity             INTEGER     NOT NULL,
    -- Sustained rate: refill_tokens added every refill_period_seconds.
    refill_tokens        INTEGER     NOT NULL,
    refill_period_seconds INTEGER    NOT NULL DEFAULT 1,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ck_rate_limit_channel  CHECK (channel IS NULL OR channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_rate_limit_capacity CHECK (capacity > 0),
    CONSTRAINT ck_rate_limit_refill   CHECK (refill_tokens > 0 AND refill_period_seconds > 0)
);

-- Uniqueness has to be expressed as two partial indexes because NULL never equals NULL in a
-- composite UNIQUE constraint, which would otherwise allow unlimited duplicate global policies.
CREATE UNIQUE INDEX uq_rate_limit_tenant_channel
    ON rate_limit_policies (tenant_id, channel)
    WHERE tenant_id IS NOT NULL AND channel IS NOT NULL;

CREATE UNIQUE INDEX uq_rate_limit_tenant_all
    ON rate_limit_policies (tenant_id)
    WHERE tenant_id IS NOT NULL AND channel IS NULL;

CREATE UNIQUE INDEX uq_rate_limit_global_channel
    ON rate_limit_policies (channel)
    WHERE tenant_id IS NULL AND channel IS NOT NULL;

CREATE UNIQUE INDEX uq_rate_limit_global_all
    ON rate_limit_policies ((TRUE))
    WHERE tenant_id IS NULL AND channel IS NULL;

COMMENT ON TABLE rate_limit_policies IS 'Token bucket definitions resolved most-specific-first.';


-- Submission idempotency. A replayed request carrying a key already seen for this tenant
-- returns the original outcome instead of creating a second batch of work (ADR-006).
CREATE TABLE idempotency_keys (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    idempotency_key         VARCHAR(255) NOT NULL,
    -- Hash of the canonicalised request body. A replay whose key matches but whose fingerprint
    -- differs is a client bug, and is rejected with 409 rather than silently returning someone
    -- else's result.
    request_fingerprint     VARCHAR(64)  NOT NULL,
    request_path            VARCHAR(255) NOT NULL,
    notification_request_id UUID         NULL REFERENCES notification_requests (id) ON DELETE CASCADE,
    response_status         INTEGER      NULL,
    response_body           JSONB        NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at              TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);


-- Addresses that must not be delivered to: hard bounces, opt-outs, complaints. Checked at
-- submission, so a suppressed recipient never occupies dispatch capacity at all.
CREATE TABLE suppressions (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    channel    VARCHAR(16)  NOT NULL,
    address    VARCHAR(512) NOT NULL,
    reason     VARCHAR(32)  NOT NULL,
    note       TEXT         NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- NULL means the suppression never lapses.
    expires_at TIMESTAMPTZ  NULL,

    CONSTRAINT uq_suppressions_tenant_channel_address UNIQUE (tenant_id, channel, address),
    CONSTRAINT ck_suppressions_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_suppressions_reason  CHECK (
        reason IN ('HARD_BOUNCE', 'COMPLAINT', 'UNSUBSCRIBED', 'INVALID_ADDRESS', 'MANUAL')
    )
);

CREATE INDEX idx_suppressions_lookup ON suppressions (tenant_id, channel, address);
