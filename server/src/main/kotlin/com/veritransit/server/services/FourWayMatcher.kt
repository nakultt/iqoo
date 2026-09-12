package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.db.*
import kotlinx.serialization.encodeToString
import kotlin.math.abs

/**
 * §5.1 — the four-way match: ordered ↔ invoiced ↔ declared ↔ physically scanned.
 *
 * Deterministic by design (§6: "deterministic engines decide, LLMs explain").
 * Nothing in this class consults a model; given the same documents and the same
 * scans it returns the same held value every time, which is the only basis on
 * which a finance team will act on it.
 *
 * The **PO is the anchor**: it is the only document the buyer authored, so it
 * defines what was actually authorised. An invoice that exceeds it is an
 * over-bill even when the goods are physically present — which is exactly the
 * case the plan's running example turns on.
 */
class FourWayMatcher(private val db: Database) {

    /**
     * Physical quantity per PO line.
     *
     * The two stages ask genuinely different questions, so they count
     * differently rather than sharing one status filter:
     *
     *  * **At receipt** — what did the receiver actually verify? Current status
     *    is the answer, and a carton that never arrived is correctly absent.
     *  * **At dispatch** — what went onto the truck? Current status cannot
     *    answer that: a box loaded in Bengaluru and missing in Hyderabad reads
     *    as MISSING today, which would retroactively rewrite the dispatch run
     *    into claiming it was never loaded. The LOAD scans are the historical
     *    record, and per §2.1 loading a master loads its whole subtree — so a
     *    unit counts when it, or its master, was scanned out.
     */
    private fun physicalByLine(ref: String, received: Boolean): Map<Int, Double> =
        if (received) physicalAtReceipt(ref) else physicalAtDispatch(ref)

    private fun physicalAtReceipt(ref: String): Map<Int, Double> = db.query(
        """SELECT p.po_line_no AS line,
                  SUM(CASE
                        WHEN p.kind <> 'UNIT' AND NOT EXISTS (
                             SELECT 1 FROM packages c WHERE c.parent_code = p.package_code)
                          THEN p.qty
                        WHEN p.kind = 'UNIT' THEN 1
                        ELSE 0
                      END) AS qty
             FROM packages p JOIN shipments s ON s.id = p.shipment_id
            WHERE s.ref = ? AND p.po_line_no IS NOT NULL AND p.status = 'RECEIVED'
            GROUP BY p.po_line_no""",
        ref,
    ) { it.int("line") to it.dbl("qty") }.toMap()

    private fun physicalAtDispatch(ref: String): Map<Int, Double> = db.query(
        """WITH loaded AS (
               SELECT DISTINCT package_code FROM scan_events
                WHERE shipment_ref = ? AND kind = 'LOAD' AND result <> 'REJECTED')
           SELECT p.po_line_no AS line,
                  SUM(CASE
                        WHEN p.kind <> 'UNIT' AND NOT EXISTS (
                             SELECT 1 FROM packages c WHERE c.parent_code = p.package_code)
                          THEN p.qty
                        WHEN p.kind = 'UNIT' THEN 1
                        ELSE 0
                      END) AS qty
             FROM packages p JOIN shipments s ON s.id = p.shipment_id
            WHERE s.ref = ? AND p.po_line_no IS NOT NULL
              AND (p.package_code IN (SELECT package_code FROM loaded)
                   OR p.parent_code IN (SELECT package_code FROM loaded))
            GROUP BY p.po_line_no""",
        ref, ref,
    ) { it.int("line") to it.dbl("qty") }.toMap()

    private fun documentOf(ref: String, kind: DocumentKind): DocumentFact? = db.queryOne(
        """SELECT d.fact::text AS fact FROM documents d JOIN shipments s ON s.id = d.shipment_id
            WHERE s.ref = ? AND d.kind = ?::document_kind ORDER BY d.uploaded_at DESC LIMIT 1""",
        ref, kind.name,
    ) { runCatching { Rows.json.decodeFromString<DocumentFact>(it.str("fact")) }.getOrNull() }

