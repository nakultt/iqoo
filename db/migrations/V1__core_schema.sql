-- =============================================================================
-- VeriTransit — V1: verification core
-- Implements MASTER_PLAN.md §8.1 "verification core" + §4.4 package hierarchy.
--
-- Naming and column sets follow the plan's SQL sketch; types, constraints and
-- indexes are filled in here. Phase 2 of the roadmap requires the full schema
-- (core + commerce) to ship at once so later phases add code, not migrations.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), digest()

-- ---------------------------------------------------------------------------
-- Enumerated domains
-- Plain lookup tables would be over-engineering here; CHECK constraints keep
-- the vocabulary of §4.3 / §5.2 enforced at the database boundary.
-- ---------------------------------------------------------------------------

CREATE TYPE site_kind        AS ENUM ('WAREHOUSE', 'GATE', 'DELIVERY');
CREATE TYPE user_role        AS ENUM ('ADMIN', 'SUPERVISOR', 'OFFICER', 'PACKER',
                                      'FINANCE_MAKER', 'FINANCE_CHECKER');
CREATE TYPE shipment_status  AS ENUM ('OPEN', 'LOADING', 'DISPATCHED', 'RECEIVED', 'FLAGGED');
CREATE TYPE package_kind     AS ENUM ('UNIT', 'MASTER', 'PALLET');
CREATE TYPE package_status   AS ENUM ('CREATED', 'PRINTED', 'LOADED', 'RECEIVED', 'MISSING', 'FLAGGED');
CREATE TYPE scan_kind        AS ENUM ('LOAD', 'RECEIVE', 'FIELD', 'POD');
CREATE TYPE scan_result      AS ENUM ('VERIFIED', 'SUSPECT_REVIEW', 'REJECTED');
CREATE TYPE scan_source      AS ENUM ('DEVICE', 'SERVER', 'WEB');

-- ---------------------------------------------------------------------------
-- Tenancy, sites, people, devices
-- ---------------------------------------------------------------------------

