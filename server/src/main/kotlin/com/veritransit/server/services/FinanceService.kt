package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.crypto.Signer
import com.veritransit.server.db.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection

/**
 * §5.2 — the finance state machine.
 *
 * ```
 * AWAITING → VERIFIED → RELEASE_PENDING → RELEASED
 *          ↘ HELD (mismatch) → RESOLVED → RELEASE_PENDING
 * ```
 *
 * **VeriTransit never moves money.** It emits a signed Release Certificate or
 * Hold Notice that the payer's own system acts on. That boundary is what keeps
 * the platform a trust layer rather than a regulated payments business, so
 * nothing in this class talks to a bank — the furthest it goes is handing a
 * signed statement to [WebhookService].
 */
class FinanceService(
    private val db: Database,
    private val signer: Signer,
    private val audit: AuditLog,
    private val webhooks: WebhookService,
    private val matcher: FourWayMatcher,
) {

    class StateError(message: String) : IllegalStateException(message)

    fun state(ref: String): FinanceState? = db.queryOne(
        """SELECT s.ref AS shipment_ref, f.currency, f.order_value, f.status,
                  f.released_value, f.held_value, f.terms::text AS terms
             FROM finance_terms f JOIN shipments s ON s.id = f.shipment_id WHERE s.ref = ?""",
        ref, map = Rows::financeState,
    )

    /** Creates the finance object when a shipment first gets an order value. */
    fun ensureTerms(ref: String, orderValue: Double, terms: Map<String, String>) {
        db.update(
            """INSERT INTO finance_terms (shipment_id, order_value, terms, status)
               SELECT id, ?, ?, 'AWAITING' FROM shipments WHERE ref = ?
               ON CONFLICT (shipment_id) DO UPDATE SET order_value = EXCLUDED.order_value,
                                                       terms = EXCLUDED.terms""",
            orderValue, Jsonb(Rows.json.encodeToString(terms)), ref,
        )
    }

    /**
     * Applies a reconciliation outcome. This is the only path from verification
     * into money: a clean match makes the value releasable, any mismatch holds
     * exactly the disputed delta and not a rupee more (§5.2 partial release).
     */
    fun applyReconciliation(ref: String, run: ReconciliationRun, actor: String = "agent"): FinanceState? =
        db.transaction { conn ->
            val current = state(ref) ?: return@transaction null
            val held = run.heldValue.coerceAtMost(current.orderValue)

            val next = when {
                run.status == MatchStatus.MATCHED -> FinanceStatus.VERIFIED
                held > 0 -> FinanceStatus.HELD
                // A partial match with nothing quantifiable to hold (a missing
                // document, say) blocks release without withholding value.
                else -> FinanceStatus.AWAITING
            }

            conn.update(
                """UPDATE finance_terms SET status = ?::finance_status, held_value = ?, updated_at = now()
                   WHERE shipment_id = (SELECT id FROM shipments WHERE ref = ?)""",
                next.name, held, ref,
            )

            if (next == FinanceStatus.HELD) {
                val seq = audit.append(conn, actor, "PAYMENT_HELD", ref, buildJsonObject {
                    put("amount", held)
                    put("reasons", run.mismatches.joinToString(",") { it.code.name })
                })
                recordEvent(conn, ref, "HOLD", actor, held, seq, buildJsonObject {
                    put("reasons", run.mismatches.joinToString(",") { it.code.name })
                    put("releasable_on_resolution", current.orderValue - held)
                })
                webhooks.enqueue(conn, "HOLD_NOTICE", ref, signedNotice(ref, "HOLD_NOTICE", held, run))
            }
            state(ref)
        }

    /**
     * Maker half of maker-checker. Records the intent; no value moves yet.
     * Asking for less than the verified balance is a partial release — the
     * remainder stays releasable after the checker approves this one.
     */
    fun requestRelease(ref: String, maker: String, amount: Double?, note: String?): FinanceActionResponse =
        db.transaction { conn ->
            val st = state(ref) ?: throw StateError("shipment $ref has no finance terms")
            if (st.status == FinanceStatus.RELEASED) throw StateError("already released")
            if (st.status == FinanceStatus.RELEASE_PENDING) {
                throw StateError("a release is already awaiting a checker — approve it before requesting more")
            }
            if (st.status == FinanceStatus.HELD) {
                throw StateError("held for ₹${"%.2f".format(st.heldValue)} — resolve the mismatch first")
            }
            if (st.status == FinanceStatus.AWAITING) {
                throw StateError("not yet verified — run reconciliation before requesting release")
            }

            val releasable = releasableValue(st)
            val ask = amount ?: releasable
            if (ask > releasable + 0.005) {
                throw StateError("cannot release ₹${"%.2f".format(ask)}; only ₹${"%.2f".format(releasable)} is verified")
            }
            if (ask <= 0.0) {
                throw StateError("release amount must be positive")
            }

            conn.update(
                """UPDATE finance_terms SET status = 'RELEASE_PENDING', updated_at = now()
                   WHERE shipment_id = (SELECT id FROM shipments WHERE ref = ?)""", ref,
            )
            val seq = audit.append(conn, maker, "RELEASE_REQUESTED", ref, buildJsonObject {
                put("amount", ask); put("note", note ?: "")
            })
            recordEvent(conn, ref, "RELEASE_REQUEST", maker, ask, seq, buildJsonObject {
                put("note", note ?: "")
            })

            FinanceActionResponse(
                ref, FinanceStatus.RELEASE_PENDING, st.releasedValue, st.heldValue,
                message = "release of ₹${"%.2f".format(ask)} awaiting a checker",
            )
        }

    /**
     * Checker half. The checker must be a different person than the maker —
     * enforced here on the server, so a compromised Telegram chat cannot
     * approve its own request (§5.5).
     *
     * Approving a partial request releases exactly what was asked and returns
     * to VERIFIED while anything of the verified balance remains releasable —
     * §5.2 partial release. The shipment only reaches RELEASED once the
     * verified amount is exhausted.
     */
    fun approveRelease(ref: String, checker: String): FinanceActionResponse = db.transaction { conn ->
        val st = state(ref) ?: throw StateError("shipment $ref has no finance terms")
        if (st.status != FinanceStatus.RELEASE_PENDING) {
            throw StateError("no release is pending (state is ${st.status})")
        }

        val request = conn.queryOne(
            """SELECT actor, amount FROM payment_events pe JOIN shipments s ON s.id = pe.shipment_id
                WHERE s.ref = ? AND pe.kind = 'RELEASE_REQUEST'
                ORDER BY pe.created_at DESC LIMIT 1""", ref,
        ) { it.str("actor") to it.dbl("amount") } ?: throw StateError("no release request to approve")

        val (maker, amount) = request
        if (maker.equals(checker, ignoreCase = true)) {
            throw StateError("maker-checker: $checker requested this release and cannot approve it")
        }

        val releasable = releasableValue(st)
        if (amount > releasable + 0.005) {
            throw StateError(
                "cannot approve ₹${"%.2f".format(amount)}; only ₹${"%.2f".format(releasable)} is releasable now — " +
                    "the hold grew or the terms changed since the request was made, so re-request"
            )
        }

        val remaining = releasable - amount
        // RELEASED only when the verified balance is exhausted; otherwise the
        // shipment stays VERIFIED so the remainder can still be released.
        val next = if (remaining <= 0.005) FinanceStatus.RELEASED else FinanceStatus.VERIFIED
        val released = st.releasedValue + amount
        conn.update(
            """UPDATE finance_terms SET status = ?::finance_status, released_value = ?, updated_at = now()
               WHERE shipment_id = (SELECT id FROM shipments WHERE ref = ?)""",
            next.name, released, ref,
        )

        val approveSeq = audit.append(conn, checker, "RELEASE_APPROVED", ref, buildJsonObject {
            put("amount", amount); put("maker", maker)
        })
        recordEvent(conn, ref, "RELEASE_APPROVED", checker, amount, approveSeq, buildJsonObject {
            put("maker", maker); put("checker", checker)
        })

        val certificate = signedNotice(ref, "RELEASE_CERTIFICATE", amount, null)
        val certSeq = audit.append(conn, "system", "CERTIFICATE_ISSUED", ref, buildJsonObject {
            put("kind", "RELEASE_CERTIFICATE"); put("amount", amount)
        })
        recordEvent(conn, ref, "CERTIFICATE_ISSUED", "system", amount, certSeq, buildJsonObject {
            put("key_id", signer.keyId)
        })
        webhooks.enqueue(conn, "RELEASE_CERTIFICATE", ref, certificate)

        FinanceActionResponse(
            ref, next, released, st.heldValue,
            certificateId = certificate["certificate_id"],
            message = if (next == FinanceStatus.RELEASED) "released ₹${"%.2f".format(amount)} — certificate issued to the payer"
            else "released ₹${"%.2f".format(amount)} — ₹${"%.2f".format(remaining)} of the verified balance remains releasable",
        )
    }

    /** Explicit hold, e.g. a supervisor acting on evidence the engine has not seen. */
    fun hold(ref: String, actor: String, amount: Double, reason: String): FinanceActionResponse =
        db.transaction { conn ->
            val st = state(ref) ?: throw StateError("shipment $ref has no finance terms")
            val held = (st.heldValue + amount).coerceAtMost(st.orderValue - st.releasedValue)
            conn.update(
                """UPDATE finance_terms SET status = 'HELD', held_value = ?, updated_at = now()
                   WHERE shipment_id = (SELECT id FROM shipments WHERE ref = ?)""", held, ref,
            )
            val seq = audit.append(conn, actor, "PAYMENT_HELD", ref, buildJsonObject {
                put("amount", amount); put("reason", reason)
            })
            recordEvent(conn, ref, "HOLD", actor, amount, seq, buildJsonObject { put("reason", reason) })
            webhooks.enqueue(conn, "HOLD_NOTICE", ref, signedNotice(ref, "HOLD_NOTICE", held, null))
            FinanceActionResponse(ref, FinanceStatus.HELD, st.releasedValue, held, message = "held: $reason")
        }

    /**
     * A hold is cleared by evidence, not by a note (§5.2: mismatch → hold →
     * credit note / recount → *rerun match* → release). The reconciliation is
     * re-run here: if the numbers still support the hold, only an explicit
     * supervisor override clears it — and that decision is audit-chained with
     * its reason.
     */
    fun resolve(
        ref: String,
        actor: String,
        note: String,
        override: Boolean = false,
        overrideReason: String? = null,
    ): FinanceActionResponse = db.transaction { conn ->
        val st = state(ref) ?: throw StateError("shipment $ref has no finance terms")
        if (st.status != FinanceStatus.HELD) throw StateError("nothing is held on $ref")

        val fresh = matcher.run(ref, atReceipt = true, runBy = actor)
        if (fresh.heldValue > 0 && !override) {
            throw StateError(
                "reconciliation still holds ₹${"%.2f".format(fresh.heldValue)} — attach the corrected " +
                    "paperwork or recount and reconcile again, or resolve with override=true (audit-chained)",
            )
        }

        conn.update(
            """UPDATE finance_terms SET status = 'VERIFIED', held_value = 0, updated_at = now()
               WHERE shipment_id = (SELECT id FROM shipments WHERE ref = ?)""", ref,
        )
        val seq = audit.append(conn, actor, "HOLD_RESOLVED", ref, buildJsonObject {
            put("note", note)
            put("fresh_held_value", fresh.heldValue)
            if (override) {
                put("override", true)
                put("override_reason", overrideReason ?: "")
            }
        })
        recordEvent(conn, ref, "RESOLVED", actor, st.heldValue, seq, buildJsonObject {
            put("note", note)
            if (override) put("override", "true")
        })
        FinanceActionResponse(ref, FinanceStatus.VERIFIED, st.releasedValue, 0.0,
            message = if (fresh.heldValue > 0)
                "hold cleared by override — ₹${"%.2f".format(fresh.heldValue)} the engine still disputes is noted in the audit chain"
            else "hold cleared — release may be requested again")
    }

    fun events(ref: String): List<Map<String, String>> = db.query(
        """SELECT pe.kind::text AS kind, pe.actor, pe.amount, pe.created_at, pe.payload::text AS payload
             FROM payment_events pe JOIN shipments s ON s.id = pe.shipment_id
            WHERE s.ref = ? ORDER BY pe.created_at""", ref,
    ) {
        mapOf(
            "kind" to it.str("kind"), "actor" to it.str("actor"),
            "amount" to (it.dblOrNull("amount")?.toString() ?: ""),
            "at" to it.iso("created_at"), "payload" to it.str("payload"),
        )
    }

    fun releaseQueue(status: FinanceStatus): List<FinanceState> = db.query(
        """SELECT s.ref AS shipment_ref, f.currency, f.order_value, f.status,
                  f.released_value, f.held_value, f.terms::text AS terms
             FROM finance_terms f JOIN shipments s ON s.id = f.shipment_id
            WHERE f.status = ?::finance_status ORDER BY f.updated_at DESC""",
        status.name, map = Rows::financeState,
    )

    // ------------------------------------------------------------- internals

    /**
     * §5.2 terms: the portion due on verified delivery. The balance follows its
     * own schedule (net-30) and is not this platform's to release.
     */
    private fun releasableValue(st: FinanceState): Double {
        val pct = st.terms["on_verified_delivery_pct"]?.toDoubleOrNull() ?: 100.0
        val due = st.orderValue * pct / 100.0
        return (due - st.releasedValue - st.heldValue).coerceAtLeast(0.0)
    }

    private fun recordEvent(
        conn: Connection, ref: String, kind: String, actor: String,
        amount: Double?, auditSeq: Long, payload: kotlinx.serialization.json.JsonObject,
    ) {
        conn.update(
            """INSERT INTO payment_events (shipment_id, kind, actor, amount, payload, audit_seq)
               SELECT id, ?::payment_event_kind, ?, ?, ?, ? FROM shipments WHERE ref = ?""",
            kind, actor, amount, Jsonb(payload.toString()), auditSeq, ref,
        )
    }

    /**
     * The signed statement the payer acts on. It certifies *facts and their
     * evidence hashes*, never a guarantee — the wording matters legally (§12).
     */
    private fun signedNotice(
        ref: String, kind: String, amount: Double, run: ReconciliationRun?,
    ): Map<String, String> {
        val body = buildJsonObject {
            put("kind", kind)
            put("shipment_ref", ref)
            put("amount", amount)
            put("currency", "INR")
            put("issued_at", java.time.Instant.now().toString())
            put("statement", if (kind == "HOLD_NOTICE")
                "VeriTransit certifies the verification facts below; payment of the stated amount is withheld pending resolution."
            else
                "VeriTransit certifies the verification facts below. This is a statement of verified fact, not a payment guarantee.")
            run?.let { put("mismatches", it.mismatches.joinToString(",") { m -> "${m.code}:${m.deltaValue}" }) }
        }
        return mapOf(
            "certificate_id" to java.util.UUID.randomUUID().toString(),
            "body" to body.toString(),
            "signature" to signer.signToB64(body.toString().toByteArray()),
            "key_id" to signer.keyId,
        )
    }
}
