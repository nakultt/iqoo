package com.veritransit.dashboard

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Deterministic PDF report for one receiving record — no libraries, no clock,
 * no randomness: the same record (evidence frames included) always renders to
 * the exact same bytes, so a report's identity can be checked by hashing it
 * alone. Anything that would vary between runs is simply never read; the
 * document speaks only about what is in the record.
 *
 * Two A4 pages, hand-built PDF 1.4 with the standard Helvetica fonts (no
 * embedding):
 *   page 1 — record fields, receipt status, packing-list lines with per-line
 *            packed/received, receiver remarks;
 *   page 2 — the evidence frames captured at the dock (the packing list and the
 *            delivered goods, embedded as DCTDecode JPEG XObjects) and a vector
 *            bar chart of packed-vs-received per item.
 *
 * Charts are drawn as plain path/paint operators, and photographs as raw JPEG
 * passes-through, so determinism costs nothing: every byte in the file is a
 * fixed function of the record's data.
 */
object PdfReport {

    /** Page geometry (points). */
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 54f
    private const val RIGHT = PAGE_W - MARGIN

    private val LATIN_1 = Charsets.ISO_8859_1

    // Ink / hairline colours, fixed point.
    private const val INK = "0.059 0.090 0.165"
    private const val MUTED = "0.580 0.647 0.729"
    private const val EMERALD = "0.020 0.588 0.412"
    private const val AMBER = "0.706 0.325 0.035"
    private const val CRIMSON = "0.706 0.110 0.110"
    private const val BURGUNDY = "0.494 0.082 0.188"
    private const val GREY = "0.780 0.815 0.859"

    /** A JPEG frame the receiving app captured for this record, ready to embed. */
    internal data class EvidenceImage(val caption: String, val jpeg: ByteArray)

    fun render(r: ReceivingRecord): ByteArray {
        val images = buildList {
            r.listEvidence?.let { add(EvidenceImage("Packing list frame", it)) }
            r.dockEvidence?.let { add(EvidenceImage("Delivered goods frame", it)) }
        }
        val page1 = StringBuilder()
        val page2 = StringBuilder()
        buildPageOne(page1, r)
        buildPageTwo(page2, r, images)
        return assemble(page1, page2, images)
    }

    // ------------------------------------------------------------------ page 1

    private fun buildPageOne(out: StringBuilder, r: ReceivingRecord) {
        header(out, "VERITRANSIT - GOODS RECEIVED NOTE", r.id)
        var y = PAGE_H - 100f
        fun field(label: String, value: String) {
            text(out, y, "$label:  $value")
            y -= 15f
        }
        field("GRN", r.id)
        field("Purchase order", r.purchaseOrderId.ifBlank { "-" })
        field("Packing list", r.packingListId.ifBlank { "-" })
        field("Supplier", r.supplier.ifBlank { "Unnamed supplier" })
        field("Goods", r.goods.ifBlank { "Unclassified goods" })
        field("Received at", r.dock.ifBlank { "-" } + if (r.carrier.isNotBlank()) "  (${r.carrier})" else "")
        field("Received by", "${r.receiver} - ${r.warehouse}")
        field("Receipt outcome", r.outcome.name + if (r.confidence > 0f) "   Confidence: ${pct(r.confidence)}" else "")

        when (ShipState.of(r.outcome)) {
            ShipState.SHIPPED -> status(out, y, "STATUS: ACCEPTED - CLEARED FOR PAYMENT", EMERALD)
            ShipState.HELD -> status(out, y, "STATUS: HELD ON DOCK - DO NOT PAY", CRIMSON)
            ShipState.AWAITING -> status(out, y, "STATUS: AWAITING DOCK COUNT - PAYMENT ON HOLD", AMBER)
        }
        y -= 26f
        rule(out, y + 8f)
        y -= 18f
        text(out, y, "PACKING LIST (${r.items.size} lines, ${r.totalUnits} units packed, " +
                "${r.discrepancyCount} ${if (r.discrepancyCount == 1) "discrepancy" else "discrepancies"})", bold = true)
        y -= 14f
        val itemLines = r.items.mapIndexed { i, item ->
            "  ${i + 1}. ${item.name}" +
                (if (item.sku.isNotBlank()) " [${item.sku}]" else "") +
                (if (item.detail.isNotBlank()) " | ${item.detail}" else "") +
                " | packed ${item.expected} | received ${item.received}" +
                (if (item.damaged > 0) " | damaged ${item.damaged}" else "") +
                " | ${item.status}"
        }
        val shown = itemLines.take(18)
        shown.forEach { text(out, y, clip(it, 96), size = 9); y -= 12f }
        if (itemLines.size > shown.size) {
            text(out, y, "  ... +${itemLines.size - shown.size} more lines in the receipt log", size = 9, rgb = MUTED)
            y -= 12f
        }
        if (r.note.isNotBlank()) {
            y -= 10f
            text(out, y, "Remarks: ${clip(r.note, 96)}", size = 9)
        }
        footer(out, r.id)
    }

