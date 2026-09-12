package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * §5.1 document ingest. Both capture paths — a phone photograph read by the NPU
 * and a PDF parsed on the web — land here in the same [DocumentFact] shape.
 *
 * The `confirmed_by` column is the safety rail from §12: a 4-bit model will
 * eventually misread a dense invoice, so a document whose numbers drive money
 * must carry a human who confirmed them on screen. [attach] records who that
 * was; it does not invent one.
 */
class DocumentService(private val db: Database, private val audit: AuditLog) {

    class DuplicateDocument(val kind: DocumentKind, val docNo: String) :
        IllegalStateException("${kind.name} $docNo is already attached to a shipment")

    fun attach(
        ref: String,
        doc: ShipmentDocument,
        uploadedBy: String?,
        confirmedBy: String?,
    ): ShipmentDocument = db.transaction { conn ->
        // §6.2 documents service: "dedupe by doc_no". The same invoice arriving
        // twice is a reconciliation signal (DOC_DUPLICATE), not a second invoice.
        val existing = conn.queryOne(
            """SELECT s.ref FROM documents d JOIN shipments s ON s.id = d.shipment_id
                WHERE d.kind = ?::document_kind AND upper(d.doc_no) = upper(?)""",
            doc.kind.name, doc.docNo,
        ) { it.str("ref") }
        if (existing != null && existing != ref) throw DuplicateDocument(doc.kind, doc.docNo)

        conn.update(
            """INSERT INTO documents (shipment_id, kind, doc_no, doc_date, party_from, party_to,
                                      fact, source_uri, read_by, confidence, confirmed_by, uploaded_by)
               SELECT s.id, ?::document_kind, ?, ?::date,
                      (SELECT id FROM parties WHERE gstin = ? LIMIT 1),
                      (SELECT id FROM parties WHERE gstin = ? LIMIT 1),
                      ?, ?, ?::document_reader, ?,
                      (SELECT id FROM users WHERE name = ? LIMIT 1),
                      (SELECT id FROM users WHERE name = ? LIMIT 1)
                 FROM shipments s WHERE s.ref = ?
               ON CONFLICT (kind, upper(doc_no)) DO UPDATE
                 SET fact = EXCLUDED.fact, source_uri = EXCLUDED.source_uri,
                     confidence = EXCLUDED.confidence, confirmed_by = EXCLUDED.confirmed_by""",
            doc.kind.name, doc.docNo, doc.docDate,
            doc.fact.seller.gstin, doc.fact.buyer.gstin,
            Jsonb(Rows.json.encodeToString(doc.fact)),
            doc.sourceUri,
            when (doc.readBy) {
                DocumentReader.DEVICE_NPU -> "device_npu"
                DocumentReader.WEB_PARSER -> "web_parser"
                DocumentReader.MANUAL -> "manual"
            },
            doc.confidence, confirmedBy, uploadedBy, ref,
        )

        audit.append(conn, uploadedBy ?: "system", "DOCUMENT_ATTACHED", ref, buildJsonObject {
            put("kind", doc.kind.name)
            put("doc_no", doc.docNo)
            put("read_by", doc.readBy.name)
            put("confirmed_by", confirmedBy ?: "")
        })

        doc.copy(shipmentRef = ref)
    }

    fun list(ref: String): List<ShipmentDocument> = db.query(
        """SELECT d.id::text AS id, s.ref AS shipment_ref, d.kind, d.doc_no,
                  d.doc_date::text AS doc_date, d.fact::text AS fact, d.source_uri,
                  d.read_by::text AS read_by, d.confidence
             FROM documents d JOIN shipments s ON s.id = d.shipment_id
            WHERE s.ref = ? ORDER BY d.kind, d.doc_no""",
        ref, map = Rows::document,
    )

    /**
     * Derives the order value from the invoice (or the PO when no invoice has
     * arrived), so finance terms track the paperwork rather than being typed in.
     */
    fun orderValue(ref: String): Double? = db.queryOne(
        """SELECT COALESCE(
                    (SELECT (fact -> 'totals' ->> 'taxable_value')::numeric FROM documents d
                       JOIN shipments s ON s.id = d.shipment_id
                      WHERE s.ref = ? AND d.kind = 'INVOICE' ORDER BY d.uploaded_at DESC LIMIT 1),
                    (SELECT (fact -> 'totals' ->> 'taxable_value')::numeric FROM documents d
                       JOIN shipments s ON s.id = d.shipment_id
                      WHERE s.ref = ? AND d.kind = 'PO' ORDER BY d.uploaded_at DESC LIMIT 1)
                  ) AS v""",
        ref, ref,
    ) { it.dblOrNull("v") }
}