CREATE TABLE tenants (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sites (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        text NOT NULL,
    kind        site_kind NOT NULL,
    address     text,
    lat         numeric(9,6),
    lng         numeric(9,6),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE users (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        text NOT NULL,
    role        user_role NOT NULL,
    email       text,
    tg_handle   text,                       -- Telegram surface, §6.4
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE UNIQUE INDEX users_tg_handle_key ON users (lower(tg_handle)) WHERE tg_handle IS NOT NULL;

CREATE TABLE devices (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    site_id       uuid REFERENCES sites(id) ON DELETE SET NULL,
    label         text NOT NULL,            -- human name, e.g. "Dock-A Handheld 1"
    api_key_hash  text NOT NULL,            -- sha256 of the issued key; key itself never stored
    npu_capable   boolean NOT NULL DEFAULT false,
    activated_at  timestamptz,
    retired_at    timestamptz,
    last_seen_at  timestamptz,
    UNIQUE (tenant_id, label)
);
CREATE INDEX devices_active_idx ON devices (tenant_id) WHERE retired_at IS NULL;

-- ---------------------------------------------------------------------------
-- Shipments and packages (§4.1, §4.4)
-- ---------------------------------------------------------------------------

CREATE TABLE shipments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ref             text NOT NULL,          -- SHP-2026-090231
    vehicle         text,
    origin_site     uuid REFERENCES sites(id),
    dest_site       uuid REFERENCES sites(id),
    expected_count  integer NOT NULL DEFAULT 0 CHECK (expected_count >= 0),
    status          shipment_status NOT NULL DEFAULT 'OPEN',
    created_by      uuid REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    dispatched_at   timestamptz,
    received_at     timestamptz,
    UNIQUE (tenant_id, ref)
);
CREATE INDEX shipments_status_idx ON shipments (tenant_id, status);
CREATE INDEX shipments_ref_idx    ON shipments (ref);

CREATE TABLE packages (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id   uuid NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    package_code  text NOT NULL UNIQUE,     -- VT-P-8F3K2M9D
    kind          package_kind NOT NULL DEFAULT 'UNIT',
    parent_code   text REFERENCES packages(package_code) ON DELETE SET NULL,
    contents      text,                     -- declared description
    sku           text,
    hsn           text,
    qty           integer NOT NULL DEFAULT 1 CHECK (qty > 0),
    po_line_no    integer,                  -- links a package back to the PO line it fulfils
    weight_g      integer CHECK (weight_g IS NULL OR weight_g >= 0),
    status        package_status NOT NULL DEFAULT 'CREATED',
    created_at    timestamptz NOT NULL DEFAULT now(),
    -- §4.4: only MASTER/PALLET may contain children, so a UNIT can never be a parent.
    -- Enforced by the trigger below (a CHECK cannot see the parent row).
    CONSTRAINT packages_parent_not_self CHECK (parent_code IS NULL OR parent_code <> package_code)
);
CREATE INDEX packages_shipment_idx ON packages (shipment_id);
CREATE INDEX packages_parent_idx   ON packages (parent_code);
CREATE INDEX packages_status_idx   ON packages (shipment_id, status);
CREATE INDEX packages_kind_idx     ON packages (shipment_id, kind);

CREATE OR REPLACE FUNCTION packages_check_parent() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    parent_kind package_kind;
    parent_ship uuid;
BEGIN
    IF NEW.parent_code IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT kind, shipment_id INTO parent_kind, parent_ship
      FROM packages WHERE package_code = NEW.parent_code;
    IF parent_kind IS NULL THEN
        RAISE EXCEPTION 'parent package % does not exist', NEW.parent_code;
    END IF;
    IF parent_kind = 'UNIT' THEN
        RAISE EXCEPTION 'package % cannot nest under UNIT %', NEW.package_code, NEW.parent_code;
    END IF;
    IF parent_kind = NEW.kind AND NEW.kind = 'MASTER' THEN
        RAISE EXCEPTION 'MASTER % cannot nest under MASTER %', NEW.package_code, NEW.parent_code;
    END IF;
    IF parent_ship <> NEW.shipment_id THEN
        RAISE EXCEPTION 'package % and parent % belong to different shipments',
            NEW.package_code, NEW.parent_code;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER packages_check_parent_trg
    BEFORE INSERT OR UPDATE OF parent_code, kind, shipment_id ON packages
    FOR EACH ROW EXECUTE FUNCTION packages_check_parent();

-- ---------------------------------------------------------------------------
-- Labels (§4.2) — the signed token is the truth, not the QR
-- ---------------------------------------------------------------------------

CREATE TABLE labels (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id    uuid NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
    copy_no       integer NOT NULL DEFAULT 1 CHECK (copy_no >= 1),
    payload       text NOT NULL,            -- VT1|P=...|S=...|N=1|T=...  (signed bytes)
    signature     text NOT NULL,            -- base64url Ed25519, 86 chars
    key_id        text NOT NULL,            -- which signing key; supports rotation
    issued_at     timestamptz NOT NULL DEFAULT now(),
    superseded_at timestamptz,              -- set when a reprint supersedes this copy
    UNIQUE (package_id, copy_no)
);
-- At most one live copy per package: a reprint must supersede the old one first.
CREATE UNIQUE INDEX labels_one_live_copy ON labels (package_id) WHERE superseded_at IS NULL;
CREATE INDEX labels_key_idx ON labels (key_id);

-- The Ed25519 keypair registry (§4.2, §10 key ceremony). Private key lives in a
-- vault/env file, never here — the database only needs to know the public half.
CREATE TABLE signing_keys (
    key_id      text PRIMARY KEY,           -- e.g. vt-key-2026-01
    algorithm   text NOT NULL DEFAULT 'Ed25519',
    public_key  text NOT NULL,              -- base64url raw 32-byte public key
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    retired_at  timestamptz
);

-- ---------------------------------------------------------------------------
-- Scan events (§7.1) — idempotent by client_event_id, the outbox contract
-- ---------------------------------------------------------------------------

CREATE TABLE scan_events (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_event_id  uuid NOT NULL UNIQUE,  -- device-generated; makes ingest idempotent
    package_code     text NOT NULL,
    shipment_ref     text,
    device_id        uuid REFERENCES devices(id),
    officer_id       uuid REFERENCES users(id),
    kind             scan_kind NOT NULL,
    result           scan_result NOT NULL,
    reasons          jsonb NOT NULL DEFAULT '[]'::jsonb,   -- ["QR_BARCODE_MISMATCH", ...]
    ai_flags         jsonb NOT NULL DEFAULT '{}'::jsonb,   -- NPU verifyPackage output
    evidence_uri     text,                                 -- MinIO/S3 object key
    evidence_sha256  text,                                 -- hashed on-device before upload (§5.4)
    lat              numeric(9,6),
    lng              numeric(9,6),
    client_ts        timestamptz NOT NULL,
    server_ts        timestamptz NOT NULL DEFAULT now(),
    source           scan_source NOT NULL DEFAULT 'DEVICE',
    CONSTRAINT scan_reasons_is_array CHECK (jsonb_typeof(reasons) = 'array')
);
CREATE INDEX scan_events_package_idx  ON scan_events (package_code, server_ts DESC);
CREATE INDEX scan_events_shipment_idx ON scan_events (shipment_ref, server_ts DESC);
CREATE INDEX scan_events_device_idx   ON scan_events (device_id, server_ts DESC);
CREATE INDEX scan_events_result_idx   ON scan_events (result) WHERE result <> 'VERIFIED';
CREATE INDEX scan_events_reasons_idx  ON scan_events USING gin (reasons);

-- ---------------------------------------------------------------------------
-- Discrepancies (§4.3 reason codes, §6.3 discrepancy queue)
-- ---------------------------------------------------------------------------

CREATE TABLE discrepancies (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id   uuid NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    kind          text NOT NULL,            -- reason code: INNER_SHORTAGE, DUPLICATE_LABEL, ...
    package_code  text,
    severity      text NOT NULL DEFAULT 'MEDIUM' CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    detail        jsonb NOT NULL DEFAULT '{}'::jsonb,
    detected_at   timestamptz NOT NULL DEFAULT now(),
    resolved_by   uuid REFERENCES users(id),
    resolved_at   timestamptz,
    resolution    text,
    note          text,
    CONSTRAINT discrepancy_resolution_complete
        CHECK ((resolved_at IS NULL) = (resolved_by IS NULL))
);
CREATE INDEX discrepancies_open_idx     ON discrepancies (shipment_id) WHERE resolved_at IS NULL;
CREATE INDEX discrepancies_kind_idx     ON discrepancies (kind);

-- ---------------------------------------------------------------------------
-- Audit log (§3 check 9) — append-only hash chain
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log (
    seq        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at         timestamptz NOT NULL DEFAULT now(),
    actor      text NOT NULL,               -- user id, device id, 'agent', 'system'
    action     text NOT NULL,               -- SCAN_INGESTED, RELEASE_APPROVED, ...
    subject    text NOT NULL,               -- shipment ref / package code / doc no
    payload    jsonb NOT NULL DEFAULT '{}'::jsonb,
    prev_hash  text,
    hash       text NOT NULL
);
CREATE INDEX audit_log_subject_idx ON audit_log (subject, seq);
CREATE INDEX audit_log_action_idx  ON audit_log (action, seq);
