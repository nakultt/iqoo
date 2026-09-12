package com.veritransit.inspector.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContainerCheckTest {

    private val records = BotData.seed(0L)
    private val canonical = records.first { it.id == "VT-2024-8841" }

    @Test
    fun `resolves ewb with messy formatting`() {
        val found = ContainerCheck.resolve("please check ewb 7819 2044 for me", records)
        assertEquals(listOf(canonical), found)
    }

    @Test
    fun `resolves vehicle with and without spaces`() {
        // Two vault entries share TN 38 BX 4491; both must come back, newest first.
        val spaced = ContainerCheck.resolve("TN 38 BX 4491", records)
        assertEquals(listOf(canonical, records.first { it.id == "VT-2024-8838" }), spaced)
        val compact = ContainerCheck.resolve("status of tn38bx4491", records)
        assertEquals(spaced, compact)
    }

    @Test
    fun `resolves inspection record id`() {
        assertEquals(listOf(canonical), ContainerCheck.resolve("VT-2024-8841", records))
        assertEquals(listOf(canonical), ContainerCheck.resolve("vt 2024 8841", records))
    }

    @Test
    fun `matches cargo by item keyword`() {
        val found = ContainerCheck.resolveByKeyword("is the cereal shipment cleared?", records)
        assertTrue(found.isNotEmpty())
        assertEquals("Packaged Foodstuffs (Dry Cereals)", found.first().cargo)
    }

    @Test
    fun `receipt with ewb resolves to the inspected container`() {
        val receipt = """
            TAX INVOICE
            Consignor: Bright Electronics Pvt Ltd
            EWB-7819-2044
            Vehicle: TN 38 BX 4491
            1 x Thermal POS Printer
        """.trimIndent()
        val match = assertNotNull(ContainerCheck.analyzeReceipt(receipt, records))
        assertEquals(canonical, match.record)
        assertEquals("reference number on the receipt", match.matchedBy)
    }

    @Test
    fun `receipt with only cargo description falls back to keyword match`() {
        val match = assertNotNull(ContainerCheck.analyzeReceipt("Delivery of 10 x Bobbin Spindle Set", records))
        assertEquals("Textile Machinery & Spares", match.record?.cargo)
        assertEquals("cargo description on the receipt", match.matchedBy)
    }

    @Test
    fun `unmatched receipt yields null`() {
        assertEquals(null, ContainerCheck.analyzeReceipt("lunch order 2 pizzas", records))
    }

    @Test
    fun `report lists shortage and unlisted items with verdict`() {
        val report = ContainerCheck.containerReport(canonical, now = 96 * 60_000L)
        assertTrue("⚠️ REVIEW" in report)
        assertTrue("expected 4, found 3 (shortage)" in report)
        assertTrue("not on manifest, found 1 (unlisted)" in report)
        assertTrue(OfficerAction.RECOUNT.title in report)
    }

    @Test
    fun `clean report shows all units match`() {
        val clean = records.first { it.id == "VT-2024-8838" }
        val report = ContainerCheck.containerReport(clean, now = 12 * 60_000L)
        assertTrue("✅ PASSED" in report)
        assertTrue("all units match" in report)
    }

    @Test
    fun `html is escaped in user echoed text`() {
        assertEquals("a &lt;b&gt; &amp; c", Html.esc("a <b> & c"))
    }

    @Test
    fun `message handler answers stats question`() {
        val handler = MessageHandler(records) { 0L }
        val reply = handler.answerQuestion("give me the shift stats")
        assertTrue("Inspections" in reply)
    }

    @Test
    fun `message handler routes plain ewb question to container check`() {
        val handler = MessageHandler(records) { 0L }
        val reply = handler.answerQuestion("EWB-3315-8890")
        assertTrue("Container Check" in reply)
        assertTrue("⚠️ REVIEW" in reply)
    }
}
