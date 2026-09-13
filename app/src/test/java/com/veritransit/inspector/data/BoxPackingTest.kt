package com.veritransit.inspector.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sender mode's box plan: which units go in which box inside a master box. */
class BoxPackingTest {

    /** ELC-2710 ×3, ELC-1180 ×4. */
    private val electronics = Presets.ALL.first()

    private fun packedPerSku(plan: List<List<BoxLine>>) =
        plan.flatten().groupBy { it.sku }.mapValues { (_, lines) -> lines.sumOf { it.qty } }

    @Test
    fun `every unit on every preset is packed exactly once, into no empty box, for any box count`() {
        for (preset in Presets.ALL) {
            for (n in 1..preset.totalUnits) {
                val plan = BoxPacking.distribute(preset.items, n)
                assertEquals(n, plan.size)
                assertEquals(preset.items.associate { it.sku to it.expected }, packedPerSku(plan), "${preset.short} in $n")
                assertEquals(emptyList(), BoxPacking.emptyBoxes(plan), "${preset.short} in $n")
                assertTrue(BoxPacking.reconcile(preset.items, plan).all { it.status == ItemStatus.MATCHED })
            }
        }
    }

    @Test
    fun `with at least a box per sku, no box mixes skus`() {
        for (preset in Presets.ALL) {
            for (n in preset.items.size..preset.totalUnits) {
                assertTrue(BoxPacking.distribute(preset.items, n).all { it.size == 1 }, "${preset.short} in $n")
            }
        }
    }

    @Test
    fun `extra boxes go to the sku with the most units per box, split evenly`() {
        assertEquals(
            listOf(listOf(BoxLine("ELC-2710", 3)), listOf(BoxLine("ELC-1180", 2)), listOf(BoxLine("ELC-1180", 2))),
            BoxPacking.distribute(electronics.items, 3),
        )
        val foods = Presets.ALL.first { it.short == "Foodstuffs" } // FMC-5001 ×20, FMC-5510 ×10
        assertEquals(
            listOf(listOf(BoxLine("FMC-5001", 10)), listOf(BoxLine("FMC-5001", 10)), listOf(BoxLine("FMC-5510", 10))),
            BoxPacking.distribute(foods.items, 3),
        )
    }

    @Test
    fun `fewer boxes than skus fill in list order at near-equal sizes`() {
        assertEquals(
            listOf(listOf(BoxLine("ELC-2710", 3), BoxLine("ELC-1180", 4))),
            BoxPacking.distribute(electronics.items, 1),
        )
        val items = listOf(
            PackingItem("A-1", "a", "", 2, 0),
            PackingItem("B-1", "b", "", 2, 0),
            PackingItem("C-1", "c", "", 3, 0),
        )
        assertEquals(
            listOf(listOf(BoxLine("A-1", 2), BoxLine("B-1", 2)), listOf(BoxLine("C-1", 3))),
            BoxPacking.distribute(items, 2),
        )
    }

    @Test
    fun `more boxes than units leaves the extra boxes empty and names them`() {
        assertEquals(listOf(8, 9), BoxPacking.emptyBoxes(BoxPacking.distribute(electronics.items, 9)))
    }

    @Test
    fun `lines without a sku or units cannot be packed, and a repeated sku packs as one`() {
        val items = listOf(
            PackingItem("ELC-2710", "Monitor", "", 3, 0),
            PackingItem("", "Thermal POS Printer", "Not on packing list", 0, 1),
            PackingItem("ELC-2710", "Monitor", "Second pallet", 2, 0),
            PackingItem("ELC-1180", "Keyboard", "", 0, 0),
        )
        assertEquals(listOf(BoxLine("ELC-2710", 5)), BoxPacking.packable(items))
    }

