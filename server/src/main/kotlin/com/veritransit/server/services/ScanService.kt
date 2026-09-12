package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Instant

/**
 * Scan ingest (§8.3 `POST /v1/scans:batch`).
 *
 * Two properties matter more than throughput here:
 *
 *  * **Idempotency.** The device's outbox retries whenever the dock Wi-Fi
 *    flickers, so the same event arrives repeatedly. `client_event_id` is
 *    unique in the schema and every insert is `ON CONFLICT DO NOTHING`.
 *  * **The server re-decides.** A phone can only see its own session, so it
 *    cannot know that another device scanned the same carton (§3 check 8).
 *    The server recomputes the verdict over all devices and returns it. Where
 *    the two disagree the difference becomes a discrepancy — the device's
 *    verdict is never silently overwritten (§6).
 */
class ScanService(
    private val db: Database,
    private val audit: AuditLog,
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

    private fun ingestOne(conn: Connection, e: ScanEvent, deviceId: String?, officer: String?): ScanAck {
        val known = conn.queryOne(
            """SELECT p.kind::text AS kind, p.status::text AS status, p.qty, p.parent_code,
                      s.ref AS ship_ref, s.status::text AS ship_status
                 FROM packages p JOIN shipments s ON s.id = p.shipment_id
                WHERE p.package_code = ?""",
            e.packageCode,
        ) {
            mapOf(
                "kind" to it.str("kind"), "status" to it.str("status"),
                "qty" to it.int("qty").toString(), "parent" to (it.strOrNull("parent_code") ?: ""),
                "ship" to it.str("ship_ref"), "ship_status" to it.str("ship_status"),
            )
        }

        // Cross-device duplicate: the same carton already accepted on this
        // shipment and pass from a *different* device or officer.
        val duplicate = conn.queryOne(
            """SELECT count(*) AS n FROM scan_events
                WHERE package_code = ? AND kind = ?::scan_kind
                  AND client_event_id <> ?::uuid
                  AND result <> 'REJECTED'
                  AND (device_id IS DISTINCT FROM ?::uuid OR ?::uuid IS NULL)""",
            e.packageCode, e.kind.name, e.clientEventId, deviceId, deviceId,
        ) { it.int("n") } ?: 0

        val serverReasons = buildList {
            if (known == null) add(ReasonCode.NOT_IN_MANIFEST)
            else {
                if (e.shipmentRef != null && known["ship"] != e.shipmentRef) add(ReasonCode.WRONG_SHIPMENT)
            }
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
            e.clientEventId, e.packageCode, e.shipmentRef ?: known?.get("ship"), deviceId, officer,
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

        if (known != null && serverResult != ScanResult.REJECTED) {
            applyStatus(conn, e, known)
        }

        if (conflict || serverResult != ScanResult.VERIFIED) {
            raiseDiscrepancy(conn, e, serverReasons, e.shipmentRef ?: known?.get("ship"))
        }

        audit.append(
            conn, officer ?: deviceId ?: "device", "SCAN_INGESTED",
            e.shipmentRef ?: known?.get("ship") ?: e.packageCode,
            buildJsonObject {
                put("package", e.packageCode)
                put("kind", e.kind.name)
                put("result", serverResult.name)
                put("reasons", serverReasons.joinToString(",") { it.name })
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
    private fun applyStatus(conn: Connection, e: ScanEvent, known: Map<String, String>) {
        val newStatus = when (e.kind) {
            ScanKind.LOAD -> PackageStatus.LOADED
            ScanKind.RECEIVE -> PackageStatus.RECEIVED
            else -> return
        }
        conn.update(
            "UPDATE packages SET status = ?::package_status WHERE package_code = ?",
            newStatus.name, e.packageCode,
        )
        if (known["kind"] != "UNIT" && e.kind == ScanKind.LOAD) {
            conn.update(
                """UPDATE packages SET status = ?::package_status
                    WHERE parent_code = ? AND status NOT IN ('MISSING','FLAGGED')""",
                newStatus.name, e.packageCode,
            )
        }
    }

    private fun raiseDiscrepancy(conn: Connection, e: ScanEvent, reasons: List<ReasonCode>, ref: String?) {
        if (reasons.isEmpty() || ref == null) return
        val kind = reasons.first().name
        val severity = when {
            reasons.any { it.rejects } -> "HIGH"
            reasons.any { it in listOf(ReasonCode.DUPLICATE_LABEL, ReasonCode.QR_BARCODE_MISMATCH,
                    ReasonCode.INNER_SHORTAGE, ReasonCode.VISUAL_TAMPER) } -> "HIGH"
            else -> "MEDIUM"
        }
        conn.update(
            """INSERT INTO discrepancies (shipment_id, kind, package_code, severity, detail)
               SELECT id, ?, ?, ?, ? FROM shipments WHERE ref = ?""",
            kind, e.packageCode, severity,
            Jsonb(Rows.json.encodeToString(
                mapOf(
                    "reasons" to reasons.joinToString(",") { it.name },
                    "device_result" to e.result.name,
                    "evidence" to (e.evidenceUri ?: ""),
                )
            )),
            ref,
        )
        // FLAGGED is a shipment-level state, so the dispatch gate can refuse
        // release without anyone having to read the discrepancy queue first.
        conn.update(
            "UPDATE packages SET status = 'FLAGGED' WHERE package_code = ? AND ?::text = 'HIGH'",
            e.packageCode, severity,
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