    fun tolerances(): Tolerances = db.queryOne(
        "SELECT rules::text AS rules FROM policies WHERE kind = 'tolerance' AND active LIMIT 1",
    ) { it.str("rules") }?.let {
        runCatching { Rows.json.decodeFromString<Tolerances>(it) }.getOrNull()
    } ?: Tolerances()

    /**
     * Runs the match. [atReceipt] selects which physical reality to compare
     * against: what left the dock, or what the receiver actually verified. Both
     * runs are kept — the plan's example holds ₹87,400 at dispatch and more once
     * a sealed carton turns out to be short.
     */
    fun run(ref: String, atReceipt: Boolean, runBy: String = "system"): ReconciliationRun {
        val po = documentOf(ref, DocumentKind.PO)
        val invoice = documentOf(ref, DocumentKind.INVOICE)
        val ewb = documentOf(ref, DocumentKind.EWB)
        val physical = physicalByLine(ref, atReceipt)
        val tol = tolerances()

        val mismatches = mutableListOf<Mismatch>()
        val lines = mutableListOf<MatchLine>()
        var held = 0.0

        // Party check first: a GSTIN mismatch invalidates the whole comparison,
        // so it is reported even when the numbers happen to line up.
        if (po != null && invoice != null) {
            val poSeller = po.seller.gstin
            val invSeller = invoice.seller.gstin
            if (poSeller != null && invSeller != null && poSeller != invSeller) {
                mismatches += Mismatch(
                    code = MismatchCode.PARTY_MISMATCH, pair = MatchPair.PARTY,
                    detail = "invoice seller GSTIN $invSeller does not match PO $poSeller",
                )
            }
        }

        val anchorLines = po?.lines ?: invoice?.lines ?: emptyList()
        for (poLine in anchorLines) {
            val invLine = invoice?.let { match(it.lines, poLine) }
            val ewbLine = ewb?.let { match(it.lines, poLine) }
            val phys = physical[poLine.lineNo]
            val rate = if (poLine.rate > 0) poLine.rate else invLine?.rate ?: 0.0

            lines += MatchLine(
                lineNo = poLine.lineNo, sku = poLine.sku, description = poLine.description,
                hsn = poLine.hsn, rate = rate,
                orderedQty = po?.let { poLine.qty },
                invoicedQty = invLine?.qty, declaredQty = ewbLine?.qty, physicalQty = phys,
            )

            // The product itself must be the same thing before quantities mean anything.
            if (invLine != null && po != null && !sameProduct(poLine, invLine)) {
                mismatches += Mismatch(
                    code = MismatchCode.PRODUCT_MISMATCH, pair = MatchPair.PO_VS_INVOICE,
                    lineNo = poLine.lineNo, sku = poLine.sku,
                    detail = "invoice line describes '${invLine.description}' against PO '${poLine.description}'",
                )
            }

            // PO vs invoice — over-billing, the case that survives a physical count.
            if (po != null && invLine != null && !withinQty(poLine.qty, invLine.qty, tol)) {
                val delta = abs(invLine.qty - poLine.qty) * rate
                held += delta
                mismatches += Mismatch(
                    code = MismatchCode.QTY_MISMATCH, pair = MatchPair.PO_VS_INVOICE,
                    lineNo = poLine.lineNo, sku = poLine.sku,
                    orderedQty = poLine.qty, invoicedQty = invLine.qty,
                    declaredQty = ewbLine?.qty, physicalQty = phys,
                    deltaQty = invLine.qty - poLine.qty, deltaValue = round2(delta),
                    detail = "invoiced ${fmt(invLine.qty)} against PO ${fmt(poLine.qty)}",
                )
            }

            // Invoice vs e-way bill — the declared consignment should mirror the bill.
            if (invLine != null && ewbLine != null && !withinQty(invLine.qty, ewbLine.qty, tol)) {
                mismatches += Mismatch(
                    code = MismatchCode.QTY_MISMATCH, pair = MatchPair.INVOICE_VS_EWB,
                    lineNo = poLine.lineNo, sku = poLine.sku,
                    invoicedQty = invLine.qty, declaredQty = ewbLine.qty,
                    deltaQty = ewbLine.qty - invLine.qty,
                    deltaValue = round2(abs(ewbLine.qty - invLine.qty) * rate),
                    detail = "e-way bill declares ${fmt(ewbLine.qty)} against invoice ${fmt(invLine.qty)}",
                )
            }

            // Invoice vs physical — a short-ship. Only a shortfall is held; an
            // overage is a discrepancy but not money the buyer should withhold.
            if (invLine != null && phys != null && phys < invLine.qty) {
                val delta = (invLine.qty - phys) * rate
                held += delta
                mismatches += Mismatch(
                    code = if (isInnerShortage(ref, poLine.lineNo)) MismatchCode.INNER_SHORTAGE
                    else MismatchCode.QTY_MISMATCH,
                    pair = MatchPair.INVOICE_VS_PHYSICAL,
                    lineNo = poLine.lineNo, sku = poLine.sku,
                    orderedQty = po?.let { poLine.qty }, invoicedQty = invLine.qty,
                    declaredQty = ewbLine?.qty, physicalQty = phys,
                    deltaQty = phys - invLine.qty, deltaValue = round2(delta),
                    detail = "physically verified ${fmt(phys)} of ${fmt(invLine.qty)} invoiced",
                )
            }

            // Value check, independent of quantity: a line can carry the right
            // count at the wrong rate.
            if (invLine != null && po != null && poLine.amount > 0 && invLine.amount > 0) {
                val expected = poLine.rate * invLine.qty
                if (expected > 0 && abs(invLine.amount - expected) / expected * 100 > tol.valuePct) {
                    mismatches += Mismatch(
                        code = MismatchCode.VALUE_MISMATCH, pair = MatchPair.PO_VS_INVOICE,
                        lineNo = poLine.lineNo, sku = poLine.sku,
                        deltaValue = round2(abs(invLine.amount - expected)),
                        detail = "line value ${fmt(invLine.amount)} against ${fmt(expected)} at PO rate",
                    )
                }
            }
        }

        if (po == null) mismatches += missing(DocumentKind.PO)
        if (invoice == null) mismatches += missing(DocumentKind.INVOICE)
        if (ewb == null && invoice != null) mismatches += missing(DocumentKind.EWB)
        mismatches += duplicateDocuments(ref)

        val status = when {
            mismatches.isEmpty() -> MatchStatus.MATCHED
            po == null || invoice == null -> MatchStatus.PARTIAL
            else -> MatchStatus.MISMATCHED
        }

        return ReconciliationRun(
            shipmentRef = ref,
            matrix = MatchMatrix(
                anchor = "PO", tolerances = tol, lines = lines,
                fourWay = mapOf(
                    "ordered" to lines.sumOf { it.orderedQty ?: 0.0 },
                    "invoiced" to lines.sumOf { it.invoicedQty ?: 0.0 },
                    "declared" to lines.sumOf { it.declaredQty ?: 0.0 },
                    "physical" to lines.sumOf { it.physicalQty ?: 0.0 },
                ),
            ),
            mismatches = mismatches, status = status, heldValue = round2(held), runBy = runBy,
        )
    }

