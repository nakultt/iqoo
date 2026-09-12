package com.veritransit.inspector.ai

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/**
 * JVM-side checks for the packing-list per-section confidence parsing — the
 * exact decode path [ReceivingAi.readPackingList] takes on device, minus the
 * NPU. Runs without a device or the model bundle.
 */
class ReceivingAiListConfidenceTest {

    @Test
    fun `sections absent from the reply parse as not reported`() {
        val reading = ReceivingAi.parsePackingListReading(
            """
            {"purchase_order": "PO-2025-4471", "packing_list": "PL-2025-4471-A",
             "legible": true}
            """.trimIndent(),
        )
        assertNull(reading.headerConfidence)
        assertNull(reading.supplierConfidence)
        assertNull(reading.itemsConfidence)
    }

    @Test
    fun `in-range confidences pass through verbatim`() {
        val reading = ReceivingAi.parsePackingListReading(
            """
            {"purchase_order": "PO-1", "packing_list": "PL-1",
             "header_confidence": 0.42, "supplier_confidence": 0.9, "items_confidence": 0.0}
            """.trimIndent(),
        )
        assertEquals(0.42f, reading.headerConfidence)
        assertEquals(0.9f, reading.supplierConfidence)
        assertEquals(0.0f, reading.itemsConfidence)
    }

    @Test
    fun `percent-scale confidences are rescaled, not clamped to certainty`() {
        val reading = ReceivingAi.parsePackingListReading(
            """{"header_confidence": 85, "supplier_confidence": 100, "items_confidence": 12}""",
        )
        assertEquals(0.85f, reading.headerConfidence)
        assertEquals(1f, reading.supplierConfidence)
        assertEquals(0.12f, reading.itemsConfidence)
    }

    @Test
    fun `garbage confidences become not-reported instead of a false all-clear`() {
        val reading = ReceivingAi.parsePackingListReading(
            """{"header_confidence": -3, "supplier_confidence": 250, "items_confidence": null}""",
        )
        assertNull(reading.headerConfidence)
        assertNull(reading.supplierConfidence)
        assertNull(reading.itemsConfidence)
    }

    @Test
    fun `only the sections below the threshold are flagged`() {
        val reading = ReceivingAi.parsePackingListReading(
            """{"header_confidence": 0.42, "supplier_confidence": 0.9, "items_confidence": 0.08}""",
        )
        assertEquals(listOf("header", "items"), ReceivingAi.lowConfidenceSections(reading))
    }

    @Test
    fun `unreported sections are never flagged`() {
        val reading = ReceivingAi.parsePackingListReading("""{"purchase_order": "PO-1"}""")
        assertEquals(emptyList<String>(), ReceivingAi.lowConfidenceSections(reading))
    }

    @Test
    fun `fenced replies still parse`() {
        val reading = ReceivingAi.parsePackingListReading(
            """Sure! Here is the reading: {"header_confidence": 0.7} — hope that helps""",
        )
        assertEquals(0.7f, reading.headerConfidence)
        assertEquals("", reading.purchaseOrderId)
    }

    @Test
    fun `a reading with only declared lines is usable for the packing-list step`() {
        val reading = ReceivingAi.parsePackingListReading(
            """{"items": [{"sku": "ELC-2710", "name": "Monitor", "packaging": "Carton", "quantity": 3}]}""",
        )
        assertEquals(true, reading.usable)
        assertEquals("ELC-2710", reading.toPackingList().items.single().sku)
    }
}