    @Test
    fun `hand edits move units between boxes and show against the list`() {
        var plan = BoxPacking.distribute(electronics.items, 2) // [ELC-2710 ×3], [ELC-1180 ×4]
        plan = BoxPacking.adjust(electronics.items, plan, box = 1, sku = "ELC-1180", delta = -1)
        plan = BoxPacking.adjust(electronics.items, plan, box = 0, sku = "ELC-1180", delta = +1)
        assertEquals(listOf(BoxLine("ELC-2710", 3), BoxLine("ELC-1180", 1)), plan[0])
        assertEquals(listOf(BoxLine("ELC-1180", 3)), plan[1])

        plan = BoxPacking.adjust(electronics.items, plan, box = 1, sku = "ELC-1180", delta = +2)
        val lines = BoxPacking.reconcile(electronics.items, plan).associateBy { it.sku }
        assertEquals(ItemStatus.MATCHED, lines.getValue("ELC-2710").status)
        assertEquals(ItemStatus.OVER, lines.getValue("ELC-1180").status)
        assertEquals(6, lines.getValue("ELC-1180").received)
    }

    @Test
    fun `a line in a new box joins in packing-list order`() {
        var plan = BoxPacking.distribute(electronics.items, 2)
        plan = BoxPacking.adjust(electronics.items, plan, box = 1, sku = "ELC-2710", delta = +1)
        assertEquals(listOf(BoxLine("ELC-2710", 1), BoxLine("ELC-1180", 4)), plan[1])
    }

    @Test
    fun `a box emptied by hand is named, and a line never goes below zero`() {
        var plan = BoxPacking.distribute(electronics.items, 2)
        plan = BoxPacking.adjust(electronics.items, plan, box = 0, sku = "ELC-2710", delta = -5)
        assertEquals(emptyList(), plan[0])
        assertEquals(listOf(1), BoxPacking.emptyBoxes(plan))
        assertEquals(ItemStatus.SHORT, BoxPacking.reconcile(electronics.items, plan).first { it.sku == "ELC-2710" }.status)
    }

    @Test
    fun `master box ids follow the po and skip the ones already taken`() {
        assertEquals("MB-4471-01", BoxPacking.suggestMasterId("PO-2025-4471", emptyList()))
        assertEquals(
            "MB-4471-03",
            BoxPacking.suggestMasterId("PO-2025-4471", listOf("MB-4471-01", "MB-4471-02", "MB-4488-03")),
        )
        assertEquals("MB-4471-02", BoxPacking.suggestMasterId("PO-2025-4471", listOf("mb-4471-01")))
        assertEquals("MB-ACME-01", BoxPacking.suggestMasterId("acme", emptyList()))
        assertEquals("MB-01", BoxPacking.suggestMasterId("", emptyList()))
    }

    @Test
    fun `every suggested id is a valid master id, with room for its boxes`() {
        for (po in listOf("PO-2025-4471", "po 99", "ACME", "", "PO-2025-4471000000000000000000000")) {
            val id = BoxPacking.suggestMasterId(po, emptyList())
            assertTrue(BoxPacking.isValidMasterId(id), "$po → $id")
            assertTrue(BoxLabel.isToken(BoxPacking.innerBoxId(id, BoxLabel.MAX_BOXES)), "$po → $id")
        }
    }

    @Test
    fun `a built master box numbers its boxes and every label reads back`() {
        val box = BoxPacking.build("MB-4471-01", electronics, BoxPacking.distribute(electronics.items, 3), packedAt = 0L)
        assertEquals(listOf("MB-4471-01-B01", "MB-4471-01-B02", "MB-4471-01-B03"), box.boxes.map { it.id })
        assertEquals(7, box.units)

        val labels = box.labels()
        assertEquals(4, labels.size)
        assertEquals(BoxLabel.Master("PO-2025-4471", "PL-2025-4471-A", "MB-4471-01", 3, 7), labels[0])
        val last = labels[3] as BoxLabel.Inner
        assertEquals("MB-4471-01", last.masterId)
        assertEquals(3 to 3, last.seq to last.of)
        labels.forEach { assertEquals(it, BoxLabel.parse(it.payload())) }
    }

    @Test
    fun `a master box is never built with an empty box or an id no label can carry`() {
        val twoBoxes = BoxPacking.distribute(electronics.items, 2)
        assertFailsWith<IllegalArgumentException> {
            BoxPacking.build("MB-4471-01", electronics, BoxPacking.distribute(electronics.items, 9), 0L)
        }
        assertFailsWith<IllegalArgumentException> { BoxPacking.build("MB 4471", electronics, twoBoxes, 0L) }
        assertFailsWith<IllegalArgumentException> { BoxPacking.build("MB-4471-0000000000000000001", electronics, twoBoxes, 0L) }
    }
}
