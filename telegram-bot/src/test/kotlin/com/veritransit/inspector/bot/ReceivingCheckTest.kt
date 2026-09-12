package com.veritransit.inspector.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceivingCheckTest {

    private val records = BotData.seed(0L)
    private val canonical = records.first { it.id == "GRN-2025-8841" }

    @Test
    fun `resolves purchase order with messy formatting`() {
        val found = ReceivingCheck.resolve("please check po 2025 4471 for me", records)
        // Three deliveries sit on PO-2025-4471; all must come back, newest first.
        assertEquals(
            listOf("GRN-2025-8841", "GRN-2025-8835", "GRN-2025-8799", "GRN-2025-8759"),
            found.map { it.id },
        )
    }

    @Test
    fun `resolves packing-list reference with and without the revision suffix`() {
        val full = ReceivingCheck.resolve("PL-2025-4471-A", records)
        assertEquals(listOf(canonical), full)
        val compact = ReceivingCheck.resolve("pl 2025 4471 a", records)
        assertEquals(full, compact)
    }

    @Test
    fun `resolves GRN record id`() {
        assertEquals(listOf(canonical), ReceivingCheck.resolve("GRN-2025-8841", records))
        assertEquals(listOf(canonical), ReceivingCheck.resolve("grn 2025 8841", records))
    }

    @Test
    fun `resolves a SKU straight to the deliveries that carry it`() {
        val found = ReceivingCheck.resolve("where is ELC-2710?", records)
        assertEquals(listOf(canonical), found)
    }

    @Test
    fun `matches goods by item keyword`() {
        val found = ReceivingCheck.resolveByKeyword("is the cereal delivery accepted?", records)
        assertTrue(found.isNotEmpty())
        assertEquals("Packaged Foodstuffs (Dry Cereals)", found.first().goods)
    }

    @Test
    fun `receipt with a purchase order resolves to the received delivery`() {
        val receipt = """
            DELIVERY NOTE
            Supplier: Bright Electronics Pvt Ltd
            PO-2025-4471
            Packing list: PL-2025-4471-A
            1 x Thermal POS Printer
        """.trimIndent()
        val match = assertNotNull(ReceivingCheck.analyzeReceipt(receipt, records))
        assertEquals(canonical, match.record)
        assertEquals("purchase order on the receipt", match.matchedBy)
    }

    @Test
    fun `receipt with only a goods description falls back to keyword match`() {
        val match = assertNotNull(ReceivingCheck.analyzeReceipt("Delivery of 10 x Bobbin Spindle Set", records))
        assertEquals("Textile Machinery & Spares", match.record?.goods)
        assertEquals("goods description on the receipt", match.matchedBy)
    }

    @Test
    fun `unmatched receipt yields null`() {
        assertEquals(null, ReceivingCheck.analyzeReceipt("lunch order 2 pizzas", records))
    }

    @Test
    fun `report lists short and unlisted lines with the outcome`() {
        val report = ReceivingCheck.receiptReport(canonical, now = 96 * 60_000L)
        assertTrue("⛔ MISMATCH" in report)
        assertTrue("packed 4, received 3 (short by 1)" in report)
        assertTrue("not on the packing list, received 1 (unlisted)" in report)
        assertTrue(ReceivingAction.FLAG.title in report)
    }

    @Test
    fun `clean report shows all lines matching`() {
        val clean = records.first { it.id == "GRN-2025-8838" }
        val report = ReceivingCheck.receiptReport(clean, now = 12 * 60_000L)
        assertTrue("✅ OK" in report)
        assertTrue("all lines match" in report)
    }

    @Test
    fun `over-count is reported as an over, not normalized away`() {
        val over = records.first { it.id == "GRN-2025-8784" }
        val report = ReceivingCheck.receiptReport(over, now = 12 * 60_000L)
        assertTrue("⚠️ OVER" in report)
        assertTrue("(over by 2)" in report, "over line missing from report:\n$report")
    }

    @Test
    fun `a short delivery recommends a re-count`() {
        val short = records.first { it.id == "GRN-2025-8835" }
        val report = ReceivingCheck.receiptReport(short, now = 12 * 60_000L)
        assertTrue("⚠️ SHORT" in report)
        assertTrue(ReceivingAction.RECOUNT.title in report)
    }

    @Test
    fun `damaged goods are reported apart from a quantity mismatch`() {
        val damaged = records.first { it.id == "GRN-2025-8768" }
        val report = ReceivingCheck.receiptReport(damaged, now = 12 * 60_000L)
        assertTrue("⛔ MISMATCH" in report)
        assertTrue("damaged 3 (damaged)" in report, "damage line missing from report:\n$report")
    }

    @Test
    fun `html is escaped in user echoed text`() {
        assertEquals("a &lt;b&gt; &amp; c", Html.esc("a <b> & c"))
    }

    @Test
    fun `message handler answers stats question`() {
        val handler = MessageHandler(records) { 0L }
        val reply = handler.answerQuestion("give me the shift stats")
        assertTrue("Receipts" in reply)
    }

    @Test
    fun `message handler routes a plain purchase-order question to the receipt check`() {
        val handler = MessageHandler(records) { 0L }
        val reply = handler.answerQuestion("PO-2025-4471")
        assertTrue("Receipt Report" in reply)
        assertTrue("⛔ MISMATCH" in reply)
    }

    @Test
    fun `message handler routes a flagged question to the flagged list`() {
        val handler = MessageHandler(records) { 0L }
        val reply = handler.answerQuestion("any flagged deliveries?")
        assertTrue("Flagged receipts" in reply)
    }
}
