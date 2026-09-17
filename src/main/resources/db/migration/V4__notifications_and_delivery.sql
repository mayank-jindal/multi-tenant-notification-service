-- V4 — The send pipeline: submissions, the per-recipient work items, and their attempt history.

-- One row per accepted API submission. A submission fans out into many notifications
-- (recipients x channels); keeping the submission separate preserves what the caller actually
-- asked for, independently of how it was expanded.
CREATE TABLE notification_requests (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    template_id         UUID         NULL REFERENCES templates (id) ON DELETE SET NULL,
    template_version_id UUID         NULL REFERENCES template_versions (id) ON DELETE SET NULL,
    -- The variable values supplied by the caller, validated against the version's declared
    -- schema at submission time and retained so a send can be explained after the fact.
    variables           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- NULL means "dispatch immediately"; a future timestamp defers eligibility.
    scheduled_at        TIMESTAMPTZ  NULL,
    status              VARCHAR(24)  NOT NULL DEFAULT 'ACCEPTED',
    notification_count  INTEGER      NOT NULL DEFAULT 0,
    submitted_by        UUID         NULL REFERENCES users (id) ON DELETE SET NULL,
    idempotency_key     VARCHAR(255) NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_notification_requests_status CHECK (
        status IN ('ACCEPTED', 'SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT ck_notification_requests_count CHECK (notification_count >= 0)
);

CREATE INDEX idx_notification_requests_tenant_created ON notification_requests (tenant_id, created_at DESC);
CREATE INDEX idx_notification_requests_scheduled      ON notification_requests (scheduled_at)
    WHERE status = 'SCHEDULED';


-- The unit of work. One row per (recipient, channel) pair; this is the table the dispatcher
-- claims from and the table delivery reporting aggregates over.
CREATE TABLE notifications (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    request_id          UUID         NOT NULL REFERENCES notification_requests (id) ON DELETE CASCADE,
    channel             VARCHAR(16)  NOT NULL,

    -- Destination. recipient_address is the channel-native target (email address, MSISDN,
    -- device token). recipient_ref is the tenant's own identifier for the person, and is what
    -- the in-app inbox is queried by.
    recipient_address   VARCHAR(512) NOT NULL,
    recipient_ref       VARCHAR(255) NULL,

    -- Content is rendered once, at submission, and frozen. Rendering at dispatch time would
    -- mean a template edit could silently change what an already-queued notification says.
    rendered_subject    VARCHAR(998) NULL,
    rendered_body_text  TEXT         NULL,
    rendered_body_html  TEXT         NULL,

    status              VARCHAR(24)  NOT NULL DEFAULT 'CREATED',
    priority            SMALLINT     NOT NULL DEFAULT 5,

    -- Eligibility clock. next_attempt_at carries both the schedule and the retry backoff, so
    -- the claim query never needs to know which of the two deferred this row.
    scheduled_at        TIMESTAMPTZ  NULL,
    next_attempt_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    attempt_count       INTEGER      NOT NULL DEFAULT 0,
    max_attempts        INTEGER      NOT NULL DEFAULT 5,

    -- Dispatch lease. A worker claims a row by stamping a token and an expiry; a crashed worker
    -- leaves an expired lease that the reaper returns to the queue. Combined with attempt rows
    -- being written before the provider call, this is what stops a retry double-sending.
    lease_token         UUID         NULL,
    lease_owner         VARCHAR(128) NULL,
    lease_expires_at    TIMESTAMPTZ  NULL,

    -- Stable fingerprint of (tenant, channel, recipient, content). Not unique: a tenant may
    -- legitimately send the same message twice. Used to surface accidental duplicates.
    dedupe_hash         VARCHAR(64)  NULL,

    provider_code       VARCHAR(64)  NULL,
    provider_message_id VARCHAR(255) NULL,
    last_error_code     VARCHAR(64)  NULL,
    last_error_message  TEXT         NULL,

    sent_at             TIMESTAMPTZ  NULL,
    delivered_at        TIMESTAMPTZ  NULL,
    terminal_at         TIMESTAMPTZ  NULL,
    -- IN_APP only: when the recipient marked the message read.
    read_at             TIMESTAMPTZ  NULL,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_notifications_channel  CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_notifications_priority CHECK (priority BETWEEN 1 AND 9),
    CONSTRAINT ck_notifications_attempts CHECK (attempt_count >= 0 AND max_attempts BETWEEN 1 AND 20),
    CONSTRAINT ck_notifications_status   CHECK (
        status IN ('CREATED', 'SCHEDULED', 'QUEUED', 'SENDING', 'SENT',
                   'DELIVERED', 'RETRY_SCHEDULED', 'FAILED', 'CANCELLED', 'SUPPRESSED')
    ),
    -- A lease is all-or-nothing; a half-stamped lease would be unreapable.
    CONSTRAINT ck_notifications_lease_complete CHECK (
        (lease_token IS NULL     AND lease_owner IS NULL     AND lease_expires_at IS NULL) OR
        (lease_token IS NOT NULL AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

-- The dispatcher's claim path. Partial so the index stays small as delivered rows accumulate:
-- only work that is actually claimable is indexed.
CREATE INDEX idx_notifications_claimable
    ON notifications (channel, next_attempt_at, priority)
    WHERE status IN ('QUEUED', 'RETRY_SCHEDULED');

-- Fairness: lets the dispatcher enumerate which tenants currently have claimable work in a
-- channel without scanning the whole backlog (ADR-005).
CREATE INDEX idx_notifications_fairness
    ON notifications (channel, tenant_id, next_attempt_at)
    WHERE status IN ('QUEUED', 'RETRY_SCHEDULED');

-- Lease reaping: finds rows abandoned by a crashed worker.
CREATE INDEX idx_notifications_expired_leases
    ON notifications (lease_expires_at)
    WHERE status = 'SENDING';

-- Promotion of scheduled work into the queue.
CREATE INDEX idx_notifications_scheduled
    ON notifications (scheduled_at)
    WHERE status = 'SCHEDULED';

-- Reporting and tenant-facing search.
CREATE INDEX idx_notifications_tenant_created ON notifications (tenant_id, created_at DESC);
CREATE INDEX idx_notifications_tenant_status  ON notifications (tenant_id, status, channel);
CREATE INDEX idx_notifications_request        ON notifications (request_id);
CREATE INDEX idx_notifications_dedupe         ON notifications (tenant_id, dedupe_hash)
    WHERE dedupe_hash IS NOT NULL;

-- In-app inbox lookup.
CREATE INDEX idx_notifications_inbox
    ON notifications (tenant_id, recipient_ref, created_at DESC)
    WHERE channel = 'IN_APP';


-- Append-only record of every provider interaction. One row per attempt, written before the
-- provider is called so that a crash mid-send still leaves evidence the attempt happened.
CREATE TABLE delivery_attempts (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    notification_id     UUID         NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    attempt_number      INTEGER      NOT NULL,
    outcome             VARCHAR(24)  NULL,
    provider_code       VARCHAR(64)  NOT NULL,
    provider_message_id VARCHAR(255) NULL,
    error_code          VARCHAR(64)  NULL,
    error_message       TEXT         NULL,
    latency_ms          INTEGER      NULL,
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ  NULL,

    CONSTRAINT uq_delivery_attempts_number UNIQUE (notification_id, attempt_number),
    CONSTRAINT ck_delivery_attempts_number CHECK (attempt_number > 0),
    -- NULL outcome means the attempt is still in flight, or the worker died during it.
    CONSTRAINT ck_delivery_attempts_outcome CHECK (
        outcome IS NULL OR outcome IN ('SUCCESS', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE', 'ABANDONED')
    )
);

CREATE INDEX idx_delivery_attempts_notification ON delivery_attempts (notification_id, attempt_number);
CREATE INDEX idx_delivery_attempts_tenant_time  ON delivery_attempts (tenant_id, started_at DESC);
