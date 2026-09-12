package com.veritransit.inspector.bot

/**
 * Receiving-check engine: resolves a free-form reference (purchase order,
 * packing-list reference, SKU, GRN id, or raw receipt text) against the
 * receiving log and produces the receipt report.
 */
object ReceivingCheck {

    private val PO = Regex("(?i)(?<![A-Z0-9])PO[\\s\\-_]?(\\d{4})[\\s\\-_]?(\\d{2,6})(?![0-9])")
    private val PL = Regex("(?i)(?<![A-Z0-9])PL[\\s\\-_]?(\\d{4})[\\s\\-_]?(\\d{2,6})(?:[\\s\\-_]?([A-Z]))?(?![0-9A-Z])")
    private val GRN = Regex("(?i)(?<![A-Z0-9])GRN[\\s\\-_]?(\\d{4})[\\s\\-_]?(\\d{2,6})(?![0-9])")
    private val SKU = Regex("(?<![A-Z0-9])([A-Z]{3})[\\s\\-_]?(\\d{4})(?![0-9])")
    private val WORD = Regex("[a-z]+")

    /** True if the reference contains a machine-readable id (PO / PL / SKU / GRN). */
    fun hasDirectRef(query: String): Boolean =
        PO.containsMatchIn(query) || PL.containsMatchIn(query) ||
            GRN.containsMatchIn(query) || SKU.containsMatchIn(query.uppercase())

    /**
     * Best-matching records for a query, most relevant first. Empty when
     * nothing matches. The order is the resolution order: the references that
     * identify a delivery outright, then the SKU, then free-text keywords.
     */
    fun resolve(query: String, records: List<ReceivingRecord>): List<ReceivingRecord> {
        PO.find(query)?.let { m ->
            val ref = "PO-${m.groupValues[1]}-${m.groupValues[2]}"
            return records.filter { it.purchaseOrderId.equals(ref, ignoreCase = true) }
        }
        PL.find(query)?.let { m ->
            val tail = m.groupValues[3].takeIf { it.isNotBlank() }?.let { "-$it" } ?: ""
            val ref = "PL-${m.groupValues[1]}-${m.groupValues[2]}$tail"
            return records.filter { it.packingListId.equals(ref, ignoreCase = true) }
        }
        GRN.find(query)?.let { m ->
            val ref = "GRN-${m.groupValues[1]}-${m.groupValues[2]}"
            return records.filter { it.id.equals(ref, ignoreCase = true) }
        }
        SKU.find(query.uppercase())?.let { m ->
            val sku = "${m.groupValues[1]}-${m.groupValues[2]}"
            val bySku = records.filter { r -> r.items.any { it.sku.equals(sku, ignoreCase = true) } }
            if (bySku.isNotEmpty()) return bySku
        }
        return resolveByKeyword(query, records)
    }

    /** Keyword fallback: matches supplier, goods, item names and dock against the query. */
    fun resolveByKeyword(query: String, records: List<ReceivingRecord>): List<ReceivingRecord> {
        val tokens = WORD.findAll(query.lowercase()).map { stem(it.value) }.toSet()
        if (tokens.isEmpty()) return emptyList()
        return records.mapNotNull { record ->
            val score = vocabularyOf(record).count { it in tokens }
            if (score == 0) null else score to record
        }.sortedWith(compareByDescending<Pair<Int, ReceivingRecord>> { it.first }.thenByDescending { it.second.timestamp })
            .map { it.second }
    }

    private fun vocabularyOf(record: ReceivingRecord): Set<String> =
        (record.goods + " " + record.supplier + " " + record.dock + " " +
            record.items.joinToString(" ") { "${it.sku} ${it.name} ${it.detail}" })
            .let { WORD.findAll(it.lowercase()).map { m -> stem(m.value) }.toSet() }

    private fun stem(word: String): String = when {
        word.length < 4 -> word
        word.endsWith("ies") -> word.dropLast(3) + "y"
        word.endsWith("ss") -> word
        word.endsWith("s") -> word.removeSuffix("s")
        else -> word
    }

    // ---- Receipts -----------------------------------------------------------

    data class ReceiptMatch(
        val record: ReceivingRecord?,
        val candidates: List<ReceivingRecord>,
        val matchedBy: String,
    )

