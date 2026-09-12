package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.crypto.Verifier
import com.veritransit.server.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Instant

/**
 * Scan ingest (§8.3 `POST /v1/scans:batch`).
 *
 * Three properties matter more than throughput here:
 *
 *  * **Idempotency.** The device's outbox retries whenever the dock Wi-Fi
 *    flickers, so the same event arrives repeatedly. `client_event_id` is
 *    unique in the schema and every insert is `ON CONFLICT DO NOTHING`.
 *
 *  * **The server re-decides, from the label.** §6 says "the device decides in
 *    real time; the backend decides authoritatively" — so the server does not
 *    take the device's word for what was scanned. It re-verifies the Ed25519
 *    token against its own signing keys, checks the current `copy_no`, and
 *    derives the package and its shipment from the database. The device's
 *    `result` is an observation; where the two disagree the difference becomes
 *    a discrepancy — the device's verdict is never silently overwritten (§6).
 *
 *  * **The shipment is canonical, never claimed.** A scan of package P belongs
 *    to the shipment P is registered under, whatever `shipment_ref` the client
 *    attached. A mismatched claim cannot re-associate the event with another
 *    shipment — the label's binding check says which shipment the platform
 *    issued the box for, and that is what the row records.
 */
class ScanService(
    private val db: Database,
    private val audit: AuditLog,
    private val verifier: Verifier,
    private val listeners: MutableList<(ScanEvent) -> Unit> = mutableListOf(),
) {

    fun onScan(listener: (ScanEvent) -> Unit) { listeners += listener }

    fun ingest(events: List<ScanEvent>, deviceId: String?, officer: String?): ScanBatchResponse {
        val acks = db.transaction { conn ->
            events.map { ingestOne(conn, it, deviceId, officer) }
        }
        // Notify the live-ops feed only after the transaction commits, so a
        // rolled-back batch never shows up on the admin wall.
        events.forEach { e -> listeners.forEach { it(e) } }
        return ScanBatchResponse(acks, acks.count { it.accepted }, acks.count { !it.accepted })
    }

    /**
     * The ingest half of a wider caller's transaction (proof of delivery files
     * its scans and its certificate atomically). Commits nothing itself — the
     * caller owns the transaction and the post-commit notifications.
     */
    fun ingestWithin(conn: Connection, events: List<ScanEvent>, deviceId: String?, officer: String?): List<ScanAck> =
        events.map { ingestOne(conn, it, deviceId, officer) }

    private fun ingestOne(conn: Connection, e: ScanEvent, deviceId: String?, officer: String?): ScanAck {
        // The label is the identity source, not the payload field (§4.2 "the
        // QR is not the truth — the signature is"). A verified token outranks
        // the package code the device claimed.
        val claimed = e.labelToken?.let { LabelToken.parse(it) }
        val code = claimed?.packageCode ?: e.packageCode

        val known = conn.queryOne(
            """SELECT p.kind::text AS kind, p.status::text AS status, p.qty, p.parent_code,
                      s.ref AS ship_ref, s.status::text AS ship_status,
                      COALESCE(l.copy_no, 1) AS copy_no
                 FROM packages p JOIN shipments s ON s.id = p.shipment_id
                      LEFT JOIN labels l ON l.package_id = p.id AND l.superseded_at IS NULL
                WHERE p.package_code = ?""",
            code,
        ) {
            mapOf(
                "kind" to it.str("kind"), "status" to it.str("status"),
                "qty" to it.int("qty").toString(), "parent" to (it.strOrNull("parent_code") ?: ""),
                "ship" to it.str("ship_ref"), "ship_status" to it.str("ship_status"),
                "copy" to it.int("copy_no").toString(),
            )
        }

        // What this platform signed for the package — the verification
        // fallback for clients that did not relay the raw QR payload.
        val stored = known?.let {
            conn.queryOne(
                """SELECT l.payload FROM labels l JOIN packages p ON p.id = l.package_id
                    WHERE p.package_code = ? AND l.superseded_at IS NULL
                    ORDER BY l.copy_no DESC LIMIT 1""",
                code,
            ) { row -> row.strOrNull("payload") }
        }?.let { LabelToken.parse(it) }

        val token = claimed ?: stored
        val signatureValid = token != null && verifier.verifyToken(token)

        // The shipment the database says this package belongs to. Client-supplied
        // shipment refs are never authoritative for a known package.
        val canonicalShip: String? = known?.get("ship") ?: e.shipmentRef

        // Cross-device duplicate: the same carton already accepted on this
        // shipment and pass from a *different* device or officer.
        val duplicate = conn.queryOne(
            """SELECT count(*) AS n FROM scan_events
                WHERE package_code = ? AND kind = ?::scan_kind
                  AND client_event_id <> ?::uuid
                  AND result <> 'REJECTED'
                  AND (device_id IS DISTINCT FROM ?::uuid OR ?::uuid IS NULL)""",
            code, e.kind.name, e.clientEventId, deviceId, deviceId,
        ) { it.int("n") } ?: 0

        val serverReasons = buildList {
            // L1 again, at the only layer that matters for money: a scan with no
            // verifiable label is refused, exactly as the device refuses one.
            if (token == null || !signatureValid) add(ReasonCode.SIGNATURE_INVALID)
            // Not registered at all — regardless of what the label claims.
            if (known == null) add(ReasonCode.NOT_IN_MANIFEST)
            else if (token != null && signatureValid) {
                // L2 binding against the database, not against the device's claim.
                if (token.shipmentRef != known["ship"]) add(ReasonCode.WRONG_SHIPMENT)
                if (token.copyNo < (known["copy"]?.toIntOrNull() ?: 1)) add(ReasonCode.REPRINT_SUPERSEDED)
            }
            if (claimed != null && claimed.packageCode != e.packageCode) add(ReasonCode.QR_BARCODE_MISMATCH)
            if (duplicate > 0) add(ReasonCode.DUPLICATE_LABEL)
            // The device's own findings stand — the server adds to them, it does
            // not overrule a tamper flag it cannot see.
            addAll(e.reasons.filter { it !in listOf(ReasonCode.DUPLICATE_LABEL, ReasonCode.NOT_IN_MANIFEST) })
        }.distinct()

        val serverResult = when {
            serverReasons.any { it.rejects } -> ScanResult.REJECTED
            serverReasons.isNotEmpty() -> ScanResult.SUSPECT_REVIEW
            else -> ScanResult.VERIFIED
        }
        val conflict = serverResult != e.result

        val inserted = conn.update(
            """INSERT INTO scan_events (client_event_id, package_code, shipment_ref, device_id, officer_id,
                                        kind, result, reasons, ai_flags, evidence_uri, evidence_sha256,
                                        lat, lng, client_ts, source)
               VALUES (?::uuid, ?, ?, ?::uuid, (SELECT id FROM users WHERE name = ? LIMIT 1),
                       ?::scan_kind, ?::scan_result, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'DEVICE')
               ON CONFLICT (client_event_id) DO NOTHING""",
            e.clientEventId, code, canonicalShip, deviceId, officer,
            e.kind.name, serverResult.name,
            Jsonb(Rows.json.encodeToString(serverReasons)),
            Jsonb(Rows.json.encodeToString(e.aiFlags)),
            e.evidenceUri, e.evidenceSha256, e.lat, e.lng, e.clientTs,
        )

        // A replay is a success, not a failure — the event is already recorded.
        if (inserted == 0) {
            return ScanAck(e.clientEventId, accepted = true, serverResult = serverResult,
                serverReasons = serverReasons, conflict = false, duplicate = true)
        }

        // Only a verified label moves package state: a REJECTED scan must never
        // mark a carton loaded or received.
        if (known != null && serverResult != ScanResult.REJECTED) {
            applyStatus(conn, e, code, known)
        }

        if (conflict || serverResult != ScanResult.VERIFIED) {
            raiseDiscrepancy(conn, e, code, canonicalShip, serverResult, serverReasons, known)
        }

        audit.append(
            conn, officer ?: deviceId ?: "device", "SCAN_INGESTED",
            canonicalShip ?: code,
            buildJsonObject {
                put("package", code)
                put("kind", e.kind.name)
                put("result", serverResult.name)
                put("reasons", serverReasons.joinToString(",") { it.name })
                put("label_verified", signatureValid)
            },
            runCatching { Instant.parse(e.clientTs) }.getOrNull(),
        )

        return ScanAck(e.clientEventId, true, serverResult, serverReasons, conflict, false)
    }

    /**
     * §2.1 step 3 — "the platform treats either as loading its whole subtree".
     * Scanning a master onto the truck ticks its inner boxes as loaded; they
     * stay *pending receiver verification* until each one is scanned or counted
     * at the far end.
     */
    private fun applyStatus(conn: Connection, e: ScanEvent, code: String, known: Map<String, String>) {
        val newStatus = when (e.kind) {
            ScanKind.LOAD -> PackageStatus.LOADED
            ScanKind.RECEIVE -> PackageStatus.RECEIVED
            else -> return
        }
        conn.update(
            "UPDATE packages SET status = ?::package_status WHERE package_code = ?",
            newStatus.name, code,
        )
        if (known["kind"] != "UNIT" && e.kind == ScanKind.LOAD) {
            conn.update(
                """UPDATE packages SET status = ?::package_status
                    WHERE parent_code = ? AND status NOT IN ('MISSING','FLAGGED')""",
                newStatus.name, code,
            )
        }
    }

    private fun raiseDiscrepancy(
        conn: Connection, e: ScanEvent, code: String, ref: String?,
        serverResult: ScanResult, reasons: List<ReasonCode>, known: Map<String, String>?,
    ) {
        if (reasons.isEmpty() || ref == null) return
        val kind = reasons.first().name
        val severity = when {
            reasons.any { it.rejects } -> "HIGH"
            reasons.any { it in listOf(ReasonCode.DUPLICATE_LABEL, ReasonCode.QR_BARCODE_MISMATCH,
                    ReasonCode.INNER_SHORTAGE, ReasonCode.VISUAL_TAMPER, ReasonCode.REPRINT_SUPERSEDED) } -> "HIGH"
            else -> "MEDIUM"
        }
        conn.update(
            """INSERT INTO discrepancies (shipment_id, kind, package_code, severity, detail)
               SELECT id, ?, ?, ?, ? FROM shipments WHERE ref = ?""",
            kind, code, severity,
            Jsonb(Rows.json.encodeToString(
                mapOf(
                    "reasons" to reasons.joinToString(",") { it.name },
                    "device_result" to e.result.name,
                    "server_result" to serverResult.name,
                    "verified_shipment" to (known?.get("ship") ?: ""),
                    "claimed_shipment" to (e.shipmentRef ?: ""),
                    "evidence" to (e.evidenceUri ?: ""),
                )
            )),
            ref,
        )
        // FLAGGED is a shipment-level state, so the dispatch gate can refuse
        // release without anyone having to read the discrepancy queue first.
        conn.update(
            "UPDATE packages SET status = 'FLAGGED' WHERE package_code = ? AND ?::text = 'HIGH'",
            code, severity,
        )
    }

    fun recent(shipmentRef: String?, limit: Int = 100): List<ScanEvent> {
        val sql = buildString {
            append(
                """SELECT client_event_id::text AS client_event_id, package_code, shipment_ref,
                          kind::text AS kind, result::text AS result, reasons::text AS reasons,
                          evidence_uri, evidence_sha256, lat, lng, client_ts
                     FROM scan_events WHERE 1=1"""
            )
            if (shipmentRef != null) append(" AND shipment_ref = ?")
            append(" ORDER BY server_ts DESC LIMIT ?")
        }
        val params = listOfNotNull(shipmentRef, limit).toTypedArray()
        return db.query(sql, *params) {
            ScanEvent(
                clientEventId = it.str("client_event_id"),
                packageCode = it.str("package_code"),
                shipmentRef = it.strOrNull("shipment_ref"),
                kind = ScanKind.valueOf(it.str("kind")),
                result = ScanResult.valueOf(it.str("result")),
                reasons = runCatching {
                    Rows.json.decodeFromString<List<ReasonCode>>(it.str("reasons"))
                }.getOrDefault(emptyList()),
                evidenceUri = it.strOrNull("evidence_uri"),
                evidenceSha256 = it.strOrNull("evidence_sha256"),
                lat = it.dblOrNull("lat"), lng = it.dblOrNull("lng"),
                clientTs = it.iso("client_ts"),
            )
        }
    }
}
