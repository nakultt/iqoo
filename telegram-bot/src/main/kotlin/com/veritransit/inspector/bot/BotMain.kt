package com.veritransit.inspector.bot

import kotlin.system.exitProcess

/**
 * VeriTransit receiving bot. Anyone who messages the bot — with a question or a
 * receipt (photo caption or text document) — gets the matching delivery checked
 * against the receiving log: packed vs received, discrepancies, receipt outcome
 * and what to do with it.
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
    println("VeriTransit receiving bot ready: @${bot.username} (${bot.name})")

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
    private val records: List<ReceivingRecord>,
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
                if (args.isEmpty()) "Usage: <code>/check PO-2025-4471</code> — or just send a purchase order, packing-list reference or SKU."
                else checkReply(args)
            }
            "/recent" -> recentReply(args.toIntOrNull()?.coerceIn(1, 10) ?: 5)
            "/flagged" -> flaggedReply()
            "/stats" -> statsReply()
            else -> "Unknown command. $helpHint"
        }
    }

    /** Free-form questions: direct refs win, then intent words, then goods keywords. */
    fun answerQuestion(text: String): String {
        ReceivingCheck.resolve(text, records).firstOrNull()?.let { return checkReply(text, resolved = it) }
        val lower = text.lowercase()
        return when {
            greetingRegex.containsMatchIn(lower) && lower.length < 32 -> greeting()
            containsAny(lower, "flag", "review", "hold", "problem", "discrepan", "issue", "short", "over", "unlisted", "damag") -> flaggedReply()
            containsAny(lower, "stat", "summary", "shift", "how many", "total", "rate") -> statsReply()
            containsAny(lower, "recent", "latest", "last", "history", "today") -> recentReply(5)
            else -> {
                ReceivingCheck.resolveByKeyword(text, records).firstOrNull()
                    ?.let { checkReply(text, resolved = it) }
                    ?: "I couldn't map that to a delivery. $helpHint"
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
            "Send the purchase order (e.g. PO-2025-4471), the packing-list reference or a SKU, or resend the receipt as text/CSV."
    }

    private fun respondToPhoto(message: TgMessage): String {
        val caption = message.caption.orEmpty()
        if (caption.isBlank()) {
            return "📸 I can't read text inside photos in this build — add the purchase order, packing-list reference or SKU as the photo caption and I'll run the receiving check."
        }
        return analyzeReceiptReply(caption)
    }

    private fun analyzeReceiptReply(receiptText: String): String {
        val match = ReceivingCheck.analyzeReceipt(receiptText, records)
            ?: return "No delivery matched that receipt. Try sending the purchase order (e.g. PO-2025-4471), the packing-list reference or a SKU directly."
        val report = ReceivingCheck.receiptReport(match.record!!, now())
        val extra = if (match.candidates.size > 1) {
            "\n\nOther possible matches:\n" + match.candidates.drop(1).take(2)
                .joinToString("\n") { ReceivingCheck.listLine(it, now()) }
        } else ""
        return "📎 Matched by ${match.matchedBy}.\n\n$report$extra"
    }

    // ---- Replies ------------------------------------------------------------

    private fun checkReply(query: String, resolved: ReceivingRecord? = null): String {
        val record = resolved ?: ReceivingCheck.resolve(query, records).firstOrNull()
            ?: return "No receipt found for “${Html.esc(query.take(64))}”. Try a purchase order (PO-2025-4471), a packing list (PL-2025-4471-A), a SKU (ELC-2710) or a GRN id (GRN-2025-8841)."
        return ReceivingCheck.receiptReport(record, now())
    }

    private fun statsReply(): String {
        val total = records.size
        val accepted = records.count { it.outcome == ReceiptOutcome.OK }
        val flagged = records.count { it.flagged }
        val pending = total - accepted - flagged
        return buildString {
            appendLine("📊 <b>${Html.esc(BotData.WAREHOUSE)}</b> — receiving summary (${Html.esc(BotData.RECEIVER)})")
            appendLine("• Receipts: <b>$total</b>")
            appendLine("• Accepted: $accepted ✅")
            appendLine("• Flagged: $flagged ⚠️")
            appendLine("• Pending: $pending 🕓")
            append("• Flag rate: ${"%.1f".format(flagged * 100.0 / total)}%")
        }
    }

    private fun flaggedReply(): String {
        val flagged = records.filter { it.flagged }
        if (flagged.isEmpty()) return "No flagged deliveries right now ✅"
        return buildString {
            appendLine("⛔ <b>Flagged receipts</b> (${flagged.size}):")
            appendLine()
            appendLine(flagged.joinToString("\n") { ReceivingCheck.listLine(it, now()) })
            appendLine()
            append("Send <code>/check &lt;ref&gt;</code> for the full receipt report.")
        }
    }

    private fun recentReply(n: Int): String {
        val latest = records.sortedByDescending { it.timestamp }.take(n)
        return buildString {
            appendLine("🕘 <b>Latest receipts</b>:")
            appendLine()
            appendLine(latest.joinToString("\n") { ReceivingCheck.listLine(it, now()) })
            appendLine()
            append("Send any PO, packing-list reference or SKU for a full check.")
        }
    }

    private fun greeting(): String =
        "👋 ${BotData.RECEIVER} at ${BotData.WAREHOUSE}. " +
            "Send me a purchase order, packing-list reference or SKU, or a receipt, and I'll run the receiving check.\n\n$helpHint"

    private val helpHint =
        "Try <code>/check PO-2025-4471</code>, <code>/recent</code>, <code>/flagged</code> or <code>/stats</code>."

    private fun helpText(): String = """
        |🤖 <b>VeriTransit Receiving Bot</b>
        |
        |<b>What I do</b> — ask me about any delivery or drop a receipt, and I check it against the ${Html.esc(BotData.WAREHOUSE)} receiving log: packed vs received per SKU, discrepancies, receipt outcome and what to do with the delivery.
        |
        |This is a goods-receiving helper for B2B trade. It is <b>not</b> a GST, customs or legal verification tool and holds no statutory authority.
        |
        |<b>Just send</b>
        |• a purchase order — <code>PO-2025-4471</code>
        |• a packing list — <code>PL-2025-4471-A</code>
        |• a SKU — <code>ELC-2710</code>
        |• a GRN id — <code>GRN-2025-8841</code>
        |• a receipt photo (PO in the caption) or a text/CSV receipt
        |
        |<b>Commands</b>
        |/check &lt;ref&gt; — full receipt report
        |/recent — latest receipts
        |/flagged — deliveries held for review
        |/stats — receiving summary
    """.trimMargin()

    private fun containsAny(text: String, vararg needles: String): Boolean = needles.any { it in text }
}
