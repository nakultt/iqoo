package com.veritransit.dashboard

import com.veritransit.dashboard.documents.ConsignmentDocument
import com.veritransit.dashboard.documents.ConsignmentFacts
import com.veritransit.dashboard.documents.ConsignmentPaperwork
import com.veritransit.dashboard.documents.GoodsCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The receipt-to-paperwork handoff on the web: pre-fill from the receipt, answer the rest on the page. */
class ConsignmentFactsTest {

    private fun record(
        supplier: String = "Bright Electronics Pvt Ltd",
        goods: String = "Consumer Electronics (Smartphones/Tabs)",
        carrier: String = "VRL Logistics",
        outcome: ReceiptOutcome = ReceiptOutcome.SHORT,
        items: List<PackingItem> = listOf(PackingItem("ELC-3305", "Phone cartons", "Retail Pallet", 24, 22)),
    ) = ReceivingRecord(
        id = "GRN-2025-8835",
        purchaseOrderId = "PO-2025-4471",
        packingListId = "PL-2025-4471-B",
        supplier = supplier,
        goods = goods,
        dock = "Dock 3",
        carrier = carrier,
        outcome = outcome,
        timestamp = 4_000,
        items = items,
    )

    @Test
    fun `the receipt's own words pick the goods family or refuse to`() {
        assertEquals(GoodsCategory.ELECTRONICS, ConsignmentFacts.guessCategory("Bright Electronics Pvt Ltd Consumer Electronics (Smartphones/Tabs)"))
        assertEquals(GoodsCategory.FABRIC, ConsignmentFacts.guessCategory("Lakshmi Textile Works Textile Machinery & Spares"))
        assertNull(ConsignmentFacts.guessCategory("Sahyadri Foods LLP Packaged Foodstuffs (Dry Cereals)"))
    }

    @Test
    fun `the receipt decides the discrepancy and implies the carrier handover`() {
        val facts = ConsignmentFacts.fromRecord(record())
        assertTrue(facts.discrepancyAtReceipt, "a SHORT outcome is a discrepancy at receipt")
        assertTrue(facts.byCommonCarrier, "a named carrier is the common-carrier handover")
        assertEquals(GoodsCategory.ELECTRONICS, facts.category)
        assertNull(facts.interState, "inter-State is never guessed from a receipt")
        assertNull(facts.declaredPaise, "the receipt carries no invoice value")

        val clean = ConsignmentFacts.fromRecord(
            record(
                outcome = ReceiptOutcome.OK,
                carrier = "",
                items = listOf(PackingItem("ELC-3305", "Phone cartons", "Retail Pallet", 24, 24)),
            ),
        )
        assertFalse(clean.discrepancyAtReceipt)
        assertFalse(clean.byCommonCarrier, "no carrier named, no handover implied")
    }

    @Test
    fun `rupee text parses to exact paise, malformed stays unknown`() {
        assertEquals(50_000_00L, ConsignmentFacts.parseRupees("₹50,000"))
        assertEquals(50_000_50L, ConsignmentFacts.parseRupees("50000.5"))
        assertNull(ConsignmentFacts.parseRupees(""))
        assertNull(ConsignmentFacts.parseRupees("-5"))
        assertNull(ConsignmentFacts.parseRupees("12.345"))
        assertNull(ConsignmentFacts.parseRupees(null))
    }

    @Test
    fun `a plain page view keeps the receipt defaults, a submitted form overrides`() {
        // First view: no query at all — defaults intact, inter-State unanswered.
        val viewed = ConsignmentFacts.fromRecord(record(), emptyMap())
        assertTrue(viewed.byCommonCarrier)
        assertNull(viewed.interState)
        assertEquals(GoodsCategory.ELECTRONICS, viewed.category)

        // Submitted: unchecked means unchecked, even for the carrier default.
        val submitted = ConsignmentFacts.fromRecord(record(), mapOf("submitted" to "1", "inter" to "true"))
        assertFalse(submitted.byCommonCarrier, "unchecked box on a submitted form is an answer")
        assertTrue(submitted.interState == true, "office answered: inter-State")

        val answered = ConsignmentFacts.fromRecord(
            record(),
            mapOf(
                "submitted" to "1", "category" to "fabric", "declared" to "60000",
                "tax" to "10800", "exempt" to "800", "einvoice" to "false", "common_carrier" to "on",
            ),
        )
        assertEquals(GoodsCategory.FABRIC, answered.category)
        assertEquals(60_000_00L + 10_800_00L - 800_00L, answered.value!!.paise)
        assertTrue(answered.supplierEInvoicing == false, "office answered: not e-invoicing-notified")
        assertTrue(answered.byCommonCarrier)
    }

    @Test
    fun `an explicitly chosen unknown clears a guess`() {
        val cleared = ConsignmentFacts.fromRecord(record(), mapOf("submitted" to "1", "category" to ""))
        assertNull(cleared.category, "the office may decline the receipt's guess")
    }

    @Test
    fun `the claim documents resolve for a damaged common-carrier delivery`() {
        val facts = ConsignmentFacts.fromRecord(record())
        val paperwork = ConsignmentPaperwork.resolve(facts.toConsignment())
        assertTrue(paperwork.required.any { it.id == "carrier-notice" }, "required: ${paperwork.required.map { it.id }}")
        assertTrue(paperwork.required.any { it.id == "lorry-receipt" })
        assertTrue(paperwork.customary.any { it.document == ConsignmentDocument.CREDIT_NOTE })
        assertTrue(
            paperwork.undetermined.any { it.id == "eway-carry" },
            "the value question is unanswered, so e-way cannot be settled",
        )
    }

    @Test
    fun `an unanswered value leaves e-way undetermined on the web too`() {
        val paperwork = ConsignmentPaperwork.resolve(
            ConsignmentFacts.fromRecord(record(), mapOf("submitted" to "1")).toConsignment(),
        )
        val undetermined = paperwork.undetermined.map { it.id }
        assertTrue("eway-carry" in undetermined, "undetermined: $undetermined")
    }
}
