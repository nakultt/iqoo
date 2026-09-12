package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.crypto.Signer
import com.veritransit.server.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * §5.4 — the proof-of-delivery certificate.
 *
 * The evidence is hashed *on the device* before upload, so the certificate can
 * attest provenance even though the photos crossed an untrusted network. This
 * service assembles those hashes with the delivery events and the four-way
 * match result as it stood at delivery, then signs the whole body.
 *
 * What makes it useful in a dispute is precisely what it does *not* claim: it
 * certifies that specific evidence with specific hashes existed at a specific
 * time and place, not that the goods were correct.
 */
class PodService(
    private val db: Database,
    private val signer: Signer,
    private val audit: AuditLog,
    private val matcher: FourWayMatcher,
    private val scans: ScanService,
) {

    fun submit(submission: PodSubmission, officer: String?): PodCertificate = db.transaction { conn ->
        // The delivery scans go through the normal ingest logic — a PoD scan is
        // a first-class scan event, not a separate species of record — but they
        // land inside *this* transaction: a certificate that fails to commit
        // must not leave half-committed scans behind, and vice versa.
        if (submission.scans.isNotEmpty()) {
            scans.ingestWithin(conn, submission.scans, deviceId = null, officer = officer)
        }

        val ref = submission.shipmentRef
        val match = matcher.latest(ref) ?: matcher.run(ref, atReceipt = true)

        val counts = conn.queryOne(
            """SELECT expected_count, accounted, inner_units, inner_verified
                 FROM v_shipment_completeness WHERE shipment_ref = ?""", ref,
        ) {
            listOf(it.int("expected_count"), it.int("accounted"), it.int("inner_units"), it.int("inner_verified"))
        } ?: List(4) { 0 }

        val events = buildJsonObject {
            put("masters_expected", counts[0])
            put("masters_accounted", counts[1])
            put("inners_declared", counts[2])
            put("inners_verified", counts[3])
            put("receiver", submission.receiverName)
            put("signature_uri", submission.signatureUri ?: "")
            put("delivered_at", submission.deliveredAt)
            put("lat", submission.lat ?: 0.0)
            put("lng", submission.lng ?: 0.0)
            put("officer", officer ?: "")
        }

        val matchResult = buildJsonObject {
            put("status", match.status.name)
            put("held_value", match.heldValue)
            put("mismatches", match.mismatches.joinToString(",") { "${it.code}:${it.deltaValue}" })
        }

        // The signed body is exactly what a verifier will reconstruct, so its
        // field order is fixed rather than incidental.
        val body = buildJsonObject {
            put("shipment_ref", ref)
            put("events", events)
            put("evidence_hashes", Rows.json.encodeToString(submission.evidenceHashes.toSortedMap()))
            put("match", matchResult)
        }.toString()

        val signature = signer.signToB64(body.toByteArray())

        val id = conn.queryOne(
            """INSERT INTO pod_certificates (shipment_id, events, evidence_hashes, match_result,
                                             signature, key_id, pdf_uri, bundle_uri)
               SELECT id, ?, ?, ?, ?, ?, ?, ? FROM shipments WHERE ref = ?
               RETURNING id::text AS id""",
            Jsonb(events.toString()),
            Jsonb(Rows.json.encodeToString(submission.evidenceHashes)),
            Jsonb(matchResult.toString()),
            signature, signer.keyId,
            "s3://veritransit-pod/$ref/certificate.pdf",
            "s3://veritransit-pod/$ref/claims-bundle.zip",
            ref,
        ) { it.str("id") } ?: error("certificate insert returned no id")

        conn.update(
            "UPDATE shipments SET status = 'RECEIVED', received_at = COALESCE(received_at, now()) " +
                "WHERE ref = ? AND status NOT IN ('FLAGGED')", ref,
        )

        audit.append(conn, officer ?: "system", "POD_ISSUED", ref, buildJsonObject {
            put("certificate", id)
            put("key_id", signer.keyId)
            put("evidence_count", submission.evidenceHashes.size)
        })

        PodCertificate(
            id = id, shipmentRef = ref, signature = signature, keyId = signer.keyId,
            issuedAt = java.time.Instant.now().toString(),
            evidenceHashes = submission.evidenceHashes,
            matchResult = Rows.flattenJson(matchResult.toString()),
            pdfUri = "s3://veritransit-pod/$ref/certificate.pdf",
        )
    }

    fun get(id: String): PodCertificate? = db.queryOne(
        """SELECT c.id::text AS id, s.ref, c.signature, c.key_id, c.issued_at,
                  c.evidence_hashes::text AS hashes, c.match_result::text AS match, c.pdf_uri
             FROM pod_certificates c JOIN shipments s ON s.id = c.shipment_id
            WHERE c.id = ?::uuid""",
        id, map = ::mapCertificate,
    )

    fun forShipment(ref: String): List<PodCertificate> = db.query(
        """SELECT c.id::text AS id, s.ref, c.signature, c.key_id, c.issued_at,
                  c.evidence_hashes::text AS hashes, c.match_result::text AS match, c.pdf_uri
             FROM pod_certificates c JOIN shipments s ON s.id = c.shipment_id
            WHERE s.ref = ? ORDER BY c.issued_at DESC""",
        ref, map = ::mapCertificate,
    )

    private fun mapCertificate(rs: java.sql.ResultSet) = PodCertificate(
        id = rs.str("id"), shipmentRef = rs.str("ref"), signature = rs.str("signature"),
        keyId = rs.str("key_id"), issuedAt = rs.iso("issued_at"),
        evidenceHashes = runCatching {
            Rows.json.decodeFromString<Map<String, String>>(rs.str("hashes"))
        }.getOrDefault(emptyMap()),
        matchResult = Rows.flattenJson(rs.str("match")),
        pdfUri = rs.strOrNull("pdf_uri"),
    )

    /**
     * Human-readable certificate text. The real PDF renderer is a Phase 4
     * deliverable; this is the same content in a form a dispute officer or a
     * Telegram thread can already read, and it is what the PDF will typeset.
     */
    fun render(id: String): String? {
        val cert = get(id) ?: return null
        return buildString {
            appendLine("VERITRANSIT — PROOF OF DELIVERY CERTIFICATE")
            appendLine("=".repeat(52))
            appendLine("Certificate : ${cert.id}")
            appendLine("Shipment    : ${cert.shipmentRef}")
            appendLine("Issued      : ${cert.issuedAt}")
            appendLine("Signing key : ${cert.keyId}")
            appendLine()
            appendLine("FOUR-WAY MATCH AT DELIVERY")
            cert.matchResult.forEach { (k, v) -> appendLine("  ${k.padEnd(14)} $v") }
            appendLine()
            appendLine("EVIDENCE (hashed on the capturing device before upload)")
            cert.evidenceHashes.forEach { (name, hash) -> appendLine("  ${name.padEnd(22)} $hash") }
            appendLine()
            appendLine("Ed25519 signature:")
            appendLine("  ${cert.signature}")
            appendLine()
            appendLine("VeriTransit certifies that the evidence listed above existed with these")
            appendLine("hashes at the stated time and location. This is a statement of verified")
            appendLine("fact, not a guarantee of the condition or merchantability of the goods.")
        }
    }
}
