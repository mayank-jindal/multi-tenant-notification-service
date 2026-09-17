-- V7 — A displayable hint for stored provider credentials.
--
-- Credentials are write-only over the API: the plaintext is decrypted only by the dispatcher at
-- send time and is never returned in a response. That leaves a tenant admin unable to tell which
-- key is configured, so a masked hint of the last few characters is stored alongside the
-- ciphertext. The hint is deliberately not sensitive and is therefore not encrypted.

ALTER TABLE tenant_channel_configs
    ADD COLUMN credentials_hint VARCHAR(64) NULL;

COMMENT ON COLUMN tenant_channel_configs.credentials_hint
    IS 'Masked tail of the stored credential, for display only. Never the full value.';
