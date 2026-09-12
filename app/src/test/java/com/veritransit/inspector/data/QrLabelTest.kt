package com.veritransit.inspector.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * JVM-side checks for the carton-label payload extraction — the exact parse
 * path the label scanner takes on device, minus the camera and ML Kit. Runs
 * without a device.
 */
class QrLabelTest {

    @Test
    fun `a printed po reference is pulled out as the purchase order`() {
        val fields = QrLabel.parse("PO-2025-4471")
        assertEquals("PO-2025-4471", fields.purchaseOrderId)
        assertNull(fields.packingListId)
    }

    @Test
    fun `spacing and separators are normalised to the printed form`() {
        assertEquals("PO-2025-4471", QrLabel.parse("PO 2025 4471").purchaseOrderId)
        assertEquals("PO-2025-4471", QrLabel.parse("po_2025_4471").purchaseOrderId)
        assertEquals("PO-2025-4471", QrLabel.parse("PO20254471").purchaseOrderId)
    }

    @Test
    fun `a packing-list reference keeps its revision suffix`() {
        assertEquals("PL-2025-4471-A", QrLabel.parse("PL-2025-4471-A").packingListId)
        assertEquals("PL-2025-4471", QrLabel.parse("PL-2025-4471").packingListId)
    }

    @Test
    fun `a json label payload yields both references`() {
        val fields = QrLabel.parse(
            """{"po":"PO-2025-4471","pl":"PL-2025-4471-A","supplier":"Bright Electronics"}""",
        )
        assertEquals("PO-2025-4471", fields.purchaseOrderId)
        assertEquals("PL-2025-4471-A", fields.packingListId)
    }

    @Test
    fun `a label carrying both references reads both`() {
        val fields = QrLabel.parse("SUPPLIER BRIGHT  PO-2025-4471  PL-2025-4471-A  CARTON 4/9")
        assertEquals("PO-2025-4471", fields.purchaseOrderId)
        assertEquals("PL-2025-4471-A", fields.packingListId)
    }

    @Test
    fun `a reference inside a longer alphanumeric blob is not read as a po`() {
        // The boundaries keep a serial like XPO20254471B from being harvested.
        assertNull(QrLabel.parse("XPO20254471B").purchaseOrderId)
    }

    @Test
    fun `an unrelated label still carries its raw text`() {
        val fields = QrLabel.parse("https://example.com/delivery/abc")
        assertNull(fields.purchaseOrderId)
        assertNull(fields.packingListId)
        assertEquals("https://example.com/delivery/abc", fields.raw)
    }

    @Test
    fun `a bare carton serial yields no references rather than a guess`() {
        val fields = QrLabel.parse("781920448831")
        assertNull(fields.purchaseOrderId)
        assertNull(fields.packingListId)
    }
}
