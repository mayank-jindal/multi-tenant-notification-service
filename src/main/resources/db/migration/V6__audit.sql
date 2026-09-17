-- V6 — Append-only audit trail.
--
-- Covers both halves of the requirement: every notification state transition, and every
-- administrative configuration change. Rows are never updated or deleted; corrections are
-- expressed as new events.

CREATE TABLE audit_events (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL for platform-scoped actions such as creating a tenant, which by definition happen
    -- outside any single tenant.
    tenant_id     UUID         NULL REFERENCES tenants (id) ON DELETE CASCADE,

    event_type    VARCHAR(64)  NOT NULL,
    entity_type   VARCHAR(64)  NOT NULL,
    entity_id     UUID         NULL,

    -- Populated for state-transition events; NULL for configuration changes.
    from_state    VARCHAR(24)  NULL,
    to_state      VARCHAR(24)  NULL,

    -- The acting principal. Retained as a plain column rather than a foreign key so that
    -- deleting a user cannot erase the history of what they did.
    actor_user_id UUID         NULL,
    actor_email   VARCHAR(255) NULL,
    actor_role    VARCHAR(32)  NULL,
    -- SYSTEM covers transitions made by the dispatcher and schedulers, which have no user.
    actor_kind    VARCHAR(16)  NOT NULL DEFAULT 'USER',

    -- Event-specific payload: for configuration changes, the before/after values; for
    -- transitions, the provider response, error code or backoff decision.
    details       JSONB        NOT NULL DEFAULT '{}'::jsonb,

    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_audit_actor_kind CHECK (actor_kind IN ('USER', 'SYSTEM'))
);

CREATE INDEX idx_audit_tenant_time   ON audit_events (tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_entity        ON audit_events (entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_type_time     ON audit_events (event_type, occurred_at DESC);
CREATE INDEX idx_audit_actor         ON audit_events (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

COMMENT ON TABLE  audit_events            IS 'Append-only audit trail for state transitions and admin actions.';
COMMENT ON COLUMN audit_events.actor_kind IS 'USER for authenticated actions, SYSTEM for dispatcher and scheduler activity.';
