-- V2 — Authentication principals and role assignment.
--
-- A platform admin is deliberately modelled as a user with a NULL tenant_id rather than a
-- member of a synthetic "system" tenant: it makes "this row belongs to no tenant" explicit and
-- lets the tenant isolation filter treat NULL as out-of-scope instead of matchable data.

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NULL REFERENCES tenants (id) ON DELETE CASCADE,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMPTZ  NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_users_email  UNIQUE (email),
    CONSTRAINT ck_users_role   CHECK (role IN ('PLATFORM_ADMIN', 'TENANT_ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),

    -- The role/tenancy pairing is an invariant, so the database enforces it rather than
    -- trusting every write path to remember: platform admins are tenant-less by definition,
    -- tenant admins are meaningless without one.
    CONSTRAINT ck_users_tenancy_matches_role CHECK (
        (role = 'PLATFORM_ADMIN' AND tenant_id IS NULL) OR
        (role = 'TENANT_ADMIN'   AND tenant_id IS NOT NULL)
    )
);

CREATE INDEX idx_users_tenant ON users (tenant_id) WHERE tenant_id IS NOT NULL;

COMMENT ON TABLE  users           IS 'Authentication principals for both platform and tenant scopes.';
COMMENT ON COLUMN users.tenant_id IS 'NULL for PLATFORM_ADMIN; the owning tenant for TENANT_ADMIN.';
