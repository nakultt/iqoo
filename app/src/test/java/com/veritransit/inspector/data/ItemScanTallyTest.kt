package com.veritransit.inspector.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The dock tally the item scanner drives, frame by frame — no camera needed. */
class ItemScanTallyTest {

    private val packed = listOf(
        PackingItem("ELC-2710", "Dell UltraSharp 27\" Monitor", "Factory Boxed", 3, 3),
        PackingItem("ELC-1180", "Logitech Mechanical Keyboard", "Bulk Carton", 4, 4),
    )

    private fun received(t: ItemScanTally, sku: String) = t.lines.single { it.sku == sku }.received

    @Test
    fun `nothing counts as received before a scan`() {
        val t = ItemScanTally(packed)
        assertEquals(listOf(0, 0), t.lines.map { it.received })
        assertEquals(0, t.unitsReceived)
        assertEquals(7, t.unitsExpected)
    }

    @Test
    fun `a code held in front of the camera counts once`() {
        val t = ItemScanTally(packed, rearmMs = 1_000)
        assertIs<ItemScanTally.Outcome.Counted>(t.onCode("ELC-2710", 0))
        for (ms in 30L..3_000L step 30) {
            assertEquals(ItemScanTally.Outcome.StillInView, t.onCode("ELC-2710", ms))
        }
        assertEquals(1, received(t, "ELC-2710"))
    }

    @Test
    fun `the same code counts again once it has been out of view`() {
        val t = ItemScanTally(packed, rearmMs = 1_000)
        t.onCode("ELC-2710", 0)
        t.onCode("ELC-2710", 400)
        // 900 ms since it was last seen: not yet re-armed.
        assertEquals(ItemScanTally.Outcome.StillInView, t.onCode("ELC-2710", 1_300))
        // 1,100 ms since it was last seen: the next carton.
        assertIs<ItemScanTally.Outcome.Counted>(t.onCode("ELC-2710", 2_400))
        assertEquals(2, received(t, "ELC-2710"))
    }

    @Test
    fun `different codes in one frame each count`() {
        val t = ItemScanTally(packed)
        assertIs<ItemScanTally.Outcome.Counted>(t.onCode("ELC-2710", 0))
        assertIs<ItemScanTally.Outcome.Counted>(t.onCode("ELC-1180", 0))
        assertEquals(2, t.unitsReceived)
    }

    @Test
    fun `sku matching ignores case, separators and wrapping`() {
        val t = ItemScanTally(packed, rearmMs = 0)
        assertIs<ItemScanTally.Outcome.Counted>(t.onCode("elc 2710", 0))
        assertIs<ItemScanTally.Outcome.Counted>(t.onCode("""{"sku":"ELC2710","qty":1}""", 10))
        assertIs<ItemScanTally.Outcome.Counted>(t.onCode("SKU: ELC_1180", 20))
        assertEquals(2, received(t, "ELC-2710"))
        assertEquals(1, received(t, "ELC-1180"))
    }

    @Test
    fun `codes that only resemble a sku are not on the list`() {
        val t = ItemScanTally(packed)
        assertIs<ItemScanTally.Outcome.NotOnList>(t.onCode("ELC-27105", 0))
        assertIs<ItemScanTally.Outcome.NotOnList>(t.onCode("XELC-2710", 50))
        assertIs<ItemScanTally.Outcome.NotOnList>(t.onCode("8901234567890", 100))
        assertIs<ItemScanTally.Outcome.NotOnList>(t.onCode("PO-2025-4471", 150))
        assertEquals(0, t.unitsReceived)
    }

    @Test
    fun `hand corrections never go below zero`() {
        val t = ItemScanTally(packed)
        t.adjust("ELC-1180", +2)
        t.adjust("ELC-1180", -5)
        assertEquals(0, received(t, "ELC-1180"))
        assertNull(t.adjust("NOT-ON-LIST", +1))
    }

    private val box1 = BoxLabel.Inner(
        "PO-2025-4471", "PL-2025-4471-A", "MB-4471-01-B01",
        masterId = "MB-4471-01", seq = 1, of = 2,
        lines = listOf(BoxLine("ELC-2710", 3), BoxLine("ELC-1180", 1)),
    )

    @Test
    fun `a box label books every line it declares in one scan`() {
        val t = ItemScanTally(packed, purchaseOrderId = "PO-2025-4471")
        val outcome = assertIs<ItemScanTally.Outcome.BoxCounted>(t.onCode(box1.payload(), 0))
        assertEquals(listOf("ELC-2710" to 3, "ELC-1180" to 1), outcome.lines.map { it.sku to it.received })
        assertEquals(emptyList(), outcome.notOnList)
        assertEquals(4, t.unitsReceived)
        assertEquals(setOf("MB-4471-01-B01"), t.countedBoxIds)
    }

    @Test
    fun `a box counts once, however long it stays in view or however often it comes back`() {
        val t = ItemScanTally(packed, rearmMs = 1_000, purchaseOrderId = "PO-2025-4471")
        assertIs<ItemScanTally.Outcome.BoxCounted>(t.onCode(box1.payload(), 0))
        for (ms in 30L..3_000L step 30) {
            assertEquals(ItemScanTally.Outcome.StillInView, t.onCode(box1.payload(), ms))
        }
        assertIs<ItemScanTally.Outcome.BoxAlreadyCounted>(t.onCode(box1.payload(), 10_000))
        assertEquals(4, t.unitsReceived)
    }

    @Test
    fun `a master box label counts nothing`() {
        val master = BoxLabel.Master("PO-2025-4471", "PL-2025-4471-A", "MB-4471-01", boxCount = 2, units = 7)
        val t = ItemScanTally(packed, purchaseOrderId = "PO-2025-4471")
        val outcome = assertIs<ItemScanTally.Outcome.MasterLabel>(t.onCode(master.payload(), 0))
        assertEquals(2, outcome.master.boxCount)
        assertEquals(0, t.unitsReceived)
    }

    @Test
    fun `a box labelled for another po counts nothing, however the delivery's po is written`() {
        val other = box1.copy(purchaseOrderId = "PO-2025-4488", id = "MB-4488-01-B01", masterId = "MB-4488-01")
        val t = ItemScanTally(packed, purchaseOrderId = "po 2025 4471")
        assertIs<ItemScanTally.Outcome.OtherDelivery>(t.onCode(other.payload(), 0))
        assertEquals(0, t.unitsReceived)
        assertIs<ItemScanTally.Outcome.BoxCounted>(t.onCode(box1.payload(), 10))
        assertEquals(4, t.unitsReceived)
    }

    @Test
    fun `declared lines the packing list lacks are named, and the rest still count`() {
        val mixed = box1.copy(lines = listOf(BoxLine("ELC-1180", 2), BoxLine("ELC-9999", 5)))
        val t = ItemScanTally(packed)
        val outcome = assertIs<ItemScanTally.Outcome.BoxCounted>(t.onCode(mixed.payload(), 0))
        assertEquals(listOf(BoxLine("ELC-9999", 5)), outcome.notOnList)
        assertEquals(2, t.unitsReceived)
    }

    @Test
    fun `boxes counted before a return to the count stay counted`() {
        val t = ItemScanTally(packed, countedBoxes = setOf(box1.id))
        assertIs<ItemScanTally.Outcome.BoxAlreadyCounted>(t.onCode(box1.payload(), 0))
        assertEquals(0, t.unitsReceived)
    }
}