    // ------------------------------------------------------------------ page 2

    private fun buildPageTwo(out: StringBuilder, r: ReceivingRecord, images: List<EvidenceImage>) {
        header(out, "EVIDENCE & COUNT", r.id)

        var y = PAGE_H - 110f
        text(out, y, "PROOF FRAMES CAPTURED AT THE DOCK", bold = true)
        y -= 12f

        if (images.isEmpty()) {
            text(out, y - 80f, "No evidence frames were captured for this record.", rgb = MUTED)
            y -= 120f
        } else {
            // Square frames side by side; the dock camera emits 512x512.
            val size = 200f
            images.forEachIndexed { index, img ->
                val x = MARGIN + index * (size + 22f)
                out.append(drawImageOp("Im${index + 1}", x, y - size, size, size))
                text(out, y - size - 12f, img.caption, size = 8, rgb = MUTED, x = x)
            }
            y -= size + 34f
        }

        rule(out, y)
        y -= 20f
        text(out, y, "PACKED VS RECEIVED", bold = true)
        y -= 18f

        // Legend.
        legend(out, y, 54f, GREY, "Packed")
        legend(out, y, 140f, EMERALD, "Received (match)")
        legend(out, y, 268f, AMBER, "Short / over")
        legend(out, y, 412f, CRIMSON, "Unlisted")
        y -= 16f

        val rows = r.items.take(12)
        val max = maxOf(1, r.items.maxOfOrNull { maxOf(it.expected, it.received) } ?: 1)
        val scale = 280f / max
        rows.forEach { item ->
            val label = clip(item.name, 34)
            text(out, y + 1f, label, size = 7)
            bar(out, 232f, y - 1f, item.expected * scale, GREY)
            val receivedRgb = when (item.status) {
                ItemStatus.MATCHED -> EMERALD
                ItemStatus.UNLISTED -> CRIMSON
                else -> AMBER
            }
            bar(out, 232f, y - 7f, (item.received * scale).coerceAtLeast(if (item.received > 0) 1.5f else 0f), receivedRgb)
            text(out, y + 1f, "${item.expected}/${item.received}", size = 7, x = RIGHT - 40f, rgb = MUTED)
            y -= 19f
        }
        if (r.items.size > rows.size) {
            text(out, y, "+${r.items.size - rows.size} more lines in the receipt log", size = 7, rgb = MUTED)
        }
        footer(out, r.id)
    }

    // ------------------------------------------------------- content operators

    private fun header(out: StringBuilder, title: String, id: String) {
        text(out, PAGE_H - 66f, title, bold = true, size = 14, rgb = BURGUNDY)
        rule(out, PAGE_H - 76f)
        text(out, PAGE_H - 88f, id, size = 8, rgb = MUTED)
    }

    private fun status(out: StringBuilder, y: Float, line: String, rgb: String) {
        text(out, y, line, bold = true, size = 11, rgb = rgb)
    }

    private fun text(
        out: StringBuilder,
        y: Float,
        line: String,
        bold: Boolean = false,
        size: Int = 10,
        rgb: String = INK,
        x: Float = MARGIN,
    ) {
        out.append(
            "BT ${if (bold) "/F2" else "/F1"} $size Tf $rgb rg ${f(x)} ${f(y)} Td " +
                "(${escape(ascii(line))}) Tj ET\n",
        )
    }

    private fun rule(out: StringBuilder, y: Float) {
        out.append("0.886 0.910 0.941 RG 0.7 w ${f(MARGIN)} ${f(y)} m ${f(RIGHT)} ${f(y)} l S\n")
    }

    private fun bar(out: StringBuilder, x: Float, y: Float, w: Float, rgb: String) {
        out.append("$rgb rg ${f(x)} ${f(y)} ${f(w.coerceAtLeast(0.5f))} 4 re f\n")
    }

    private fun legend(out: StringBuilder, y: Float, x: Float, rgb: String, label: String) {
        out.append("$rgb rg ${f(x)} ${y - 1f} 6 6 re f\n")
        text(out, y, label, size = 7, x = x + 10f)
    }

    /** Places a pre-declared image XObject at (x, bottom), sized w x h. */
    private fun drawImageOp(name: String, x: Float, y: Float, w: Float, h: Float): String =
        "q ${f(w)} 0 0 ${f(h)} ${f(x)} ${f(y)} cm /$name Do Q\n"

    private fun footer(out: StringBuilder, id: String) {
        rule(out, 64f)
        text(out, 50f, "Deterministic document: identical records render to identical bytes. $id", size = 8, rgb = MUTED)
    }

