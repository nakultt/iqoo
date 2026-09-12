package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.db.*
import kotlinx.serialization.encodeToString

/**
 * §5.3 — risk scoring, rules first.
 *
 * Rules rather than ML is a deliberate choice, not a shortcut: a score that
 * decides whether a truck gets opened and whether money is held has to be
 * defensible to an operations manager and to a dispute officer. Every factor
 * here carries its own weight and a sentence of explanation, and the score is
 * simply their sum — so "why is this HIGH?" always has a complete answer.
 *
 * ML refines these weights once there is real history to learn from (Phase 5);
 * the shape of the output does not change when it does.
 */
class RiskEngine(private val db: Database) {

    fun scoreShipment(ref: String): RiskScore {
        val factors = mutableListOf<RiskFactor>()

        // --- what this shipment's own paperwork says -------------------------
        val recon = db.queryOne(
            """SELECT r.mismatches::text AS mismatches, r.held_value
                 FROM reconciliation_runs r JOIN shipments s ON s.id = r.shipment_id
                WHERE s.ref = ? ORDER BY r.created_at DESC LIMIT 1""", ref,
        ) { it.str("mismatches") to it.dbl("held_value") }

        recon?.let { (raw, held) ->
            val mismatches = runCatching {
                Rows.json.decodeFromString<List<Mismatch>>(raw)
            }.getOrDefault(emptyList())

            mismatches.groupBy { it.code }.forEach { (code, group) ->
                val weight = when (code) {
                    MismatchCode.QTY_MISMATCH -> 26
                    MismatchCode.VALUE_MISMATCH -> 22
                    MismatchCode.INNER_SHORTAGE -> 18
                    MismatchCode.PARTY_MISMATCH -> 30
                    MismatchCode.PRODUCT_MISMATCH -> 24
                    MismatchCode.DOC_MISSING -> 14
                    MismatchCode.DOC_DUPLICATE -> 12
                }
                factors += RiskFactor(
                    code.name.lowercase(), weight,
                    group.firstOrNull()?.detail ?: "${group.size} ${code.name} finding(s)",
                )
            }
            if (held > 0) {
                factors += RiskFactor("held_value", 6, "₹${"%.0f".format(held)} withheld on this shipment")
            }
        }

        // --- what the scans say ----------------------------------------------
        val incidents = db.query(
            """SELECT unnest(ARRAY(SELECT jsonb_array_elements_text(reasons))) AS reason, count(*) AS n
                 FROM scan_events WHERE shipment_ref = ? AND result <> 'VERIFIED'
                GROUP BY 1""", ref,
        ) { it.str("reason") to it.int("n") }

        incidents.forEach { (reason, n) ->
            val weight = when (reason) {
                "DUPLICATE_LABEL" -> 24
                "QR_BARCODE_MISMATCH" -> 22
                "SIGNATURE_INVALID" -> 40
                "VISUAL_TAMPER" -> 20
                "INNER_SHORTAGE" -> 18
                "WRONG_SHIPMENT" -> 12
                else -> 8
            }
            factors += RiskFactor(reason.lowercase(), weight, "$n ${reason.lowercase().replace('_', ' ')} event(s) at scan")
        }

        // --- who is involved ---------------------------------------------------
        val supplier = db.queryOne(
            """SELECT p.id::text AS id, p.name FROM shipment_parties sp
                 JOIN parties p ON p.id = sp.party_id JOIN shipments s ON s.id = sp.shipment_id
                WHERE s.ref = ? AND sp.role = 'SUPPLIER' LIMIT 1""", ref,
        ) { it.str("id") to it.str("name") }

        supplier?.let { (id, name) ->
            val history = partyHistory(id)
            if (history.total > 0) {
                val rate = history.flagged.toDouble() / history.total
                val weight = (rate * 30).toInt()
                factors += RiskFactor(
                    "party_history", weight,
                    "$name: ${history.flagged} of ${history.total} shipments flagged in 90 days",
                )
            }
        }

        // --- value band --------------------------------------------------------
        db.queryOne(
            """SELECT f.order_value FROM finance_terms f JOIN shipments s ON s.id = f.shipment_id
                WHERE s.ref = ?""", ref,
        ) { it.dbl("order_value") }?.let { value ->
            val weight = when {
                value >= 1_000_000 -> 12
                value >= 200_000 -> 8
                else -> 4
            }
            factors += RiskFactor("value_band", weight,
                "order value ₹${"%.2f".format(value / 100_000)}L${if (value > 200_000) " — above the ₹2L auto-release ceiling" else ""}")
        }

        if (factors.isEmpty()) {
            factors += RiskFactor("clean_record", 0, "no mismatches, incidents or adverse history")
        }

        // One physical fact must count once. A short carton surfaces twice —
        // as an INNER_SHORTAGE mismatch in reconciliation and as the scan event
        // that found it — and summing both would inflate the score with no new
        // information, which is precisely the kind of arithmetic that makes a
        // score indefensible to the operations manager it is meant to convince.
        val deduped = factors.groupBy { it.factor }.map { (_, group) -> group.maxBy { it.weight } }

        val score = deduped.sumOf { it.weight }.coerceIn(0, 100)
        // §5.3: the top three contributors are what any surface must show, so
        // they lead the list rather than being buried in insertion order.
        return RiskScore("shipment", ref, score, RiskBands.of(score), deduped.sortedByDescending { it.weight })
    }

