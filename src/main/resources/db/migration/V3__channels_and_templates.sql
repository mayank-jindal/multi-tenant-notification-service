-- V3 — Per-tenant channel configuration and versioned templates.

CREATE TABLE tenant_channel_configs (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    channel              VARCHAR(16)  NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Display identity the provider sends as: an address for EMAIL, a sender id or long code
    -- for SMS, an application identifier for PUSH. Unused for IN_APP.
    sender_identity      VARCHAR(255) NULL,
    -- Provider credentials, AES-encrypted at rest (ADR: secrets are never stored in plaintext).
    -- Stored as ciphertext rather than a structured column so the shape stays provider-agnostic.
    credentials_cipher   TEXT         NULL,
    provider_code        VARCHAR(64)  NOT NULL DEFAULT 'SIMULATOR',
    -- Optional per-channel override of the tenant's dispatch concurrency ceiling.
    max_in_flight        INTEGER      NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_channel_config_tenant_channel UNIQUE (tenant_id, channel),
    CONSTRAINT ck_channel_config_channel        CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_channel_config_in_flight      CHECK (max_in_flight IS NULL OR max_in_flight > 0)
);

COMMENT ON COLUMN tenant_channel_configs.credentials_cipher IS 'AES-GCM ciphertext of the provider credential document.';


-- A template is a stable, tenant-facing handle. Its content lives in immutable versions so that
-- a notification can always be re-rendered exactly as it was sent, even after the tenant edits
-- the template (ADR: template versioning).
CREATE TABLE templates (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    code          VARCHAR(64)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT         NULL,
    archived      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_templates_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_templates_code_fmt    CHECK (code ~ '^[a-z0-9][a-z0-9_.-]{1,62}[a-z0-9]$')
);

COMMENT ON COLUMN templates.code IS 'Tenant-chosen stable identifier used when submitting a send request.';


CREATE TABLE template_versions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    template_id      UUID        NOT NULL REFERENCES templates (id) ON DELETE CASCADE,
    version_number   INTEGER     NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    -- Declared variable contract: an array of {name, required, description}. Rendering is
    -- strict, so this is what a send request is validated against before any work is queued.
    variables_schema JSONB       NOT NULL DEFAULT '[]'::jsonb,
    published_at     TIMESTAMPTZ NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT uq_template_versions_number UNIQUE (template_id, version_number),
    CONSTRAINT ck_template_versions_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_template_versions_number CHECK (version_number > 0),
    CONSTRAINT ck_template_versions_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL) OR
        (status <> 'PUBLISHED')
    )
);

-- At most one PUBLISHED version per template: the send path resolves a template code to exactly
-- one renderable version without needing a tie-break rule.
CREATE UNIQUE INDEX uq_template_versions_one_published
    ON template_versions (template_id)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_template_versions_template ON template_versions (template_id);


-- One template version carries a body per channel, so a single logical notification
-- ("order shipped") can fan out to email, SMS and push with channel-appropriate content.
CREATE TABLE template_channel_bodies (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    template_version_id UUID         NOT NULL REFERENCES template_versions (id) ON DELETE CASCADE,
    channel             VARCHAR(16)  NOT NULL,
    -- EMAIL uses subject + body_html + body_text; PUSH uses subject as the title;
    -- SMS and IN_APP use body_text only.
    subject             VARCHAR(998) NULL,
    body_text           TEXT         NULL,
    body_html           TEXT         NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_template_body_version_channel UNIQUE (template_version_id, channel),
    CONSTRAINT ck_template_body_channel         CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_template_body_has_content     CHECK (body_text IS NOT NULL OR body_html IS NOT NULL)
);
