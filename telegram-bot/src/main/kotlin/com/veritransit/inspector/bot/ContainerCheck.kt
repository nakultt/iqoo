package com.veritransit.inspector.bot

/**
 * Container-check engine: resolves a free-form reference (E-Way Bill, vehicle
 * number, inspection id, cargo keyword, or raw receipt text) against the
 * inspection vault and produces the verdict report.
 */
object ContainerCheck {

    private val EWB = Regex("(?i)\\bEWB[\\s-]*(\\d{4})[\\s-]*(\\d{4})\\b")
    private val RECORD_ID = Regex("(?i)\\bVT[\\s-]*(\\d{4})[\\s-]*(\\d{4})\\b")
    private val VEHICLE = Regex("\\b([A-Za-z]{2})[\\s-]*(\\d{1,2})[\\s-]*([A-Za-z]{1,3})[\\s-]*(\\d{3,4})\\b")
    private val WORD = Regex("[a-z]+")

    /** True if the reference contains a machine-readable id (EWB / vehicle / record id). */
    fun hasDirectRef(query: String): Boolean =
        EWB.containsMatchIn(query) || RECORD_ID.containsMatchIn(query) || VEHICLE.containsMatchIn(query)

    /** Best-matching records for a query, most relevant first. Empty when nothing matches. */
    fun resolve(query: String, records: List<InspectionRecord>): List<InspectionRecord> {
        EWB.find(query)?.let { m ->
            val digits = m.groupValues[1] + m.groupValues[2]
            return records.filter { it.ewb.filter(Char::isDigit) == digits }
        }
        RECORD_ID.find(query)?.let { m ->
            val digits = m.groupValues[1] + m.groupValues[2]
            return records.filter { it.id.filter(Char::isDigit) == digits }
        }
        VEHICLE.find(query)?.let { m ->
            val plate = m.groupValues.slice(1..4).joinToString("").uppercase()
            return records.filter { it.vehicle.filter(Char::isLetterOrDigit).uppercase() == plate }
        }
        return resolveByKeyword(query, records)
    }

    /** Keyword fallback: matches cargo names, item names, and vehicle models against the query. */
    fun resolveByKeyword(query: String, records: List<InspectionRecord>): List<InspectionRecord> {
        val tokens = WORD.findAll(query.lowercase()).map { stem(it.value) }.toSet()
        if (tokens.isEmpty()) return emptyList()
        return records.mapNotNull { record ->
            val score = vocabularyOf(record).count { it in tokens }
            if (score == 0) null else score to record
        }.sortedWith(compareByDescending<Pair<Int, InspectionRecord>> { it.first }.thenByDescending { it.second.timestamp })
            .map { it.second }
    }

    private fun vocabularyOf(record: InspectionRecord): Set<String> =
        (record.cargo + " " + record.vehicleModel + " " + record.items.joinToString(" ") { it.name })
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
        val record: InspectionRecord?,
        val candidates: List<InspectionRecord>,
        val matchedBy: String,
    )

    /**
     * Analyzes receipt text (caption, pasted text, or downloaded document content)
     * and locates the consignment it belongs to.
     */
    fun analyzeReceipt(text: String, records: List<InspectionRecord>): ReceiptMatch? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val direct = if (hasDirectRef(trimmed)) resolve(trimmed, records) else emptyList()
        if (direct.isNotEmpty()) return ReceiptMatch(direct.first(), direct, "reference number on the receipt")
        val keyword = resolveByKeyword(trimmed, records)
        if (keyword.isNotEmpty()) {
            return ReceiptMatch(keyword.first(), keyword, "cargo description on the receipt")
        }
        return null
    }

    // ---- Reports ------------------------------------------------------------

    fun verdictLabel(verdict: Verdict): String = when (verdict) {
        Verdict.PASSED -> "✅ PASSED"
        Verdict.REVIEW -> "⚠️ REVIEW"
        Verdict.PENDING -> "🕓 PENDING"
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

    /** HTML-formatted container check report for a single inspection record. */
    fun containerReport(record: InspectionRecord, now: Long): String = buildString {
        appendLine("🚚 <b>Container Check</b> — ${Html.esc(record.id)}")
        appendLine("📄 ${Html.esc(record.ewb)} · ${Html.esc(record.vehicle)} (${Html.esc(record.vehicleModel)})")
        appendLine("📦 ${Html.esc(record.cargo)}")
        appendLine("🛣 ${Html.esc(record.route)} · ${record.distanceKm} km")
        appendLine()
        appendLine("<b>Verdict:</b> ${verdictLabel(record.verdict)}")
        if (record.confidence > 0f) appendLine("Scan confidence: ${"%.1f".format(record.confidence * 100)}%")
        appendLine("Inspected ${Html.esc(ago(now, record.timestamp))} at ${Html.esc(BotData.STATION)}")
        if (record.items.isNotEmpty()) {
            appendLine()
            if (record.discrepancyCount == 0) {
                appendLine("<b>Manifest reconciliation:</b> all units match")
                record.items.forEach { appendLine("• ${Html.esc(it.name)} — ${it.found}/${it.expected} ✓") }
            } else {
                appendLine("<b>Manifest reconciliation:</b> ${record.discrepancyCount} discrepancy(ies)")
                record.items.filter { it.status != ItemStatus.MATCHED }.forEach { item ->
                    when (item.status) {
                        ItemStatus.SHORTAGE ->
                            appendLine("• ${Html.esc(item.name)} — expected ${item.expected}, found ${item.found} (shortage)")
                        ItemStatus.UNLISTED ->
                            appendLine("• ${Html.esc(item.name)} — not on manifest, found ${item.found} (unlisted)")
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
        when (record.verdict) {
            Verdict.PASSED -> {
                appendLine("⚖️ <b>Recommended:</b> ${Html.esc(OfficerAction.CLEAR.title)}")
                appendLine(Html.esc(OfficerAction.CLEAR.detail))
            }
            Verdict.REVIEW -> {
                appendLine("⚖️ <b>Recommended:</b> ${Html.esc(OfficerAction.RECOUNT.title)}")
                appendLine(Html.esc(OfficerAction.RECOUNT.detail))
            }
            Verdict.PENDING -> appendLine("⚖️ Physical scan still in progress — no verdict recorded yet.")
        }
    }.trimEnd()

    /** Compact one-line summary used in list replies. */
    fun listLine(record: InspectionRecord, now: Long): String =
        "• ${Html.esc(record.id)} · ${Html.esc(record.vehicle)} — ${verdictLabel(record.verdict)}" +
            (if (record.discrepancyCount > 0) " · ${record.discrepancyCount} discrepancy(ies)" else "") +
            " · ${Html.esc(ago(now, record.timestamp))}"
}

/** Minimal HTML escaping for Telegram parse_mode=HTML. */
object Html {
    fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
