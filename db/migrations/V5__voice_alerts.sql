-- =============================================================================
-- VeriTransit — V5: tamper voice alerts
--
-- The dock speaks first (Kokoro voice on the phone) and the supervisor hears
-- it second: the phone uploads the exact WAV it just played, the bot forwards
-- it as a Telegram voice message. Audio lives in BYTEA — notes are seconds of
-- 16 kHz mono, small enough that a sidecar object store would be ceremony.
-- =============================================================================

CREATE TABLE voice_alerts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_ref     TEXT,
    package_code     TEXT,
    verdict          TEXT NOT NULL,          -- VERIFIED | SUSPECT_REVIEW | REJECTED
    caption          TEXT NOT NULL DEFAULT '',
    mime_type        TEXT NOT NULL DEFAULT 'audio/wav',
    audio            BYTEA NOT NULL,
    officer          TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ,
    tg_file_id       TEXT
);

CREATE INDEX voice_alerts_pending_idx ON voice_alerts (created_at)
    WHERE delivered_at IS NULL;
