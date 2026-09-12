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
}
