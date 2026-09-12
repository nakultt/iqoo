-- =============================================================================
-- VeriTransit — V2: commerce trust layer
-- Implements MASTER_PLAN.md §8.1 "commerce layer": parties, documents,
-- reconciliation runs, finance terms/events, risk scores, PoD certificates,
-- agent actions and policies (§5.1–§5.5).
-- =============================================================================

CREATE TYPE party_kind       AS ENUM ('SUPPLIER', 'BUYER', 'TRANSPORTER');
CREATE TYPE document_kind    AS ENUM ('PO', 'INVOICE', 'EWB', 'LR', 'PACKING_LIST', 'CHALLAN');
CREATE TYPE document_reader  AS ENUM ('device_npu', 'web_parser', 'manual');
CREATE TYPE finance_status   AS ENUM ('AWAITING', 'VERIFIED', 'HELD', 'RELEASE_PENDING', 'RELEASED');
CREATE TYPE payment_event_kind AS ENUM ('HOLD', 'RELEASE_REQUEST', 'RELEASE_APPROVED',
                                        'CERTIFICATE_ISSUED', 'WEBHOOK_DELIVERED', 'RESOLVED');
CREATE TYPE risk_band        AS ENUM ('LOW', 'MEDIUM', 'HIGH');
CREATE TYPE risk_subject     AS ENUM ('shipment', 'party', 'route');
CREATE TYPE policy_kind      AS ENUM ('agent_auto_release', 'friction_band', 'tolerance');

-- ---------------------------------------------------------------------------
-- Parties (§4.5 — party master data; GSTIN is the match key, never the name)
-- ---------------------------------------------------------------------------

CREATE TABLE parties (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind        party_kind NOT NULL,
    name        text NOT NULL,
    gstin       text,
    pan         text,
    meta        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT parties_gstin_format
        CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]{3}$'),
    CONSTRAINT parties_pan_format
        CHECK (pan IS NULL OR pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$')
);
CREATE UNIQUE INDEX parties_gstin_key ON parties (tenant_id, gstin) WHERE gstin IS NOT NULL;
CREATE INDEX parties_kind_idx ON parties (tenant_id, kind);

-- Which parties play which role on a shipment. The plan keeps supplier/buyer/
-- transporter on the shipment; a join table keeps it flexible without nullable
-- column sprawl and lets a shipment carry e.g. two transporters on a leg swap.
CREATE TABLE shipment_parties (
    shipment_id uuid NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    party_id    uuid NOT NULL REFERENCES parties(id) ON DELETE RESTRICT,
    role        party_kind NOT NULL,
    PRIMARY KEY (shipment_id, role, party_id)
);
CREATE INDEX shipment_parties_party_idx ON shipment_parties (party_id);

-- ---------------------------------------------------------------------------
-- Documents (§5.1) — DocumentFact is stored whole in `fact`; the columns beside
-- it are the denormalised handles the matcher and the UI query on.
-- ---------------------------------------------------------------------------

CREATE TABLE documents (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id  uuid NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    kind         document_kind NOT NULL,
    doc_no       text NOT NULL,
    doc_date     date,
    party_from   uuid REFERENCES parties(id),
    party_to     uuid REFERENCES parties(id),
    fact         jsonb NOT NULL,            -- DocumentFact: lines[], totals, tax, ship_to
    source_uri   text,                      -- MinIO/S3 key of the PDF or photo
    read_by      document_reader NOT NULL DEFAULT 'manual',
    confidence   numeric(4,3) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    confirmed_by uuid REFERENCES users(id), -- finance-critical numbers are human-confirmed (§5.1)
    uploaded_by  uuid REFERENCES users(id),
    uploaded_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT documents_fact_has_lines CHECK (jsonb_typeof(fact -> 'lines') = 'array')
);
-- §6.2 documents service: "dedupe by doc_no" — a doc number is unique per kind.
CREATE UNIQUE INDEX documents_dedupe_key ON documents (kind, upper(doc_no));
CREATE INDEX documents_shipment_idx ON documents (shipment_id, kind);
CREATE INDEX documents_fact_idx     ON documents USING gin (fact);

-- ---------------------------------------------------------------------------
-- Four-way match runs (§5.1) — ordered ↔ invoiced ↔ declared ↔ scanned
-- ---------------------------------------------------------------------------

