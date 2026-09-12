package com.veritransit.inspector.bot

import com.veritransit.core.FinanceStatus
import kotlinx.coroutines.runBlocking

/**
 * §6.4 / §5.5 — the agent's chat surface.
 *
 * The commands map onto the questions the plan says a finance manager actually
 * asks: *status*, *why held*, *score for this supplier*, and *approve*. Each one
 * answers with the factor breakdown rather than a bare verdict, because a hold
 * that cannot be explained in a chat message will be escalated by phone anyway.
 */
class PlatformCommands(private val client: PlatformClient) {

    fun handle(command: String, args: String, actor: String?): String? = runBlocking {
        when (command) {
            "/shipments" -> shipments()
            "/status" -> status(args.trim())
            "/why" -> why(args.trim())
            "/held" -> held()
            "/pending" -> pending()
            "/issues" -> issues()
            "/risk" -> risk(args.trim())
            "/agent" -> agent(args.trim().ifBlank { null })
            "/approve" -> approve(args.trim(), actor)
            else -> null   // not ours; the container-check engine still handles the rest
        }
    }

    val help: String = """
        *Platform commands*
        `/shipments` — today's dispatches
        `/status SHP-…` — completeness, risk and finance for one shipment
        `/why SHP-…` — why payment is held, with the exact deltas
        `/held` — every shipment holding money
        `/pending` — releases waiting for a checker
        `/issues` — the open discrepancy queue
        `/risk SHP-…` — score with its contributing factors
        `/agent [SHP-…]` — what the operations agent did, and what it refused to do
        `/approve SHP-…` — approve a pending release (maker-checker enforced server-side)
    """.trimIndent()

    private suspend fun shipments(): String {
        val all = runCatching { client.shipments() }.getOrElse { return offline(it) }
        if (all.isEmpty()) return "No shipments."
        return "*Shipments*\n" + all.joinToString("\n") { s ->
            "`${s.ref}` ${s.status} · ${s.supplier ?: "—"} → ${s.buyer ?: "—"} · ${s.expectedCount} cartons"
        }
    }

    private suspend fun status(ref: String): String {
        if (ref.isBlank()) return "Which shipment? e.g. `/status SHP-2026-090231`"
        val r = client.report(ref) ?: return "No shipment `$ref`."
        return buildString {
            appendLine("*${r.shipment.ref}* — ${r.shipment.status}")
            appendLine("${r.shipment.supplier ?: "—"} → ${r.shipment.buyer ?: "—"}")
            appendLine("Cartons: ${r.accounted}/${r.expectedCount}" +
                if (r.missing.isNotEmpty()) "  ⚠️ ${r.missing.size} missing" else "")
            if (r.innerUnits > 0) appendLine("Inner boxes: ${r.innerVerified}/${r.innerUnits} verified")
            r.risk?.let { appendLine("Risk: ${it.score} ${it.band}") }
            r.finance?.let {
                appendLine("Finance: ${it.status}" +
                    if (it.heldValue > 0) " — ₹${"%,.2f".format(it.heldValue)} held" else "")
            }
            if (r.discrepancies.isNotEmpty()) {
                appendLine("Open issues: ${r.discrepancies.joinToString(", ") { d -> d.kind }}")
            }
        }
    }

    private suspend fun why(ref: String): String {
        if (ref.isBlank()) return "Which shipment? e.g. `/why SHP-2026-090231`"
        return runCatching { client.explain(ref) }.getOrElse { offline(it) }
    }

    private suspend fun held(): String {
        val queue = runCatching { client.heldQueue() }.getOrElse { return offline(it) }
        if (queue.isEmpty()) return "Nothing is held."
        val total = queue.sumOf { it.heldValue }
        return buildString {
            appendLine("*${queue.size} shipment(s) holding ₹${"%,.2f".format(total)}*")
            queue.forEach {
                appendLine("`${it.shipmentRef}` ₹${"%,.2f".format(it.heldValue)} of ₹${"%,.2f".format(it.orderValue)}")
            }
            append("\nAsk `/why <ref>` for the exact deltas.")
        }
    }

    private suspend fun pending(): String {
        val queue = runCatching { client.pendingQueue() }.getOrElse { return offline(it) }
        if (queue.isEmpty()) return "No releases are waiting for a checker."
        return buildString {
            appendLine("*Awaiting a checker*")
            queue.forEach { appendLine("`${it.shipmentRef}` ₹${"%,.2f".format(it.orderValue)}") }
            append("\nApprove with `/approve <ref>` — you cannot approve your own request.")
        }
    }

    private suspend fun issues(): String {
        val open = runCatching { client.discrepancies() }.getOrElse { return offline(it) }
        if (open.isEmpty()) return "Nothing flagged."
        return "*${open.size} open discrepanc${if (open.size == 1) "y" else "ies"}*\n" +
            open.take(12).joinToString("\n") { d ->
                "${severityMark(d.severity)} `${d.shipmentRef}` ${d.kind}" +
                    (d.packageCode?.let { " · $it" } ?: "")
            }
    }

    private suspend fun risk(ref: String): String {
        if (ref.isBlank()) return "Which shipment? e.g. `/risk SHP-2026-090231`"
        val score = client.risk(ref) ?: return "No risk score for `$ref`."
        return buildString {
            appendLine("*$ref — risk ${score.score} ${score.band}*")
            score.factors.take(5).forEach { appendLine("• +${it.weight} ${it.detail}") }
        }
    }

    private suspend fun agent(ref: String?): String {
        val actions = runCatching { client.agentActions(ref) }.getOrElse { return offline(it) }
        if (actions.isEmpty()) return "The agent has not run yet."
        return buildString {
            appendLine("*Agent activity*")
            actions.take(5).forEach { a ->
                appendLine("`${a.shipmentRef ?: "—"}` ${a.outcome} (${a.triggerEvent})")
                a.steps.firstOrNull { it.step == "act" }?.let { appendLine("   ${it.detail}") }
            }
        }
    }

    private suspend fun approve(ref: String, actor: String?): String {
        if (ref.isBlank()) return "Which shipment? e.g. `/approve SHP-2026-090231`"
        if (actor.isNullOrBlank()) {
            return "I could not identify you. Approvals need a Telegram handle registered against a finance role."
        }
        val finance = client.finance(ref)
        if (finance == null) return "No finance terms on `$ref`."
        if (finance.status != FinanceStatus.RELEASE_PENDING) {
            return "`$ref` is ${finance.status} — there is no pending release to approve."
        }
        // The confirmation step is explicit by design: an approval is the one
        // chat action that moves money downstream.
        return "Approving ₹${"%,.2f".format(finance.orderValue)} on `$ref`…\n\n" +
            client.approveRelease(ref, actor)
    }

    private fun severityMark(s: String) = when (s) {
        "HIGH" -> "🔴"
        "MEDIUM" -> "🟠"
        else -> "⚪"
    }

    private fun offline(t: Throwable) =
        "The platform is unreachable (${t.message ?: "no response"}). " +
            "Scanning devices keep working offline; this answer needs the server."
}
