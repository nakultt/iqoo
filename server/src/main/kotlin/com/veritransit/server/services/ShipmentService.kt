package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.db.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shipments, packages, and the two questions the warehouse actually asks:
 * "is every box on the list here?" (§3 check 6) and "what should this phone
 * cache before the shift?" (§8.3 bootstrap).
 */
class ShipmentService(private val db: Database, private val audit: AuditLog) {

    private val dashboardSelect = """
        SELECT shipment_ref AS ref, shipment_status AS status, vehicle, origin, destination,
               supplier, buyer, expected_count, dispatched_at, received_at
        FROM v_shipment_dashboard
    """.trimIndent()

    fun list(): List<Shipment> = db.query("$dashboardSelect ORDER BY shipment_ref", map = Rows::shipment)

    fun get(ref: String): Shipment? =
        db.queryOne("$dashboardSelect WHERE shipment_ref = ?", ref, map = Rows::shipment)

    fun packages(ref: String): List<PackageRecord> = db.query(
        """SELECT p.package_code, s.ref AS shipment_ref, p.kind, p.parent_code, p.contents,
                  p.sku, p.hsn, p.qty, p.po_line_no, p.status AS package_status,
                  l.payload AS label_payload, l.copy_no
             FROM packages p
             JOIN shipments s ON s.id = p.shipment_id
             LEFT JOIN labels l ON l.package_id = p.id AND l.superseded_at IS NULL
            WHERE s.ref = ?
            ORDER BY p.kind DESC, p.package_code""",
        ref, map = Rows::packageRecord,
    )

    fun create(req: CreateShipmentRequest, actor: String): Shipment {
        val ref = req.ref ?: nextRef()
        db.transaction { conn ->
            // Single-tenant pilot (§6.2): the schema carries tenant_id for the
            // production shape, but this deployment has one tenant and no
            // principal-scoped queries yet — every row lands in it explicitly.
            val shipmentId = conn.queryOne(
                """INSERT INTO shipments (tenant_id, ref, vehicle, origin_site, dest_site, status, created_by)
                   SELECT t.id, ?, ?,
                          (SELECT id FROM sites WHERE name = ? OR name ILIKE ? LIMIT 1),
                          (SELECT id FROM sites WHERE name = ? OR name ILIKE ? LIMIT 1),
                          'OPEN'::shipment_status,
                          (SELECT id FROM users WHERE name = ? LIMIT 1)
                     FROM tenants t ORDER BY t.created_at LIMIT 1
                   RETURNING id""",
                ref, req.vehicle, req.originSite, req.originSite, req.destSite, req.destSite, actor,
            ) { it.getString("id") } ?: error("shipment insert returned no id")

            // Parties are matched by GSTIN where given, else by name (§5.1).
            listOfNotNull(
                req.supplier?.let { it to "SUPPLIER" },
                req.buyer?.let { it to "BUYER" },
                req.transporter?.let { it to "TRANSPORTER" },
            ).forEach { (who, role) ->
                conn.update(
                    """INSERT INTO shipment_parties (shipment_id, party_id, role)
                       SELECT ?::uuid, id, ?::party_kind FROM parties
                        WHERE gstin = ? OR name ILIKE ? LIMIT 1
                       ON CONFLICT DO NOTHING""",
                    shipmentId, role, who, who,
                )
            }

            audit.append(conn, actor, "SHIPMENT_CREATED", ref, buildJsonObject {
                put("vehicle", req.vehicle ?: "")
                put("po", req.poNo ?: "")
            })
        }
        return get(ref) ?: error("shipment $ref vanished after creation")
    }

    /**
     * §7.3 — the release gate. Missing and extra are computed from the package
     * registry rather than from scan counts, because a scan that never synced
     * must not make a box look present.
     */
    fun report(ref: String): ShipmentReport? {
        val shipment = get(ref) ?: return null
        val counts = db.queryOne(
            """SELECT expected_count, accounted, missing, flagged, inner_units, inner_verified
                 FROM v_shipment_completeness WHERE shipment_ref = ?""", ref,
        ) {
            listOf(
                it.int("expected_count"), it.int("accounted"), it.int("missing"),
                it.int("flagged"), it.int("inner_units"), it.int("inner_verified"),
            )
        } ?: List(6) { 0 }

        val missing = db.query(
            """SELECT p.package_code FROM packages p JOIN shipments s ON s.id = p.shipment_id
                WHERE s.ref = ? AND p.status = 'MISSING' ORDER BY p.package_code""", ref,
        ) { it.str("package_code") }

        val flagged = db.query(
            """SELECT p.package_code FROM packages p JOIN shipments s ON s.id = p.shipment_id
                WHERE s.ref = ? AND p.status = 'FLAGGED' ORDER BY p.package_code""", ref,
        ) { it.str("package_code") }

        // A box that was scanned onto this shipment but was never registered on
        // it — the "extra that slipped in" of §3 check 6.
        val extra = db.query(
            """SELECT DISTINCT e.package_code FROM scan_events e
                WHERE e.shipment_ref = ?
                  AND NOT EXISTS (SELECT 1 FROM packages p JOIN shipments s ON s.id = p.shipment_id
                                   WHERE s.ref = e.shipment_ref AND p.package_code = e.package_code)
                ORDER BY 1""", ref,
        ) { it.str("package_code") }

        return ShipmentReport(
            shipment = shipment,
            expectedCount = counts[0], accounted = counts[1],
            missing = missing, extra = extra, flagged = flagged,
            innerUnits = counts[4], innerVerified = counts[5],
            discrepancies = discrepancies(ref),
            risk = latestRisk("shipment", ref),
            finance = finance(ref),
            complete = counts[1] >= counts[0] && missing.isEmpty() && extra.isEmpty() && flagged.isEmpty(),
        )
    }

