package com.veritransit.inspector.data

import kotlin.test.assertEquals
import kotlin.test.Test

/** Status derivation, including the over-count case that used to be erased. */
class PackingItemStatusTest {

    @Test
    fun `matching counts are matched`() {
        assertEquals(ItemStatus.MATCHED, PackingItem("SKU-1", "Crate", "Box", 3, 3).status)
    }

    @Test
    fun `fewer than packed is short`() {
        assertEquals(ItemStatus.SHORT, PackingItem("SKU-1", "Crate", "Box", 3, 1).status)
    }

    @Test
    fun `more than packed is an over, not a match`() {
        assertEquals(ItemStatus.OVER, PackingItem("SKU-1", "Crate", "Box", 3, 5).status)
    }

    @Test
    fun `goods with no packed line at all are unlisted`() {
        assertEquals(ItemStatus.UNLISTED, PackingItem("", "Generator", "Not on packing list", 0, 2).status)
    }

    @Test
    fun `damage outranks a clean count`() {
        assertEquals(ItemStatus.DAMAGED, PackingItem("SKU-1", "Crate", "Box", 3, 3, damaged = 1).status)
    }

    @Test
    fun `the signed delta drives the short and over chips`() {
        assertEquals(-2, PackingItem("SKU-1", "Crate", "Box", 4, 2).delta)
        assertEquals(2, PackingItem("SKU-1", "Crate", "Box", 3, 5).delta)
    }

    @Test
    fun `an over-count flags the record, and the outcome follows the lines`() {
        val items = listOf(
            PackingItem("HDW-4412", "MS Hex Bolts M12", "Crate A", 12, 12),
            PackingItem("HDW-6608", "GI Plates 6mm", "Crate B", 8, 10),
        )
        val record = ReceivingRecord(
            id = "GRN-2025-0001",
            purchaseOrderId = "PO-2025-4488",
            packingListId = "PL-2025-4488-A",
            supplier = "Deccan Fasteners & Steel",
            goods = "Industrial Hardware",
            dock = "Dock 1 · Plant Store",
            carrier = "TCI Freight",
            outcome = outcomeOf(items),
            timestamp = 0L,
            items = items,
        )
        assertEquals(ReceiptOutcome.OVER, record.outcome)
        assertEquals(true, record.flagged)
        assertEquals(1, record.discrepancyCount)
        assertEquals(true, record.items.any { it.status == ItemStatus.OVER })
    }

    @Test
    fun `unlisted and damaged goods outrank a count difference in the outcome`() {
        assertEquals(
            ReceiptOutcome.MISMATCH,
            outcomeOf(
                listOf(
                    PackingItem("SKU-1", "Crate", "Box", 3, 1),
                    PackingItem("", "Generator", "Not on packing list", 0, 1),
                ),
            ),
        )
        assertEquals(
            ReceiptOutcome.MISMATCH,
            outcomeOf(listOf(PackingItem("SKU-1", "Crate", "Box", 3, 3, damaged = 2))),
        )
    }

    @Test
    fun `an empty count is pending, not a clean pass`() {
        assertEquals(ReceiptOutcome.PENDING, outcomeOf(emptyList()))
    }
}