    fun scoreParty(partyId: String, name: String): RiskScore {
        val h = partyHistory(partyId)
        val factors = mutableListOf<RiskFactor>()

        if (h.total == 0) {
            factors += RiskFactor("no_history", 20, "$name: no completed shipments yet — unproven")
        } else {
            val rate = h.flagged.toDouble() / h.total
            factors += RiskFactor("mismatch_rate", (rate * 34).toInt(),
                "${h.flagged} of ${h.total} shipments flagged")
            if (h.duplicates > 0) {
                factors += RiskFactor("duplicate_label", minOf(h.duplicates * 13, 26),
                    "${h.duplicates} duplicate-label event(s) in 30 days")
            }
            if (h.heldValue > 0) {
                factors += RiskFactor("held_value", 10,
                    "₹${"%.0f".format(h.heldValue)} currently withheld across shipments")
            }
            if (h.overrides > 0) {
                factors += RiskFactor("override_frequency", minOf(h.overrides * 4, 12),
                    "${h.overrides} SUSPECT verdict(s) overridden by an officer")
            }
        }

        val score = factors.sumOf { it.weight }.coerceIn(0, 100)
        return RiskScore("party", partyId, score, RiskBands.of(score), factors.sortedByDescending { it.weight })
    }

    fun store(score: RiskScore) {
        db.update(
            """INSERT INTO risk_scores (subject_kind, subject_id, score, band, factors)
               VALUES (?::risk_subject, ?, ?, ?::risk_band, ?)""",
            score.subjectKind, score.subjectId, score.score, score.band.name,
            Jsonb(Rows.json.encodeToString(score.factors)),
        )
    }

    fun recomputeAndStore(ref: String): RiskScore = scoreShipment(ref).also(::store)

    fun latest(kind: String, subject: String): RiskScore? = db.queryOne(
        """SELECT subject_kind, subject_id, score, band, factors::text AS factors, computed_at
             FROM risk_scores WHERE subject_kind = ?::risk_subject AND subject_id = ?
            ORDER BY computed_at DESC LIMIT 1""",
        kind, subject, map = Rows::riskScore,
    )

    fun leaderboard(): List<RiskScore> = db.query(
        """SELECT DISTINCT ON (subject_id) subject_kind, subject_id, score, band,
                  factors::text AS factors, computed_at
             FROM risk_scores WHERE subject_kind = 'party'
            ORDER BY subject_id, computed_at DESC""",
        map = Rows::riskScore,
    ).sortedByDescending { it.score }

    // ------------------------------------------------------------- history

    private data class History(
        val total: Int, val flagged: Int, val duplicates: Int,
        val heldValue: Double, val overrides: Int,
    )

    private fun partyHistory(partyId: String): History = db.queryOne(
        """WITH ship AS (
               SELECT s.id, s.ref, s.status FROM shipments s
                 JOIN shipment_parties sp ON sp.shipment_id = s.id
                WHERE sp.party_id = ?::uuid AND s.created_at > now() - interval '90 days')
           SELECT (SELECT count(*) FROM ship) AS total,
                  (SELECT count(*) FROM ship WHERE status = 'FLAGGED') AS flagged,
                  (SELECT count(*) FROM scan_events e
                     WHERE e.shipment_ref IN (SELECT ref FROM ship)
                       AND e.reasons @> '["DUPLICATE_LABEL"]'::jsonb) AS duplicates,
                  (SELECT COALESCE(SUM(f.held_value), 0) FROM finance_terms f
                     WHERE f.shipment_id IN (SELECT id FROM ship)) AS held,
                  (SELECT count(*) FROM discrepancies d
                     WHERE d.shipment_id IN (SELECT id FROM ship)
                       AND d.resolution = 'OVERRIDDEN') AS overrides""",
        partyId,
    ) {
        History(it.int("total"), it.int("flagged"), it.int("duplicates"), it.dbl("held"), it.int("overrides"))
    } ?: History(0, 0, 0, 0.0, 0)
}