    fun discrepancies(ref: String? = null, openOnly: Boolean = true): List<Discrepancy> {
        val sql = buildString {
            append(
                """SELECT d.id::text AS id, s.ref AS shipment_ref, d.kind, d.package_code,
                          d.severity, d.detail::text AS detail, d.detected_at, d.resolved_at, d.resolution
                     FROM discrepancies d JOIN shipments s ON s.id = d.shipment_id WHERE 1=1"""
            )
            if (ref != null) append(" AND s.ref = ?")
            if (openOnly) append(" AND d.resolved_at IS NULL")
            append(" ORDER BY CASE d.severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, d.detected_at DESC")
        }
        val params = listOfNotNull(ref).toTypedArray()
        return db.query(sql, *params, map = Rows::discrepancy)
    }

    fun latestRisk(kind: String, subject: String): RiskScore? = db.queryOne(
        """SELECT subject_kind, subject_id, score, band, factors::text AS factors, computed_at
             FROM risk_scores WHERE subject_kind = ?::risk_subject AND subject_id = ?
            ORDER BY computed_at DESC LIMIT 1""",
        kind, subject, map = Rows::riskScore,
    )

    fun finance(ref: String): FinanceState? = db.queryOne(
        """SELECT s.ref AS shipment_ref, f.currency, f.order_value, f.status,
                  f.released_value, f.held_value, f.terms::text AS terms
             FROM finance_terms f JOIN shipments s ON s.id = f.shipment_id
            WHERE s.ref = ?""",
        ref, map = Rows::financeState,
    )

    fun documents(ref: String): List<ShipmentDocument> = db.query(
        """SELECT d.id::text AS id, s.ref AS shipment_ref, d.kind, d.doc_no,
                  d.doc_date::text AS doc_date, d.fact::text AS fact, d.source_uri,
                  d.read_by::text AS read_by, d.confidence, cu.name AS confirmed_by
             FROM documents d JOIN shipments s ON s.id = d.shipment_id
                  LEFT JOIN users cu ON cu.id = d.confirmed_by
            WHERE s.ref = ? ORDER BY d.kind, d.doc_no""",
        ref, map = Rows::document,
    )

    /**
     * §8.3 bootstrap. Scoped to shipments that are still in play — a device has
     * no use for last month's dispatches and every cached row costs sync time
     * on dock Wi-Fi.
     */
    fun bootstrap(site: String?, publicKeys: List<PublicKeyEntry>): BootstrapResponse {
        val refs = db.query(
            """SELECT s.ref FROM shipments s
                LEFT JOIN sites o ON o.id = s.origin_site
                LEFT JOIN sites d ON d.id = s.dest_site
                WHERE s.status IN ('OPEN','LOADING','DISPATCHED')
                  AND (?::text IS NULL OR o.name = ? OR d.name = ?)
                ORDER BY s.created_at DESC LIMIT 50""",
            site, site, site,
        ) { it.str("ref") }

        val shipments = refs.mapNotNull { get(it) }
        val packages = refs.flatMap { packages(it) }
        val docs = refs.flatMap { documents(it) }
        val risk = refs.mapNotNull { latestRisk("shipment", it) }
        val finance = refs.mapNotNull { finance(it) }

        return BootstrapResponse(
            serverTime = java.time.Instant.now().toString(),
            publicKeys = publicKeys,
            shipments = shipments, packages = packages, documents = docs,
            risk = risk, finance = finance,
            frictionBands = frictionBands(),
        )
    }

    /** §5.3 — the band policy the device reads to decide how deep to verify. */
    fun frictionBands(): Map<String, FrictionRule> = db.queryOne(
        "SELECT rules::text AS rules FROM policies WHERE kind = 'friction_band' AND active LIMIT 1",
    ) { it.str("rules") }?.let { raw ->
        runCatching {
            Rows.json.decodeFromString<Map<String, FrictionRule>>(raw)
        }.getOrDefault(defaultBands())
    } ?: defaultBands()

    private fun defaultBands() = mapOf(
        "LOW" to FrictionRule("spot", false, "master_scan_only"),
        "MEDIUM" to FrictionRule("every_scan", false, "open_and_verify"),
        "HIGH" to FrictionRule("every_scan", true, "open_and_verify"),
    )

    fun setStatus(ref: String, status: ShipmentStatus, actor: String) {
        db.transaction { conn ->
            conn.update(
                "UPDATE shipments SET status = ?::shipment_status, " +
                    "dispatched_at = CASE WHEN ?::text = 'DISPATCHED' THEN now() ELSE dispatched_at END, " +
                    "received_at = CASE WHEN ?::text = 'RECEIVED' THEN now() ELSE received_at END " +
                    "WHERE ref = ?",
                status.name, status.name, status.name, ref,
            )
            audit.append(conn, actor, "SHIPMENT_${status.name}", ref, buildJsonObject {
                put("status", status.name)
            })
        }
    }

    private fun nextRef(): String {
        val year = java.time.Year.now().value
        val seq = db.queryOne(
            "SELECT COALESCE(MAX(NULLIF(regexp_replace(ref, '^SHP-\\d+-', ''), '')::int), 90000) + 1 AS n " +
                "FROM shipments WHERE ref LIKE ?", "SHP-$year-%",
        ) { it.int("n") } ?: 90001
        return "SHP-$year-%06d".format(seq)
    }
}