    /**
     * Analyzes receipt text (caption, pasted text, or downloaded document
     * content) and locates the delivery it belongs to.
     */
    fun analyzeReceipt(text: String, records: List<ReceivingRecord>): ReceiptMatch? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val direct = if (hasDirectRef(trimmed)) resolve(trimmed, records) else emptyList()
        if (direct.isNotEmpty()) return ReceiptMatch(direct.first(), direct, "purchase order on the receipt")
        val keyword = resolveByKeyword(trimmed, records)
        if (keyword.isNotEmpty()) {
            return ReceiptMatch(keyword.first(), keyword, "goods description on the receipt")
        }
        return null
    }

    // ---- Reports ------------------------------------------------------------

    fun outcomeLabel(outcome: ReceiptOutcome): String = when (outcome) {
        ReceiptOutcome.OK -> "✅ OK"
        ReceiptOutcome.SHORT -> "⚠️ SHORT"
        ReceiptOutcome.OVER -> "⚠️ OVER"
        ReceiptOutcome.MISMATCH -> "⛔ MISMATCH"
        ReceiptOutcome.PENDING -> "🕓 PENDING"
    }

    fun ago(now: Long, timestamp: Long): String {
        val mins = ((now - timestamp) / 60_000L).coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 24 * 60L -> {
                val h = mins / 60
                if (h == 1L) "1 hr ago" else "$h hrs ago"
            }
            else -> "${mins / (24 * 60L)} days ago"
        }
    }

    /** HTML-formatted receipt report for a single receiving record. */
    fun receiptReport(record: ReceivingRecord, now: Long): String = buildString {
        appendLine("🧾 <b>Receipt Report</b> — ${Html.esc(record.id)}")
        appendLine("📄 ${Html.esc(record.purchaseOrderId)} · ${Html.esc(record.packingListId)}")
        appendLine("🏭 ${Html.esc(record.supplier)} — ${Html.esc(record.goods)}")
        appendLine("📍 ${Html.esc(record.dock)}${if (record.carrier.isNotBlank()) " · ${Html.esc(record.carrier)}" else ""}")
        appendLine()
        appendLine("<b>Receipt outcome:</b> ${outcomeLabel(record.outcome)}")
        if (record.confidence > 0f) appendLine("Count confidence: ${"%.1f".format(record.confidence * 100)}%")
        appendLine("Received ${Html.esc(ago(now, record.timestamp))} at ${Html.esc(record.dock)}")
        if (record.items.isNotEmpty()) {
            appendLine()
            if (record.discrepancyCount == 0) {
                appendLine("<b>Packed vs received:</b> all lines match")
                record.items.forEach { appendLine("• ${Html.esc(it.name)} — ${it.received}/${it.expected} ✓") }
            } else {
                appendLine("<b>Packed vs received:</b> ${record.discrepancyCount} discrepant line(s)")
                record.items.filter { it.status != ItemStatus.MATCHED }.forEach { item ->
                    when (item.status) {
                        ItemStatus.SHORT ->
                            appendLine("• ${Html.esc(item.name)} — packed ${item.expected}, received ${item.received} (short by ${item.expected - item.received})")
                        ItemStatus.OVER ->
                            appendLine("• ${Html.esc(item.name)} — packed ${item.expected}, received ${item.received} (over by ${item.received - item.expected})")
                        ItemStatus.UNLISTED ->
                            appendLine("• ${Html.esc(item.name)} — not on the packing list, received ${item.received} (unlisted)")
                        ItemStatus.DAMAGED ->
                            appendLine("• ${Html.esc(item.name)} — packed ${item.expected}, received ${item.received}, damaged ${item.damaged} (damaged)")
                        ItemStatus.MATCHED -> Unit
                    }
                }
            }
        }
        if (record.note.isNotBlank()) {
            appendLine()
            appendLine("📝 ${Html.esc(record.note)}")
        }
        appendLine()
        when (record.outcome) {
            ReceiptOutcome.OK -> {
                appendLine("📦 <b>Recommended:</b> ${Html.esc(ReceivingAction.ACCEPT.title)}")
                appendLine(Html.esc(ReceivingAction.ACCEPT.detail))
            }
            ReceiptOutcome.SHORT, ReceiptOutcome.OVER -> {
                appendLine("📦 <b>Recommended:</b> ${Html.esc(ReceivingAction.RECOUNT.title)}")
                appendLine(Html.esc(ReceivingAction.RECOUNT.detail))
            }
            ReceiptOutcome.MISMATCH -> {
                appendLine("📦 <b>Recommended:</b> ${Html.esc(ReceivingAction.FLAG.title)}")
                appendLine(Html.esc(ReceivingAction.FLAG.detail))
            }
            ReceiptOutcome.PENDING -> appendLine("📦 Dock count still in progress — no receipt recorded yet.")
        }
    }.trimEnd()

    /** Compact one-line summary used in list replies. */
    fun listLine(record: ReceivingRecord, now: Long): String =
        "• ${Html.esc(record.id)} · ${Html.esc(record.purchaseOrderId)} — ${outcomeLabel(record.outcome)}" +
            (if (record.discrepancyCount > 0) " · ${record.discrepancyCount} discrepant line(s)" else "") +
            " · ${Html.esc(ago(now, record.timestamp))}"
}

/** Minimal HTML escaping for Telegram parse_mode=HTML. */
object Html {
    fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
