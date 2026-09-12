package com.veritransit.inspector.ai

import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.PackingItem
import com.veritransit.inspector.data.PackingList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * JVM-side checks for the dock-count mapping — the part that turns the model's
 * JSON into receipt lines. These run without a device, so a regression here
 * fails CI rather than waiting for the next NPU bench run.
 */
class ReceivingAiCountTest {

    private fun packingList(vararg items: Triple<String, String, Int>) = PackingList(
        purchaseOrderId = "PO-2025-4471",
        packingListId = "PL-2025-4471-A",
        supplier = "Bright Electronics Pvt Ltd",
        goods = "Consumer Electronics",
        dock = "Dock 3 · Central DC",
        carrier = "BlueDart Surface",
        items = items.map { PackingItem(it.first, it.second, "Packed line", it.third, it.third) },
    )

    @Test
    fun `over-count is preserved, not clamped to the packed quantity`() {
        val l = packingList(Triple("ELC-2710", "Dell UltraSharp 27\" Monitor", 3))
        val parsed = ReceivingAi.Count(
            items = listOf(ReceivingAi.CountedItem("ELC-2710", "Dell UltraSharp 27\" Monitor", received = 5)),
        )
        val count = ReceivingAi.applyCount(parsed, l)
        val line = count.items.single()
        assertEquals(5, line.received)
        assertEquals(ItemStatus.OVER, line.status)
    }

    @Test
    fun `short delivery still counts as short`() {
        val l = packingList(Triple("ELC-1180", "Logitech Mechanical Keyboard", 4))
        val parsed = ReceivingAi.Count(
            items = listOf(ReceivingAi.CountedItem("ELC-1180", "Logitech Mechanical Keyboard", received = 2)),
        )
        val count = ReceivingAi.applyCount(parsed, l)
        assertEquals(2, count.items.single().received)
        assertEquals(ItemStatus.SHORT, count.items.single().status)
    }

    @Test
    fun `goods nobody packed come back as unlisted lines`() {
        val l = packingList(Triple("ELC-2710", "Dell UltraSharp 27\" Monitor", 3))
        val parsed = ReceivingAi.Count(
            items = listOf(ReceivingAi.CountedItem("ELC-2710", "Dell UltraSharp 27\" Monitor", received = 3)),
            unlisted = listOf(ReceivingAi.UnlistedItem("Thermal POS Printer", count = 2)),
        )
        val count = ReceivingAi.applyCount(parsed, l)
        val extra = count.items.last()
        assertEquals("Thermal POS Printer", extra.name)
        assertEquals(0, extra.expected)
        assertEquals(2, extra.received)
        assertEquals(ItemStatus.UNLISTED, extra.status)
    }

    @Test
    fun `a renamed packed line cannot be silently dropped`() {
        val l = packingList(Triple("ELC-2710", "Dell UltraSharp 27\" Monitor", 3))
        val parsed = ReceivingAi.Count(
            items = listOf(ReceivingAi.CountedItem("ELC-2710", "Dell Monitor", received = 3)),
        )
        val count = ReceivingAi.applyCount(parsed, l)
        // The SKU still matches, so the line is re-keyed to the packing list's
        // own name and keeps its count.
        assertEquals(1, count.items.count { it.expected > 0 })
        assertEquals(3, count.items.first().received)
    }

    @Test
    fun `a line whose sku and name both change reports zero counted, not a dropped row`() {
        val l = packingList(Triple("ELC-2710", "Dell UltraSharp 27\" Monitor", 3))
        val parsed = ReceivingAi.Count(
            items = listOf(ReceivingAi.CountedItem("ZZZ-0000", "Something Else", received = 3)),
        )
        val count = ReceivingAi.applyCount(parsed, l)
        // Nothing matches, so the packed line reports 0 counted — visible as a
        // short line, never as a missing row.
        assertEquals(l.items.size, count.items.count { it.expected > 0 })
        assertEquals(0, count.items.first().received)
        assertEquals(ItemStatus.SHORT, count.items.first().status)
    }

    @Test
    fun `damaged goods are counted separately from a quantity mismatch`() {
        val l = packingList(Triple("ELC-2710", "Dell UltraSharp 27\" Monitor", 3))
        val parsed = ReceivingAi.Count(
            items = listOf(ReceivingAi.CountedItem("ELC-2710", "Dell UltraSharp 27\" Monitor", received = 3, damaged = 1)),
        )
        val count = ReceivingAi.applyCount(parsed, l)
        assertEquals(1, count.items.single().damaged)
        assertEquals(ItemStatus.DAMAGED, count.items.single().status)
    }

    @Test
    fun `schema echoes never reach the receipt as goods lines`() {
        val l = packingList(Triple("ELC-2710", "Dell UltraSharp 27\" Monitor", 3))
        val parsed = ReceivingAi.Count(
            unlisted = listOf(
                ReceivingAi.UnlistedItem("line item", count = 1),
                ReceivingAi.UnlistedItem("Not on the packing list", count = 1),
                ReceivingAi.UnlistedItem("", count = 1),
            ),
        )
        val count = ReceivingAi.applyCount(parsed, l)
        assertTrue(count.items.none { it.expected == 0 }, "echoed placeholders leaked through: ${count.items}")
    }

    @Test
    fun `raw confidence below the old floor survives the pipeline`() {
        assertEquals(0.42f, ReceivingAi.normaliseConfidence(0.42f))
        assertEquals(0.08f, ReceivingAi.normaliseConfidence(0.08f))
    }

    @Test
    fun `confidence reported in percent is rescaled and not clamped to certainty`() {
        assertEquals(0.85f, ReceivingAi.normaliseConfidence(85f))
        assertEquals(1f, ReceivingAi.normaliseConfidence(250f))
    }

    @Test
    fun `negative confidence is floored at zero`() {
        assertEquals(0f, ReceivingAi.normaliseConfidence(-3f))
    }

    @Test
    fun `fenced or trailing prose around the JSON object still parses`() {
        assertEquals(
            "{\"received\": 3}",
            ReceivingAi.extractJsonObject("Here is the JSON: {\"received\": 3} — hope that helps!"),
        )
        assertEquals("{\"a\": {\"b\": 1}}", ReceivingAi.extractJsonObject("{\"a\": {\"b\": 1}}"))
        assertEquals(
            "{\"s\": \"brace } inside string\"}",
            ReceivingAi.extractJsonObject("prefix {\"s\": \"brace } inside string\"} suffix"),
        )
    }
}
