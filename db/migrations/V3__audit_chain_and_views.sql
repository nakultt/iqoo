-- =============================================================================
-- VeriTransit — V3: tamper-evident audit chain + operational views
--
-- §3 check 9 ("can anyone secretly erase a mistake later?") is only real if the
-- database itself seals each entry to the previous one and refuses rewrites.
-- The views below are the read models the admin UI, the bot and the device
-- bootstrap all answer from, so those surfaces cannot drift apart.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Append-only hash chain
-- ---------------------------------------------------------------------------

-- Deterministic JSON serialisation: keys sorted, no insignificant whitespace.
-- jsonb already normalises internally, but its ::text rendering is not a
-- contract, so the exact preimage bytes are pinned here. Written in plpgsql
-- because it recurses (a SQL-language body cannot reference itself at creation).
CREATE OR REPLACE FUNCTION jsonb_canonical(p jsonb) RETURNS text
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    v text;
BEGIN
    CASE jsonb_typeof(p)
    WHEN 'object' THEN
        SELECT coalesce('{' || string_agg(to_json(k.key)::text || ':' || jsonb_canonical(p -> k.key),
                                          ',' ORDER BY k.key) || '}', '{}')
          INTO v FROM jsonb_object_keys(p) AS k(key);
        RETURN coalesce(v, '{}');
    WHEN 'array' THEN
        SELECT coalesce('[' || string_agg(jsonb_canonical(e.val), ',' ORDER BY e.ord) || ']', '[]')
          INTO v FROM jsonb_array_elements(p) WITH ORDINALITY AS e(val, ord);
        RETURN coalesce(v, '[]');
    ELSE
        RETURN p::text;
    END CASE;
END $$;

