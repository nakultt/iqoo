package com.veritransit.inspector.data.documents

import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.PackingItem
import com.veritransit.inspector.data.ReceiptOutcome
import com.veritransit.inspector.data.ReceivingRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The receipt-to-paperwork handoff: what a filed receipt pre-fills, and what stays unknown. */
class PaperworkFactsTest {

    private fun record(
        supplier: String = "Bright Electronics Pvt Ltd",
        goods: String = "Consumer Electronics (Smartphones/Tabs)",
        outcome: ReceiptOutcome = ReceiptOutcome.OK,
        items: List<PackingItem> = listOf(PackingItem("ELC-3305", "Phone cartons", "Pallet", 24, 24)),
    ) = ReceivingRecord(
        id = "GRN-2025-8835",
        purchaseOrderId = "PO-2025-4471",
        packingListId = "PL-2025-4471-B",
        supplier = supplier,
        goods = goods,
        dock = "Dock 3",
        carrier = "VRL Logistics",
        outcome = outcome,
        timestamp = 1_760_000_000_000,
        items = items,
    )

    // ------------------------------------------------------------ pre-fill

    @Test
    fun `the receipt's own words pick the goods profile`() {
        assertEquals(GoodsCategory.ELECTRONICS, PaperworkFacts.guessCategory("Bright Electronics Pvt Ltd Consumer Electronics (Smartphones/Tabs)"))
        assertEquals(GoodsCategory.FABRIC, PaperworkFacts.guessCategory("Lakshmi Textile Works Textile Machinery & Spares"))
        assertNull(PaperworkFacts.guessCategory("Deccan Fasteners & Steel Industrial Hardware"))
        assertNull(PaperworkFacts.guessCategory(""))
    }

    @Test
    fun `a flagged receipt counts as a discrepancy at receipt`() {
        assertTrue(PaperworkFacts.from(record(outcome = ReceiptOutcome.SHORT)).discrepancyAtReceipt)
        assertTrue(
            PaperworkFacts.from(
                record(outcome = ReceiptOutcome.OK, items = listOf(PackingItem("E-1", "X", "Y", 4, 4, damaged = 1))),
            ).discrepancyAtReceipt,
            "a damaged line is a discrepancy even on an OK outcome",
        )
        assertEquals(false, PaperworkFacts.from(record()).discrepancyAtReceipt)
    }

    @Test
    fun `the receipt pre-fills only what it knows`() {
        val facts = PaperworkFacts.from(record())
        assertEquals(GoodsCategory.ELECTRONICS, facts.category)
        assertNull(facts.interState, "inter-State is never guessed from a receipt")
        assertEquals("", facts.declaredRupees, "the receipt carries no invoice value")
        assertNull(facts.supplierEInvoicing)
        assertEquals(MovementReason.SUPPLY, facts.reason)
        assertEquals(TransportMode.ROAD, facts.mode)
    }

    // ------------------------------------------------------------ rupees

    @Test
    fun `rupee text parses to exact paise`() {
        assertEquals(50_000_00L, PaperworkFacts.parseRupees("50000"))
        assertEquals(50_000_00L, PaperworkFacts.parseRupees("₹50,000"))
        assertEquals(50_000_50L, PaperworkFacts.parseRupees("50000.50"))
        assertEquals(50_000_50L, PaperworkFacts.parseRupees(" 50000.5 "))
        assertEquals(1_00_00_000L, PaperworkFacts.parseRupees("1,00,000"))
        assertEquals(0L, PaperworkFacts.parseRupees("0"))
    }

    @Test
    fun `malformed rupee text is unknown, never zero`() {
        assertNull(PaperworkFacts.parseRupees(""))
        assertNull(PaperworkFacts.parseRupees("  "))
        assertNull(PaperworkFacts.parseRupees("-500"))
        assertNull(PaperworkFacts.parseRupees("12.345"))
        assertNull(PaperworkFacts.parseRupees("about five thousand"))
        assertNull(PaperworkFacts.parseRupees("12.3.4"))
    }

    // ------------------------------------------------------------ toConsignment

    @Test
    fun `the consignment stays unresolved until category and inter-state are answered`() {
        val base = PaperworkFacts.from(record())
        assertNull(base.toConsignment(), "no category yet")
        assertNull(base.copy(category = GoodsCategory.ELECTRONICS).toConsignment(), "no inter-State answer yet")
        val full = base.copy(category = GoodsCategory.ELECTRONICS, interState = true)
        assertEquals(GoodsCategory.ELECTRONICS, full.toConsignment()?.category)
        assertTrue(full.toConsignment()!!.interState)
    }

    @Test
    fun `a blank value is carried as unknown, not as zero`() {
        val facts = PaperworkFacts.from(record()).copy(category = GoodsCategory.ELECTRONICS, interState = false)
        assertNull(facts.toConsignment()!!.value)
        val valued = facts.copy(declaredRupees = "60000", taxRupees = "10800", exemptRupees = "800")
        assertEquals(60_000_00L + 10_800_00L - 800_00L, valued.toConsignment()!!.value!!.paise)
    }

    @Test
    fun `lot facts clamp into the engine's domain`() {
        val facts = PaperworkFacts.from(record())
            .copy(category = GoodsCategory.FABRIC, interState = true, inLots = true, lotIndex = 3, lotCount = 0)
        val lot = facts.toConsignment()!!.lot!!
        assertEquals(1, lot.count)
        assertEquals(1, lot.index)
        assertNull(PaperworkFacts.from(record()).copy(category = GoodsCategory.FABRIC, interState = true).toConsignment()!!.lot)
    }

    // ------------------------------------------------------------ resolution through the engine

    @Test
    fun `an inter-state consignment above the threshold owes an e-way bill`() {
        val paperwork = ConsignmentPaperwork.resolve(
            PaperworkFacts.from(record())
                .copy(category = GoodsCategory.ELECTRONICS, interState = true, declaredRupees = "60000")
                .toConsignment()!!,
        )
        assertTrue(paperwork.required.any { it.id == "eway-carry" }, "required: ${paperwork.required.map { it.id }}")
        assertTrue(paperwork.required.none { it.id == "bill-of-entry-carry" }, "not imported, so no bill of entry")
        assertEquals(
            listOf("e-invoice-irn"),
            paperwork.undetermined.map { it.id },
            "the unknown e-invoicing status is the only open fact",
        )
    }

    @Test
    fun `an unanswered value leaves the value-triggered rules undetermined, not dismissed`() {
        val paperwork = ConsignmentPaperwork.resolve(
            PaperworkFacts.from(record())
                .copy(category = GoodsCategory.ELECTRONICS, interState = false)
                .toConsignment()!!,
        )
        val undetermined = paperwork.undetermined.map { it.id }
        assertTrue("eway-carry" in undetermined, "undetermined: $undetermined")
        assertTrue(paperwork.required.none { it.id == "eway-carry" })
    }

    @Test
    fun `a discrepancy on a common-carrier delivery pulls in the carrier-notice rule`() {
        val flagged = PaperworkFacts.from(record(outcome = ReceiptOutcome.SHORT))
            .copy(
                category = GoodsCategory.ELECTRONICS,
                interState = true,
                declaredRupees = "60000",
                byCommonCarrier = true,
            )
        val paperwork = ConsignmentPaperwork.resolve(flagged.toConsignment()!!)
        assertTrue(
            paperwork.required.any { it.id == "carrier-notice" },
            "required: ${paperwork.required.map { it.id }}",
        )
    }
}