    /** `"98.7%"` — Locale.US so the decimal separator cannot vary by host. */
    internal fun pct(confidence: Float): String =
        String.format(Locale.US, "%.1f%%", confidence * 100f)

    /** Replaces glyphs the standard fonts' Latin-1 text cannot show. */
    internal fun ascii(text: String): String = text
        .replace("→", "->")
        .replace("•", "*")
        .replace("·", "-")
        .replace("—", "-")
        .replace("–", "-")
        .replace("’", "'")
        .replace("“", "\"")
        .replace("”", "\"")
        .map { if (it.code in 32..126) it else '?' }
        .joinToString("")

    /** Escapes PDF string literals: backslash first, then the delimiters. */
    internal fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    /** Hard cap so no line can overrun the page at any font size used here. */
    private fun clip(text: String, max: Int = 100): String =
        if (text.length <= max) text else text.take(max - 3) + "..."

    /** Fixed-point formatting — never locale-dependent, never scientific. */
    private fun f(v: Float): String = String.format(Locale.US, "%.2f", v)

    /**
     * Reads pixel dimensions out of a JPEG's SOF marker. The field pipeline
     * emits fixed 512x512 squares, but the parser keeps the writer honest for
     * any frame: guessing dimensions would distort the draw matrix.
     */
    internal fun jpegSize(jpeg: ByteArray): Pair<Int, Int>? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) { i++; continue }
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0x01 || marker in 0xD0..0xD9) { i += 2; continue }
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                val h = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val w = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + len
        }
        return null
    }

    /**
     * Assembles the file: objects in a fixed order, xref offsets taken while
     * writing. Object numbering (and therefore the whole byte layout) is a
     * fixed function of the number of evidence frames. Nothing here reads the
     * clock or the environment. The Pages object may forward-reference the
     * page objects — ids are allocated up front so every reference is exact.
     */
    private fun assemble(page1: StringBuilder, page2: StringBuilder, images: List<EvidenceImage>): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = HashMap<Int, Int>()
        var nextId = 1
        fun w(s: String) = out.write(s.toByteArray(LATIN_1))
        fun begin(id: Int, body: String) {
            offsets[id] = out.size()
            w("$id 0 obj\n$body\nendobj\n")
        }
        fun beginStream(id: Int, dict: String): Int {
            offsets[id] = out.size()
            w("$id 0 obj\n$dict\nstream\n")
            return id
        }
        fun next(): Int = nextId++

        val content1 = page1.toString().toByteArray(LATIN_1)
        val content2 = page2.toString().toByteArray(LATIN_1)

        w("%PDF-1.4\n")

        // Fonts, then images, then resources, contents, pages, tree, catalog —
        // the exact write order assigns the exact ids.
        val f1 = next().also { begin(it, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>") }
        val f2 = next().also { begin(it, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>") }
        val imageIds = images.map { img ->
            val (w0, h0) = jpegSize(img.jpeg) ?: (512 to 512)
            val id = next()
            beginStream(id, "<< /Type /XObject /Subtype /Image /Width $w0 /Height $h0 " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${img.jpeg.size} >>")
            out.write(img.jpeg)
            w("\nendstream\nendobj\n")
            id
        }
        val xobjectDict = if (imageIds.isEmpty()) {
            "<< >>"
        } else {
            imageIds.mapIndexed { i, id -> "/Im${i + 1} $id 0 R" }.joinToString(" ", "<< ", " >>")
        }
        val resources = next().also {
            begin(it, "<< /Font << /F1 $f1 0 R /F2 $f2 0 R >> /XObject $xobjectDict >>")
        }
        val content1Id = next().also { id ->
            beginStream(id, "<< /Length ${content1.size} >>")
            out.write(content1)
            w("\nendstream\nendobj\n")
        }
        val content2Id = next().also { id ->
            beginStream(id, "<< /Length ${content2.size} >>")
            out.write(content2)
            w("\nendstream\nendobj\n")
        }
        val page1Id = next()
        val page2Id = next()
        val pagesId = next()
        val catalogId = next()

        begin(page1Id, "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 $PAGE_W $PAGE_H] " +
            "/Resources $resources 0 R /Contents $content1Id 0 R >>")
        begin(page2Id, "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 $PAGE_W $PAGE_H] " +
            "/Resources $resources 0 R /Contents $content2Id 0 R >>")
        begin(pagesId, "<< /Type /Pages /Kids [$page1Id 0 R $page2Id 0 R] /Count 2 >>")
        begin(catalogId, "<< /Type /Catalog /Pages $pagesId 0 R >>")

        val xrefAt = out.size()
        w("xref\n0 $nextId\n")
        w("0000000000 65535 f \n")
        for (i in 1 until nextId) w("%010d 00000 n \n".format(Locale.US, offsets[i]))
        w("trailer\n<< /Size $nextId /Root $catalogId 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")

        return out.toByteArray()
    }
}
