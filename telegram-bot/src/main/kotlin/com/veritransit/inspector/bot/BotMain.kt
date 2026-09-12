package com.veritransit.inspector.bot

import kotlin.system.exitProcess

/**
 * VeriTransit container-check bot. Anyone who messages the bot — with a question
 * or a receipt (photo caption or text document) — gets the matching container /
 * consignment checked against the inspection vault.
 */
fun main(args: Array<String>) {
    val pollOnce = "--poll-once" in args // diagnostics: confirm connectivity, do not reply
    val token = BotConfig.resolveToken()
    if (token.isNullOrBlank()) {
        System.err.println(
            """
            |No bot token found. Provide one via (any of):
            |  • system property  -Dtelegram.bot.token=<token>
            |  • env var          VERITRANSIT_BOT_TOKEN=<token>
            |  • gitignored file  telegram-bot/secrets.properties → telegram.bot.token=<token>
            """.trimMargin(),
        )
        exitProcess(1)
    }

    val client = TelegramClient(token)
    val bot = try {
        client.getMe()
    } catch (e: Exception) {
        System.err.println("Telegram API unreachable: ${e.message}")
        exitProcess(1)
    }
    println("VeriTransit container-check bot ready: @${bot.username} (${bot.name})")

    if (pollOnce) {
        val updates = client.pollUpdates()
        println("Connectivity OK — ${updates.size} pending update(s), no replies sent (--poll-once).")
        return
    }

    val handler = MessageHandler(BotData.seed(System.currentTimeMillis())) { System.currentTimeMillis() }
    println("Long-polling for questions and receipts… (Ctrl+C to stop)")
    while (true) {
        try {
            for (update in client.pollUpdates()) {
                val message = update.message ?: continue
                val documentText = if (message.documentFileId != null && message.isTextualDocument) {
                    runCatching { client.downloadDocumentText(message.documentFileId) }
                        .onFailure { System.err.println("Document download failed: ${it.message}") }
                        .getOrNull()
                } else null
                val reply = handler.respond(message, documentText)
                if (reply != null) client.sendMessage(message.chatId, reply)
            }
        } catch (e: Exception) {
            System.err.println("Poll/send failed: ${e.message} — retrying in 3s")
            Thread.sleep(3_000)
        }
    }
}

