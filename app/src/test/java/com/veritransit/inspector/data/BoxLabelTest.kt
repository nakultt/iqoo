package com.veritransit.inspector.data

import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The box-label text sender mode prints, and what the receiving side reads back from it. */
class BoxLabelTest {

    private val master = BoxLabel.Master("PO-2025-4471", "PL-2025-4471-A", "MB-4471-01", boxCount = 2, units = 7)

    private val inner = BoxLabel.Inner(
        "PO-2025-4471", "PL-2025-4471-A", "MB-4471-01-B02",
        masterId = "MB-4471-01", seq = 2, of = 2,
        lines = listOf(BoxLine("ELC-2710", 1), BoxLine("ELC-1180", 3)),
    )

    @Test
    fun `the payload is the documented text`() {
        assertEquals(
            "VTBOX1 MASTER PO:PO-2025-4471 PL:PL-2025-4471-A ID:MB-4471-01 BOXES:2 UNITS:7",
            master.payload(),
        )
        assertEquals(
            "VTBOX1 BOX PO:PO-2025-4471 PL:PL-2025-4471-A ID:MB-4471-01-B02 IN:MB-4471-01 NO:2/2 " +
                "ITEMS:ELC-2710*1+ELC-1180*3",
            inner.payload(),
        )
    }

    @Test
    fun `labels read back exactly, lines in order`() {
        assertEquals(master, BoxLabel.parse(master.payload()))
        assertEquals(inner, BoxLabel.parse(inner.payload()))
    }

    @Test
    fun `a lower-case copy of a label still reads`() {
        assertEquals(inner, BoxLabel.parse(inner.payload().lowercase()))
    }

    @Test
    fun `payloads keep to the qr alphanumeric set`() {
        for (payload in listOf(master.payload(), inner.payload())) {
            assertEquals(Mode.ALPHANUMERIC, Encoder.chooseMode(payload), payload)
        }
    }

    @Test
    fun `the label scan opens the packing list from a master box or any box inside it`() {
        for (payload in listOf(master.payload(), inner.payload())) {
            val fields = QrLabel.parse(payload)
            assertEquals("PO-2025-4471", fields.purchaseOrderId)
            assertEquals("PL-2025-4471-A", fields.packingListId)
            assertEquals("Bright Electronics Pvt Ltd", Presets.forLabel(fields)?.supplier)
        }
    }

    @Test
    fun `a master box id shaped like another po never outranks the real one`() {
        val lookalike = master.copy(id = "PO-2025-4488")
        assertEquals("PO-2025-4471", QrLabel.parse(lookalike.payload()).purchaseOrderId)
    }

    @Test
    fun `codes that are not box labels read as null`() {
        val base = "PO:PO-2025-4471 PL:PL-2025-4471-A"
        listOf(
            "",
            "PO-2025-4471",
            "ELC-2710",
            """{"po":"PO-2025-4471","pl":"PL-2025-4471-A"}""",
            "VTBOX2 MASTER $base ID:MB-1 BOXES:1 UNITS:1",
            "VTBOX1 PALLET $base ID:MB-1 BOXES:1 UNITS:1",
            "VTBOX1 MASTER $base BOXES:2 UNITS:7",
            "VTBOX1 MASTER $base ID:MB-1 BOXES:0 UNITS:7",
            "VTBOX1 MASTER $base ID:MB-1 BOXES:100 UNITS:700",
            "VTBOX1 MASTER $base PO:PO-2025-4488 ID:MB-1 BOXES:2 UNITS:7",
            "VTBOX1 MASTER $base ID:MB-1 BOXES:2 UNITS",
            "VTBOX1 BOX $base ID:B1 IN:MB-1 NO:3/2 ITEMS:ELC-2710*1",
            "VTBOX1 BOX $base ID:B1 IN:MB-1 NO:1-2 ITEMS:ELC-2710*1",
            "VTBOX1 BOX $base ID:B1 IN:MB-1 NO:1/2 ITEMS:ELC-2710*0",
            "VTBOX1 BOX $base ID:B1 IN:MB-1 NO:1/2 ITEMS:ELC-2710*-1",
            "VTBOX1 BOX $base ID:B1 IN:MB-1 NO:1/2 ITEMS:ELC-2710*1+ELC-2710*2",
            "VTBOX1 BOX $base ID:B1 IN:MB-1 NO:1/2 ITEMS:",
            "VTBOX1 BOX $base ID:B1 NO:1/2 ITEMS:ELC-2710*1",
        ).forEach { assertNull(BoxLabel.parse(it), it) }
    }

    @Test
    fun `values that would break the payload are refused when the label is made`() {
        assertFailsWith<IllegalArgumentException> { master.copy(id = "MB 4471") }
        assertFailsWith<IllegalArgumentException> { master.copy(id = "MB:4471") }
        assertFailsWith<IllegalArgumentException> { master.copy(id = "mb-4471") }
        assertFailsWith<IllegalArgumentException> { inner.copy(lines = listOf(BoxLine("ELC*2710", 1))) }
        assertFailsWith<IllegalArgumentException> { inner.copy(lines = emptyList()) }
    }
}