    /** Persists a run and returns it — the matrix is kept per run for the audit trail. */
    fun runAndStore(ref: String, atReceipt: Boolean, runBy: String = "system"): ReconciliationRun {
        val result = run(ref, atReceipt, runBy)
        db.update(
            """INSERT INTO reconciliation_runs (shipment_id, matrix, mismatches, status, held_value, run_by)
               SELECT id, ?, ?, ?, ?, ? FROM shipments WHERE ref = ?""",
            Jsonb(Rows.json.encodeToString(result.matrix)),
            Jsonb(Rows.json.encodeToString(result.mismatches)),
            result.status.name, result.heldValue, runBy, ref,
        )
        return result
    }

    fun latest(ref: String): ReconciliationRun? = db.queryOne(
        """SELECT r.matrix::text AS matrix, r.mismatches::text AS mismatches, r.status,
                  r.held_value, r.run_by, r.created_at
             FROM reconciliation_runs r JOIN shipments s ON s.id = r.shipment_id
            WHERE s.ref = ? ORDER BY r.created_at DESC LIMIT 1""",
        ref,
    ) {
        ReconciliationRun(
            shipmentRef = ref,
            matrix = runCatching { Rows.json.decodeFromString<MatchMatrix>(it.str("matrix")) }
                .getOrDefault(MatchMatrix()),
            mismatches = runCatching { Rows.json.decodeFromString<List<Mismatch>>(it.str("mismatches")) }
                .getOrDefault(emptyList()),
            status = MatchStatus.valueOf(it.str("status")),
            heldValue = it.dbl("held_value"), runBy = it.str("run_by"), createdAt = it.iso("created_at"),
        )
    }