/** Pure message → reply mapping, kept free of Telegram I/O for testability. */
class MessageHandler(
    private val records: List<InspectionRecord>,
    private val now: () -> Long,
) {

    private val greetingRegex = Regex("(?i)(^|\\W)(hi|hello|hey|namaste|good\\s(morning|evening|afternoon))(\\W|$)")

    fun respond(message: TgMessage, documentText: String? = null): String? = when {
        message.documentFileId != null -> respondToDocument(message, documentText)
        message.hasPhoto -> respondToPhoto(message)
        else -> message.text?.trim()?.takeIf { it.isNotEmpty() }?.let(::respondToText)
    }

    private fun respondToText(text: String): String =
        if (text.startsWith("/")) respondToCommand(text) else answerQuestion(text)

    private fun respondToCommand(text: String): String {
        val command = text.substringBefore(' ').substringBefore('@').lowercase()
        val args = text.substringAfter(' ', "").trim()
        return when (command) {
            "/start" -> greeting()
            "/help" -> helpText()
            "/check" -> {
                if (args.isEmpty()) "Usage: <code>/check EWB-7819-2044</code> — or just send the E-Way Bill / vehicle number."
                else checkReply(args)
            }
            "/recent" -> recentReply(args.toIntOrNull()?.coerceIn(1, 10) ?: 5)
            "/flagged" -> flaggedReply()
            "/stats" -> statsReply()
            else -> "Unknown command. $helpHint"
        }
    }

    /** Free-form questions: direct refs win, then intent words, then cargo keywords. */
    fun answerQuestion(text: String): String {
        ContainerCheck.resolve(text, records).firstOrNull()?.let { return checkReply(text, resolved = it) }
        val lower = text.lowercase()
        return when {
            greetingRegex.containsMatchIn(lower) && lower.length < 32 -> greeting()
            containsAny(lower, "flag", "review", "hold", "problem", "discrepan", "issue", "shortage", "unlisted") -> flaggedReply()
            containsAny(lower, "stat", "summary", "shift", "how many", "total", "rate") -> statsReply()
            containsAny(lower, "recent", "latest", "last", "history", "today") -> recentReply(5)
            else -> {
                ContainerCheck.resolveByKeyword(text, records).firstOrNull()
                    ?.let { checkReply(text, resolved = it) }
                    ?: "I couldn't map that to a consignment. $helpHint"
            }
        }
    }

    // ---- Receipts -----------------------------------------------------------

    private fun respondToDocument(message: TgMessage, documentText: String?): String {
        val receipt = listOfNotNull(message.caption, documentText)
            .joinToString("\n")
            .trim()
        if (receipt.isNotEmpty()) return analyzeReceiptReply(receipt)
        return "I received the file but couldn't read a reference from it. " +
            "Send the E-Way Bill number (e.g. EWB-7819-2044) or the vehicle number, or resend the receipt as text/CSV."
    }

    private fun respondToPhoto(message: TgMessage): String {
        val caption = message.caption.orEmpty()
        if (caption.isBlank()) {
            return "📸 I can't read text inside photos in this build — add the E-Way Bill number or vehicle number as the photo caption and I'll run the container check."
        }
        return analyzeReceiptReply(caption)
    }

    private fun analyzeReceiptReply(receiptText: String): String {
        val match = ContainerCheck.analyzeReceipt(receiptText, records)
            ?: return "No consignment matched that receipt. Try sending the E-Way Bill number (e.g. EWB-7819-2044) or vehicle number directly."
        val report = ContainerCheck.containerReport(match.record!!, now())
        val extra = if (match.candidates.size > 1) {
            "\n\nOther possible matches:\n" + match.candidates.drop(1).take(2)
                .joinToString("\n") { ContainerCheck.listLine(it, now()) }
        } else ""
        return "📎 Matched by ${match.matchedBy}.\n\n$report$extra"
    }

    // ---- Replies ------------------------------------------------------------

    private fun checkReply(query: String, resolved: InspectionRecord? = null): String {
        val record = resolved ?: ContainerCheck.resolve(query, records).firstOrNull()
            ?: return "No consignment found for “${Html.esc(query.take(64))}”. Try an E-Way Bill (EWB-7819-2044), a vehicle number (TN 38 BX 4491), or an inspection id (VT-2024-8841)."
        return ContainerCheck.containerReport(record, now())
    }

    private fun statsReply(): String {
        val total = records.size
        val passed = records.count { it.verdict == Verdict.PASSED }
        val flagged = records.count { it.verdict == Verdict.REVIEW }
        val pending = total - passed - flagged
        return buildString {
            appendLine("📊 <b>${Html.esc(BotData.STATION)}</b> — shift summary (${Html.esc(BotData.INSPECTOR)} ${Html.esc(BotData.BADGE)})")
            appendLine("• Inspections: <b>$total</b>")
            appendLine("• Cleared: $passed ✅")
            appendLine("• Flagged: $flagged ⚠️")
            appendLine("• Pending: $pending 🕓")
            append("• Flag rate: ${"%.1f".format(flagged * 100.0 / total)}%")
        }
    }

    private fun flaggedReply(): String {
        val flagged = records.filter { it.flagged }
        if (flagged.isEmpty()) return "No flagged consignments right now ✅"
        return buildString {
            appendLine("⚠️ <b>Flagged consignments</b> (${flagged.size}):")
            appendLine()
            appendLine(flagged.joinToString("\n") { ContainerCheck.listLine(it, now()) })
            appendLine()
            append("Send <code>/check &lt;id&gt;</code> for the full container report.")
        }
    }

    private fun recentReply(n: Int): String {
        val latest = records.sortedByDescending { it.timestamp }.take(n)
        return buildString {
            appendLine("🕘 <b>Latest inspections</b>:")
            appendLine()
            appendLine(latest.joinToString("\n") { ContainerCheck.listLine(it, now()) })
            appendLine()
            append("Send any EWB / vehicle number for a full check.")
        }
    }

    private fun greeting(): String =
        "👋 ${BotData.INSPECTOR} on duty at ${BotData.STATION}. " +
            "Send me an E-Way Bill number, vehicle number, or a receipt and I'll run the container check.\n\n$helpHint"

    private val helpHint =
        "Try <code>/check EWB-7819-2044</code>, <code>/recent</code>, <code>/flagged</code> or <code>/stats</code>."

    private fun helpText(): String = """
        |🤖 <b>VeriTransit Container-Check Bot</b>
        |
        |<b>What I do</b> — ask me about any consignment or drop a receipt, and I check the container against the ${Html.esc(BotData.STATION)} inspection vault: manifest reconciliation, discrepancies, verdict and recommended statutory action.
        |
        |<b>Just send</b>
        |• an E-Way Bill — <code>EWB-7819-2044</code>
        |• a vehicle number — <code>TN 38 BX 4491</code>
        |• an inspection id — <code>VT-2024-8841</code>
        |• a receipt photo (EWB in the caption) or a text/CSV receipt
        |
        |<b>Commands</b>
        |/check &lt;ref&gt; — full container check
        |/recent — latest inspections
        |/flagged — consignments held for review
        |/stats — shift summary
    """.trimMargin()

    private fun containsAny(text: String, vararg needles: String): Boolean = needles.any { it in text }
}