-- Canonical bytes for one entry. Kept in a function so the backend (Kotlin) can
-- reproduce the exact same preimage when it verifies a chain out-of-band.
CREATE OR REPLACE FUNCTION audit_preimage(
    p_seq bigint, p_at timestamptz, p_actor text,
    p_action text, p_subject text, p_payload jsonb, p_prev_hash text
) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT concat_ws('|',
        coalesce(p_prev_hash, 'GENESIS'),
        p_seq::text,
        to_char(p_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        p_actor, p_action, p_subject,
        jsonb_canonical(p_payload)
    );
$$;

CREATE OR REPLACE FUNCTION audit_log_seal() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_prev text;
BEGIN
    -- Serialise appenders so two concurrent inserts cannot claim the same link.
    PERFORM pg_advisory_xact_lock(hashtext('veritransit.audit_log'));

    SELECT hash INTO v_prev FROM audit_log ORDER BY seq DESC LIMIT 1;

    NEW.prev_hash := v_prev;
    NEW.hash := encode(
        digest(audit_preimage(NEW.seq, NEW.at, NEW.actor, NEW.action,
                              NEW.subject, NEW.payload, v_prev), 'sha256'),
        'hex');
    RETURN NEW;
END $$;

CREATE TRIGGER audit_log_seal_trg
    BEFORE INSERT ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_seal();

-- The chain is worthless if history can be edited, so the table is append-only
-- at the database level — not merely by convention in application code.
CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (attempted %)', TG_OP;
END $$;

CREATE TRIGGER audit_log_no_update_trg
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();

-- Walks the chain and reports the first broken link, if any. This is what the
-- admin UI's "tamper indicator" (§6.3 Audit page) calls.
CREATE OR REPLACE FUNCTION audit_verify_chain(p_from bigint DEFAULT NULL, p_to bigint DEFAULT NULL)
RETURNS TABLE (ok boolean, checked bigint, first_bad_seq bigint, detail text)
LANGUAGE plpgsql AS $$
DECLARE
    r          record;
    v_expect   text;
    v_prev     text := NULL;
    v_count    bigint := 0;
    v_first    boolean := true;
BEGIN
    FOR r IN
        SELECT * FROM audit_log
         WHERE (p_from IS NULL OR seq >= p_from)
           AND (p_to   IS NULL OR seq <= p_to)
         ORDER BY seq
    LOOP
        v_count := v_count + 1;

        -- The first row of a partial range inherits whatever it claims; a full
        -- scan must start from GENESIS.
        IF v_first THEN
            v_prev := r.prev_hash;
            v_first := false;
        ELSIF r.prev_hash IS DISTINCT FROM v_prev THEN
            RETURN QUERY SELECT false, v_count, r.seq,
                format('prev_hash mismatch: stored %s, expected %s', r.prev_hash, v_prev);
            RETURN;
        END IF;

        v_expect := encode(
            digest(audit_preimage(r.seq, r.at, r.actor, r.action, r.subject, r.payload, r.prev_hash), 'sha256'),
            'hex');

        IF v_expect <> r.hash THEN
            RETURN QUERY SELECT false, v_count, r.seq,
                format('content hash mismatch at seq %s', r.seq);
            RETURN;
        END IF;

        v_prev := r.hash;
    END LOOP;

    RETURN QUERY SELECT true, v_count, NULL::bigint, 'chain intact';
END $$;

-- Convenience appender used by seeds, the backend and the agent alike.
CREATE OR REPLACE FUNCTION audit_append(
    p_actor text, p_action text, p_subject text,
    p_payload jsonb DEFAULT '{}'::jsonb, p_at timestamptz DEFAULT now()
) RETURNS bigint
LANGUAGE sql AS $$
    INSERT INTO audit_log (at, actor, action, subject, payload, hash)
    VALUES (p_at, p_actor, p_action, p_subject, p_payload, 'pending')
    RETURNING seq;
$$;

-- ---------------------------------------------------------------------------
-- Read models
-- ---------------------------------------------------------------------------

-- §7.3 load reconciliation: scanned set vs expected set, at the top level.
CREATE OR REPLACE VIEW v_shipment_completeness AS
SELECT
    s.id                AS shipment_id,
    s.tenant_id,
    s.ref               AS shipment_ref,
    s.status,
    s.expected_count,
    count(*) FILTER (WHERE p.kind <> 'UNIT' OR p.parent_code IS NULL)        AS top_level_packages,
    count(*) FILTER (WHERE p.status IN ('LOADED','RECEIVED')
                       AND (p.kind <> 'UNIT' OR p.parent_code IS NULL))      AS accounted,
    count(*) FILTER (WHERE p.status = 'MISSING')                             AS missing,
    count(*) FILTER (WHERE p.status = 'FLAGGED')                             AS flagged,
    count(*) FILTER (WHERE p.kind = 'UNIT' AND p.parent_code IS NOT NULL)    AS inner_units,
    count(*) FILTER (WHERE p.kind = 'UNIT' AND p.parent_code IS NOT NULL
                       AND p.status = 'RECEIVED')                            AS inner_verified,
    round(100.0 * count(*) FILTER (WHERE p.status IN ('LOADED','RECEIVED')
                                     AND (p.kind <> 'UNIT' OR p.parent_code IS NULL))
          / nullif(s.expected_count, 0), 1)                                  AS pct_complete
FROM shipments s
LEFT JOIN packages p ON p.shipment_id = s.id
GROUP BY s.id;

-- §4.4: a master closes as VERIFIED only when every declared inner resolves.
CREATE OR REPLACE VIEW v_master_boxes AS
SELECT
    m.shipment_id,
    m.package_code                                              AS master_code,
    m.contents,
    m.qty                                                       AS declared_inners,
    count(c.id)                                                 AS registered_inners,
    count(c.id) FILTER (WHERE c.status = 'RECEIVED')            AS verified_inners,
    count(c.id) FILTER (WHERE c.status = 'MISSING')             AS missing_inners,
    m.status                                                    AS master_status,
    (count(c.id) FILTER (WHERE c.status = 'RECEIVED') = m.qty)  AS fully_verified
FROM packages m
LEFT JOIN packages c ON c.parent_code = m.package_code
WHERE m.kind IN ('MASTER', 'PALLET')
GROUP BY m.id;

-- The row the admin "Shipments" dashboard and the Telegram `status` command
-- both render: completeness + risk + finance + open discrepancies in one place.
CREATE OR REPLACE VIEW v_shipment_dashboard AS
SELECT
    s.ref                       AS shipment_ref,
    s.status                    AS shipment_status,
    s.vehicle,
    os.name                     AS origin,
    ds.name                     AS destination,
    sup.name                    AS supplier,
    buy.name                    AS buyer,
    c.expected_count,
    c.accounted,
    c.missing,
    c.pct_complete,
    c.inner_units,
    c.inner_verified,
    r.score                     AS risk_score,
    r.band                      AS risk_band,
    f.status                    AS finance_status,
    f.currency,
    f.order_value,
    f.released_value,
    f.held_value,
    (SELECT count(*) FROM discrepancies d
      WHERE d.shipment_id = s.id AND d.resolved_at IS NULL) AS open_discrepancies,
    s.dispatched_at,
    s.received_at
FROM shipments s
JOIN v_shipment_completeness c ON c.shipment_id = s.id
LEFT JOIN sites os  ON os.id = s.origin_site
LEFT JOIN sites ds  ON ds.id = s.dest_site
LEFT JOIN finance_terms f ON f.shipment_id = s.id
LEFT JOIN LATERAL (
    SELECT score, band FROM risk_scores
     WHERE subject_kind = 'shipment' AND subject_id = s.ref
     ORDER BY computed_at DESC LIMIT 1
) r ON true
LEFT JOIN LATERAL (
    SELECT p.name FROM shipment_parties sp JOIN parties p ON p.id = sp.party_id
     WHERE sp.shipment_id = s.id AND sp.role = 'SUPPLIER' LIMIT 1
) sup ON true
LEFT JOIN LATERAL (
    SELECT p.name FROM shipment_parties sp JOIN parties p ON p.id = sp.party_id
     WHERE sp.shipment_id = s.id AND sp.role = 'BUYER' LIMIT 1
) buy ON true;

-- §6.3 discrepancy queue.
CREATE OR REPLACE VIEW v_open_discrepancies AS
SELECT
    d.id, s.ref AS shipment_ref, d.kind, d.package_code, d.severity,
    d.detail, d.detected_at,
    f.status AS finance_status, f.held_value
FROM discrepancies d
JOIN shipments s ON s.id = d.shipment_id
LEFT JOIN finance_terms f ON f.shipment_id = s.id
WHERE d.resolved_at IS NULL
ORDER BY
    CASE d.severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
    d.detected_at DESC;

-- §5.1 four-way match: latest run per shipment, mismatches expanded one per row.
CREATE OR REPLACE VIEW v_four_way_match AS
SELECT
    s.ref                            AS shipment_ref,
    rr.created_at                    AS run_at,
    rr.status                        AS run_status,
    rr.held_value,
    m ->> 'code'                     AS mismatch_code,
    (m ->> 'line_no')::int           AS line_no,
    m ->> 'sku'                      AS sku,
    (m ->> 'ordered_qty')::numeric   AS ordered_qty,
    (m ->> 'invoiced_qty')::numeric  AS invoiced_qty,
    (m ->> 'declared_qty')::numeric  AS declared_qty,
    (m ->> 'physical_qty')::numeric  AS physical_qty,
    (m ->> 'delta_value')::numeric   AS delta_value
FROM shipments s
JOIN LATERAL (
    SELECT * FROM reconciliation_runs r
     WHERE r.shipment_id = s.id ORDER BY r.created_at DESC LIMIT 1
) rr ON true
LEFT JOIN LATERAL jsonb_array_elements(rr.mismatches) AS m ON true;

-- §8.3 GET /v1/sync/bootstrap — everything a device caches for offline scanning.
CREATE OR REPLACE VIEW v_device_bootstrap AS
SELECT
    s.ref              AS shipment_ref,
    s.status           AS shipment_status,
    s.vehicle,
    s.expected_count,
    p.package_code,
    p.kind,
    p.parent_code,
    p.contents,
    p.qty,
    p.sku,
    p.po_line_no,
    p.status           AS package_status,
    l.payload          AS label_payload,
    l.signature        AS label_signature,
    l.copy_no,
    l.key_id
FROM shipments s
JOIN packages p ON p.shipment_id = s.id
LEFT JOIN labels l ON l.package_id = p.id AND l.superseded_at IS NULL
WHERE s.status IN ('OPEN', 'LOADING', 'DISPATCHED');