    // ------------------------------------------------------------- helpers

    /**
     * §5.1 — match by HSN/SKU, fall back to description similarity. Suppliers
     * rarely number their invoice lines the way the buyer numbered the PO, so
     * line_no is the last resort rather than the first.
     */
    private fun match(candidates: List<DocumentLine>, anchor: DocumentLine): DocumentLine? =
        candidates.firstOrNull { it.sku != null && it.sku == anchor.sku }
            ?: candidates.firstOrNull { it.hsn != null && it.hsn == anchor.hsn }
            ?: candidates.firstOrNull { similar(it.description, anchor.description) }
            ?: candidates.firstOrNull { it.lineNo == anchor.lineNo }

    private fun sameProduct(a: DocumentLine, b: DocumentLine): Boolean =
        (a.sku != null && a.sku == b.sku) || (a.hsn != null && a.hsn == b.hsn) ||
            similar(a.description, b.description)

    /** Token-overlap similarity: cheap, explainable, and good enough for goods descriptions. */
    private fun similar(a: String, b: String): Boolean {
        val x = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        val y = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        if (x.isEmpty() || y.isEmpty()) return false
        val overlap = x.intersect(y).size.toDouble() / minOf(x.size, y.size)
        return overlap >= 0.6
    }

    private fun withinQty(expected: Double, actual: Double, tol: Tolerances): Boolean =
        if (tol.qty == "exact") expected == actual
        else abs(actual - expected) / maxOf(expected, 1.0) * 100 <= tol.valuePct

    /** Distinguishes "a carton was short" from "the whole line short-shipped". */
    private fun isInnerShortage(ref: String, lineNo: Int): Boolean = (db.queryOne(
        """SELECT count(*) AS n FROM packages p JOIN shipments s ON s.id = p.shipment_id
            WHERE s.ref = ? AND p.po_line_no = ? AND p.kind = 'UNIT' AND p.status = 'MISSING'
              AND p.parent_code IS NOT NULL""",
        ref, lineNo,
    ) { it.int("n") } ?: 0) > 0

    private fun missing(kind: DocumentKind) = Mismatch(
        code = MismatchCode.DOC_MISSING,
        pair = if (kind == DocumentKind.EWB) MatchPair.INVOICE_VS_EWB else MatchPair.PO_VS_INVOICE,
        detail = "${kind.name.lowercase()} not yet attached to the shipment",
    )

    private fun duplicateDocuments(ref: String): List<Mismatch> = db.query(
        """SELECT kind::text AS kind, doc_no, count(*) AS n
             FROM documents d JOIN shipments s ON s.id = d.shipment_id
            WHERE s.ref = ? GROUP BY 1, 2 HAVING count(*) > 1""",
        ref,
    ) {
        Mismatch(
            code = MismatchCode.DOC_DUPLICATE, pair = MatchPair.PO_VS_INVOICE,
            detail = "${it.str("kind")} ${it.str("doc_no")} attached ${it.int("n")} times",
        )
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v)
}
