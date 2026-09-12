package com.veritransit.server

import com.veritransit.core.*
import com.veritransit.server.db.Database
import com.veritransit.server.services.FourWayMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * §11 — four-way matcher property tests and the finance rules that depend on
 * them, run against the real database.
 *
 * These are integration tests on purpose. The matcher's correctness is mostly a
 * question of whether its SQL counts the right boxes — a mocked database would
 * test the mock. When no database is reachable the tests skip rather than fail,
 * so a checkout without `db/scripts/setup.sh` still builds green.
 */
class MatcherAndFinanceTest {

    private val db: Database? = runCatching {
        Database(Config.load()).also { it.query("SELECT 1") { rs -> rs.getInt(1) } }
    }.getOrNull()

    private fun requireDb(): Database {
        assumeTrue(db != null, "no veritransit database reachable — skipping integration test")
        return db!!
    }

    @Test
    fun `the running example holds exactly the disputed delta`() {
        val matcher = FourWayMatcher(requireDb())
        val run = matcher.run("SHP-2026-090231", atReceipt = true)

        assertEquals(MatchStatus.MISMATCHED, run.status)

        // §5.1: invoiced 60 against PO 55 at ₹17,480 → ₹87,400.
        val overBill = run.mismatches.single {
            it.code == MismatchCode.QTY_MISMATCH && it.pair == MatchPair.PO_VS_INVOICE
        }
        assertEquals(55.0, overBill.orderedQty)
        assertEquals(60.0, overBill.invoicedQty)
        assertEquals(87_400.0, overBill.deltaValue)

        // §2.2: a sealed master declared 10 and yielded 9 → one unit's value.
        val shortage = run.mismatches.single { it.code == MismatchCode.INNER_SHORTAGE }
        assertEquals(59.0, shortage.physicalQty)
        assertEquals(17_480.0, shortage.deltaValue)

        assertEquals(104_880.0, run.heldValue)
    }

    @Test
    fun `holding is never more than the order value`() {
        val matcher = FourWayMatcher(requireDb())
        for (ref in listOf("SHP-2026-090187", "SHP-2026-090231", "SHP-2026-090198", "SHP-2026-090244")) {
            val run = matcher.run(ref, atReceipt = true)
            val orderValue = requireDb().queryOne(
                """SELECT f.order_value FROM finance_terms f JOIN shipments s ON s.id = f.shipment_id
                    WHERE s.ref = ?""", ref,
            ) { it.getDouble("order_value") } ?: continue
            assertTrue(
                run.heldValue <= orderValue + 0.01,
                "$ref would hold ₹${run.heldValue} against an order of ₹$orderValue",
            )
        }
    }

    @Test
    fun `a clean shipment produces no mismatches and holds nothing`() {
        val run = FourWayMatcher(requireDb()).run("SHP-2026-090187", atReceipt = true)
        assertEquals(MatchStatus.MATCHED, run.status, "unexpected: ${run.mismatches}")
        assertEquals(0.0, run.heldValue)
    }

    @Test
    fun `a shipment with no invoice is partial, not mismatched, and holds nothing`() {
        // §5.1: a missing document blocks release but is not a quantified dispute —
        // withholding money over paperwork that has not arrived would be wrong.
        val run = FourWayMatcher(requireDb()).run("SHP-2026-090244", atReceipt = false)
        assertEquals(MatchStatus.PARTIAL, run.status)
        assertTrue(run.mismatches.any { it.code == MismatchCode.DOC_MISSING })
        assertEquals(0.0, run.heldValue)
    }

    @Test
    fun `the dispatch-time and receipt-time runs differ by the inner shortage`() {
        val matcher = FourWayMatcher(requireDb())
        val atDispatch = matcher.run("SHP-2026-090231", atReceipt = false)
        val atReceipt = matcher.run("SHP-2026-090231", atReceipt = true)
        // Everything that left the dock was scanned out; the shortage only
        // surfaces when the receiver opens the carton.
        assertTrue(
            atReceipt.heldValue > atDispatch.heldValue,
            "receipt run should hold more once the short carton is opened",
        )
        assertEquals(17_480.0, atReceipt.heldValue - atDispatch.heldValue)
    }

    @Test
    fun `every mismatch carries the numbers that justify it`() {
        val matcher = FourWayMatcher(requireDb())
        for (ref in listOf("SHP-2026-090231", "SHP-2026-090198")) {
            matcher.run(ref, atReceipt = true).mismatches
                .filter { it.deltaValue > 0 }
                .forEach {
                    assertTrue(it.detail.isNotBlank(), "$ref ${it.code} has no explanation")
                    assertTrue(
                        it.orderedQty != null || it.invoicedQty != null || it.physicalQty != null,
                        "$ref ${it.code} holds ₹${it.deltaValue} without stating any quantity",
                    )
                }
        }
    }

    @Test
    fun `risk bands never disagree with their score`() {
        val db = requireDb()
        val rows = db.query("SELECT score, band FROM risk_scores") {
            it.getInt("score") to RiskBand.valueOf(it.getString("band"))
        }
        assertTrue(rows.isNotEmpty())
        rows.forEach { (score, band) ->
            assertEquals(RiskBands.of(score), band, "score $score is filed under $band")
        }
    }

    @Test
    fun `the audit chain verifies`() {
        val status = com.veritransit.server.crypto.AuditLog(requireDb()).verifyChain()
        assertTrue(status.ok, "audit chain broken at ${status.firstBadSeq}: ${status.detail}")
    }
}
