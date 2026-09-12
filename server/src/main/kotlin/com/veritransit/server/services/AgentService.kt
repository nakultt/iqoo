package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * §5.5 — the AI operations agent.
 *
 * The loop is `gather → update → notify → act → report`, and the single most
 * important property is stated in the plan as *"the agent proposes, policy
 * disposes"*. Every decision in this class is made by the deterministic engines
 * — [FourWayMatcher] decides match/no-match, [RiskEngine] decides the band,
 * [FinanceService] decides the state transition. The language model, when one is
 * configured, only writes the explanation.
 *
 * That split is why [act] consults [Policies] before touching money and records
 * `BLOCKED_BY_POLICY` rather than proceeding when the answer is no.
 */
class AgentService(
    private val db: Database,
    private val audit: AuditLog,
    private val matcher: FourWayMatcher,
    private val risk: RiskEngine,
    private val finance: FinanceService,
    private val shipments: ShipmentService,
    private val notifier: Notifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Anything that can carry a message to a human (Telegram, email, webhook). */
    fun interface Notifier {
        fun notify(audience: String, subject: String, message: String)
    }

    data class AutoReleasePolicy(
        val id: String,
        val name: String,
        val maxOrderValue: Double,
        val maxRiskScore: Int,
        val requireZeroMismatches: Boolean,
        val requirePod: Boolean,
    )

    fun autoReleasePolicy(): AutoReleasePolicy? = db.queryOne(
        """SELECT id::text AS id, name, rules::text AS rules FROM policies
            WHERE kind = 'agent_auto_release' AND active LIMIT 1""",
    ) {
        val rules = Rows.flattenJson(it.str("rules"))
        AutoReleasePolicy(
            id = it.str("id"), name = it.str("name"),
            maxOrderValue = rules["max_order_value"]?.toDoubleOrNull() ?: 0.0,
            maxRiskScore = rules["max_risk_score"]?.toIntOrNull() ?: 0,
            requireZeroMismatches = rules["require_zero_mismatches"]?.toBoolean() ?: true,
            requirePod = rules["require_pod"]?.toBoolean() ?: true,
        )
    }

    /**
     * Runs the loop for one shipment. Returns the action record that was
     * written — the agent's own audit trail, readable in the admin UI and over
     * Telegram, including for the runs where it decided to do nothing.
     */
    fun run(ref: String, trigger: String): AgentAction {
        val steps = mutableListOf<AgentStep>()

        // --- gather ----------------------------------------------------------
        val report = shipments.report(ref)
            ?: return record(null, trigger, steps, "BLOCKED_BY_POLICY", "unknown shipment $ref", null)
        val reconciliation = matcher.runAndStore(ref, atReceipt = report.shipment.receivedAt != null, runBy = "agent")
        val score = risk.recomputeAndStore(ref)
        val pod = db.queryOne(
            """SELECT count(*) AS n FROM pod_certificates c JOIN shipments s ON s.id = c.shipment_id
                WHERE s.ref = ?""", ref,
        ) { it.int("n") } ?: 0

        steps += AgentStep("gather", buildString {
            append("four-way ${reconciliation.status}")
            if (reconciliation.mismatches.isNotEmpty()) {
                append(": ${reconciliation.mismatches.joinToString(", ") { "${it.code} ₹${"%.0f".format(it.deltaValue)}" }}")
            }
            append("; risk ${score.score} ${score.band}")
            append("; ${report.accounted}/${report.expectedCount} cartons")
            if (report.innerUnits > 0) append(", ${report.innerVerified}/${report.innerUnits} inners")
        })

        // --- update ----------------------------------------------------------
        val financeState = finance.applyReconciliation(ref, reconciliation, actor = "agent")
        steps += AgentStep("update", financeState?.let {
            "finance ${it.status}" + if (it.heldValue > 0) ", held ₹${"%.2f".format(it.heldValue)}" else ""
        } ?: "no finance terms on this shipment")

        // --- notify ----------------------------------------------------------
        val summary = explain(ref, reconciliation, score, report, financeState)
        notifier.notify(audience = ref, subject = "VeriTransit $ref", message = summary)
        steps += AgentStep("notify", "summary sent to the shipment's parties and supervisor")

        // --- act (policy-bounded) --------------------------------------------
        val policy = autoReleasePolicy()
        val (outcome, actDetail) = act(ref, reconciliation, score, financeState, pod > 0, policy)
        steps += AgentStep("act", actDetail)

        // --- report ----------------------------------------------------------
        steps += AgentStep("report", "action log written and audit-chained")

        return record(ref, trigger, steps, outcome, summary, policy?.id)
    }

    /**
     * The only place the agent can touch money — and it refuses far more often
     * than it proceeds. Each precondition is checked separately so the refusal
     * can name which one failed, which is what makes the log reviewable.
     */
    private fun act(
        ref: String,
        run: ReconciliationRun,
        score: RiskScore,
        state: FinanceState?,
        hasPod: Boolean,
        policy: AutoReleasePolicy?,
    ): Pair<String, String> {
        if (state == null) return "COMPLETED" to "no finance terms — nothing to act on"
        if (state.status == FinanceStatus.RELEASED) return "COMPLETED" to "already released"

        if (policy == null) {
            return "AWAITING_HUMAN" to "no active auto-release policy — routed to finance"
        }

        val blockers = buildList {
            if (state.orderValue > policy.maxOrderValue) {
                add("order value ₹${"%.2f".format(state.orderValue)} exceeds the ₹${"%.0f".format(policy.maxOrderValue)} ceiling")
            }
            if (score.score > policy.maxRiskScore) {
                add("risk ${score.score} exceeds the policy maximum of ${policy.maxRiskScore}")
            }
            if (policy.requireZeroMismatches && run.mismatches.isNotEmpty()) {
                add("${run.mismatches.size} unresolved mismatch(es)")
            }
            if (policy.requirePod && !hasPod) add("no proof-of-delivery certificate yet")
            if (state.heldValue > 0) add("₹${"%.2f".format(state.heldValue)} is held")
        }

        if (blockers.isNotEmpty()) {
            return (if (state.status == FinanceStatus.HELD) "AWAITING_HUMAN" else "BLOCKED_BY_POLICY") to
                "auto-release not attempted — ${blockers.joinToString("; ")}"
        }

        // Inside policy: the agent may raise the request, but a human checker
        // still approves it. The agent never occupies both maker and checker.
        return runCatching {
            finance.requestRelease(ref, maker = "agent", amount = null, note = "auto-raised under ${policy.name}")
            "COMPLETED" to "within policy '${policy.name}' — release requested, awaiting a human checker"
        }.getOrElse { t ->
            "AWAITING_HUMAN" to "release request refused: ${t.message}"
        }
    }

    /**
     * The explanation. Deterministic prose assembled from engine output — the
     * numbers are never generated, only formatted. A hosted LLM can be layered
     * on top to rewrite this for a given audience, but nothing downstream
     * depends on it having done so.
     */
    private fun explain(
        ref: String,
        run: ReconciliationRun,
        score: RiskScore,
        report: ShipmentReport,
        state: FinanceState?,
    ): String = buildString {
        appendLine("$ref — ${report.shipment.supplier ?: "supplier"} → ${report.shipment.buyer ?: "buyer"}")
        appendLine("Cartons: ${report.accounted}/${report.expectedCount}" +
            if (report.innerUnits > 0) ", inners ${report.innerVerified}/${report.innerUnits}" else "")
        appendLine("Four-way match: ${run.status}")
        run.mismatches.forEach {
            appendLine("  • ${it.code} (${it.pair}) ${it.detail}" +
                if (it.deltaValue > 0) " → ₹${"%.2f".format(it.deltaValue)}" else "")
        }
        state?.let {
            appendLine("Finance: ${it.status}" +
                if (it.heldValue > 0) " — ₹${"%.2f".format(it.heldValue)} held of ₹${"%.2f".format(it.orderValue)}" else "")
        }
        appendLine("Risk: ${score.score} ${score.band}")
        score.factors.take(3).forEach { appendLine("  • ${it.factor} (+${it.weight}): ${it.detail}") }
    }

    private fun record(
        ref: String?, trigger: String, steps: List<AgentStep>,
        outcome: String, reasoning: String, policyId: String?,
    ): AgentAction {
        val id = db.transaction { conn ->
            val seq = audit.append(conn, "agent", "AGENT_RUN", ref ?: "platform", buildJsonObject {
                put("trigger", trigger)
                put("outcome", outcome)
                policyId?.let { put("policy_id", it) }
            })
            conn.queryOne(
                """INSERT INTO agent_actions (shipment_id, trigger_event, steps, outcome,
                                              reasoning_summary, policy_id, audit_seq)
                   VALUES ((SELECT id FROM shipments WHERE ref = ?), ?, ?, ?, ?, ?::uuid, ?)
                   RETURNING id::text AS id""",
                ref, trigger, Jsonb(Rows.json.encodeToString(steps)), outcome, reasoning, policyId, seq,
            ) { it.str("id") } ?: error("agent action insert returned no id")
        }
        log.info("agent {} on {} → {}", trigger, ref, outcome)
        return AgentAction(id, ref, trigger, steps, outcome, reasoning, java.time.Instant.now().toString())
    }

    fun actions(ref: String?, limit: Int = 50): List<AgentAction> {
        val sql = buildString {
            append(
                """SELECT a.id::text AS id, s.ref, a.trigger_event, a.steps::text AS steps,
                          a.outcome, a.reasoning_summary, a.created_at
                     FROM agent_actions a LEFT JOIN shipments s ON s.id = a.shipment_id WHERE 1=1"""
            )
            if (ref != null) append(" AND s.ref = ?")
            append(" ORDER BY a.created_at DESC LIMIT ?")
        }
        val params = listOfNotNull(ref, limit).toTypedArray()
        return db.query(sql, *params) {
            AgentAction(
                id = it.str("id"), shipmentRef = it.strOrNull("ref"),
                triggerEvent = it.str("trigger_event"),
                steps = runCatching {
                    Rows.json.decodeFromString<List<AgentStep>>(it.str("steps"))
                }.getOrDefault(emptyList()),
                outcome = it.str("outcome"),
                reasoningSummary = it.strOrNull("reasoning_summary"),
                createdAt = it.iso("created_at"),
            )
        }
    }

    /** Answers "why was it held?" for the Telegram surface (§6.4). */
    fun explainHold(ref: String): String {
        val state = finance.state(ref) ?: return "$ref has no finance terms."
        if (state.heldValue <= 0) return "$ref is ${state.status} — nothing is held."
        val run = matcher.latest(ref)
        return buildString {
            appendLine("$ref — ₹${"%.2f".format(state.heldValue)} held of ₹${"%.2f".format(state.orderValue)}")
            run?.mismatches?.forEach {
                appendLine("  • ${it.detail} → ₹${"%.2f".format(it.deltaValue)} (${it.code})")
            }
            val score = risk.latest("shipment", ref)
            score?.let {
                appendLine("Risk ${it.score} ${it.band}: ${it.factors.take(3).joinToString("; ") { f -> f.detail }}")
            }
            append("Releasable once resolved: ₹${"%.2f".format(state.orderValue - state.heldValue)}")
        }
    }
}