CREATE TABLE reconciliation_runs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id  uuid NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    matrix       jsonb NOT NULL,            -- per-line ordered/invoiced/declared/physical
    mismatches   jsonb NOT NULL DEFAULT '[]'::jsonb,   -- [{code, line_no, delta_qty, delta_value}]
    status       text NOT NULL CHECK (status IN ('MATCHED', 'MISMATCHED', 'PARTIAL')),
    held_value   numeric(14,2) NOT NULL DEFAULT 0,     -- value the run says must be held
    run_by       text NOT NULL DEFAULT 'system',       -- user id | 'agent' | 'system'
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT recon_mismatches_is_array CHECK (jsonb_typeof(mismatches) = 'array')
);
CREATE INDEX recon_shipment_idx ON reconciliation_runs (shipment_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Finance (§5.2) — VeriTransit never moves money; it holds the state and signs
-- the certificate the payer's system acts on.
-- ---------------------------------------------------------------------------

CREATE TABLE finance_terms (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id    uuid NOT NULL UNIQUE REFERENCES shipments(id) ON DELETE CASCADE,
    currency       char(3) NOT NULL DEFAULT 'INR',
    order_value    numeric(14,2) NOT NULL CHECK (order_value >= 0),
    terms          jsonb NOT NULL,          -- {"on_verified_delivery": 70, "net_30": 30}
    status         finance_status NOT NULL DEFAULT 'AWAITING',
    released_value numeric(14,2) NOT NULL DEFAULT 0 CHECK (released_value >= 0),
    held_value     numeric(14,2) NOT NULL DEFAULT 0 CHECK (held_value >= 0),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT finance_values_within_order
        CHECK (released_value + held_value <= order_value)
);
CREATE INDEX finance_status_idx ON finance_terms (status);

CREATE TABLE payment_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id uuid NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    kind        payment_event_kind NOT NULL,
    actor       text NOT NULL,              -- user id | 'agent'
    policy_id   uuid,                       -- FK added after policies table below
    amount      numeric(14,2),
    payload     jsonb NOT NULL DEFAULT '{}'::jsonb,
    audit_seq   bigint REFERENCES audit_log(seq),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_events_shipment_idx ON payment_events (shipment_id, created_at);

-- ---------------------------------------------------------------------------
-- Risk (§5.3) — score + band + the factors that explain it (explainability is
-- mandatory: `factors` must be a non-empty array whenever a score exists)
-- ---------------------------------------------------------------------------

CREATE TABLE risk_scores (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_kind risk_subject NOT NULL,
    subject_id   text NOT NULL,             -- shipment ref | party uuid | route key
    score        integer NOT NULL CHECK (score BETWEEN 0 AND 100),
    band         risk_band NOT NULL,
    factors      jsonb NOT NULL,            -- [{factor, weight, detail}] — top contributors
    computed_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT risk_factors_explained
        CHECK (jsonb_typeof(factors) = 'array' AND jsonb_array_length(factors) > 0),
    -- Band must agree with the score thresholds of §5.3 (≤30 / 31–60 / ≥61)
    CONSTRAINT risk_band_matches_score CHECK (
        (score <= 30 AND band = 'LOW') OR
        (score BETWEEN 31 AND 60 AND band = 'MEDIUM') OR
        (score >= 61 AND band = 'HIGH')
    )
);
CREATE INDEX risk_subject_idx ON risk_scores (subject_kind, subject_id, computed_at DESC);

-- ---------------------------------------------------------------------------
-- Proof of delivery (§5.4)
-- ---------------------------------------------------------------------------

CREATE TABLE pod_certificates (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id      uuid NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    events           jsonb NOT NULL,        -- scan ids, signature/OTP, GPS, timestamps
    evidence_hashes  jsonb NOT NULL,        -- sha256 per photo, hashed on-device
    match_result     jsonb,                 -- four-way match snapshot at delivery time
    signature        text NOT NULL,         -- Ed25519 over the certificate body
    key_id           text REFERENCES signing_keys(key_id),
    issued_at        timestamptz NOT NULL DEFAULT now(),
    pdf_uri          text,
    bundle_uri       text                   -- claims ZIP (§5.4)
);
CREATE INDEX pod_shipment_idx ON pod_certificates (shipment_id, issued_at DESC);

-- ---------------------------------------------------------------------------
-- Policies (§5.5 guardrails) and agent actions
-- ---------------------------------------------------------------------------

CREATE TABLE policies (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind         policy_kind NOT NULL,
    name         text NOT NULL,
    rules        jsonb NOT NULL,
    active       boolean NOT NULL DEFAULT true,
    approved_by  uuid REFERENCES users(id),
    approved_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
-- A policy may only be active once a human has approved it (§5.5: policy disposes).
ALTER TABLE policies ADD CONSTRAINT policies_active_requires_approval
    CHECK (NOT active OR (approved_by IS NOT NULL AND approved_at IS NOT NULL));

ALTER TABLE payment_events
    ADD CONSTRAINT payment_events_policy_fk FOREIGN KEY (policy_id) REFERENCES policies(id);

CREATE TABLE agent_actions (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id       uuid REFERENCES shipments(id) ON DELETE CASCADE,
    trigger_event     text NOT NULL,        -- SCAN_INGESTED | RECONCILIATION_DONE | ...
    steps             jsonb NOT NULL DEFAULT '[]'::jsonb,   -- gather→update→notify→act→report
    outcome           text NOT NULL,        -- COMPLETED | AWAITING_HUMAN | BLOCKED_BY_POLICY
    reasoning_summary text,                 -- the LLM explains; it does not decide
    policy_id         uuid REFERENCES policies(id),
    audit_seq         bigint REFERENCES audit_log(seq),
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_actions_shipment_idx ON agent_actions (shipment_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Integrations (§6.2 `integrations` service) — payer webhook registry
-- ---------------------------------------------------------------------------

CREATE TABLE webhooks (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name         text NOT NULL,
    url          text NOT NULL,
    secret_hash  text NOT NULL,             -- HMAC secret digest; secret itself in the vault
    events       text[] NOT NULL DEFAULT '{}',
    active       boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE webhook_deliveries (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_id   uuid NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
    event        text NOT NULL,
    payload      jsonb NOT NULL,
    status_code  integer,
    attempts     integer NOT NULL DEFAULT 0,
    delivered_at timestamptz,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX webhook_deliveries_pending_idx
    ON webhook_deliveries (webhook_id, created_at) WHERE delivered_at IS NULL;
