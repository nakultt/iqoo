package com.veritransit.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM-side checks for the deterministic report writer. The whole point of the
 * PDF is that its bytes are a pure function of the record — evidence frames
 * included — so these tests fail if a timestamp, locale-formatted number, or
 * iteration-order difference ever leaks into the document.
 */
class PdfReportTest {

    /** Minimal valid JPEG (SOI + SOF0 declaring 2x3 px + EOI) for embed tests. */
    private val tinyJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x03, 0x00, 0x02, 0x01, 0x01, 0x11, 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private fun record(
        id: String = "GRN-2025-1234",
        outcome: ReceiptOutcome = ReceiptOutcome.OK,
        confidence: Float = 0.95f,
        note: String = "",
        list: ByteArray? = null,
        dock: ByteArray? = null,
    ) = ReceivingRecord(
        id = id,
        purchaseOrderId = "PO-2025-4488",
        packingListId = "PL-2025-4488-A",
        supplier = "Deccan Fasteners & Steel",
        goods = "Industrial Hardware (Fasteners/Plates)",
        dock = "Dock 1 · Plant Store",
        carrier = "TCI Freight",
        outcome = outcome,
        timestamp = 1_760_000_000_000,
        items = listOf(
            PackingItem("HDW-4412", "MS Hex Bolts M12", "Sealed Crate A", 12, 12),
            PackingItem("HDW-6608", "GI Plates 6mm", "Sealed Crate B", 8, 6),
        ),
        confidence = confidence,
        note = note,
        listEvidence = list,
        dockEvidence = dock,
    )

    @Test
    fun `same record renders byte-identical documents`() {
        val a = PdfReport.render(record(list = tinyJpeg, dock = tinyJpeg))
        val b = PdfReport.render(record(list = tinyJpeg, dock = tinyJpeg))
        assertTrue(a.contentEquals(b), "two renders of one record differ — non-deterministic output")
    }

    @Test
    fun `evidence bytes are part of the document identity`() {
        val a = PdfReport.render(record(dock = tinyJpeg))
        val b = PdfReport.render(record(dock = tinyJpeg.copyOf().also { it[10] = 0x07 }))
        assertTrue(!a.contentEquals(b), "different evidence produced identical bytes")
    }

    @Test
    fun `a changed record renders a different document`() {
        val a = PdfReport.render(record())
        val b = PdfReport.render(record(id = "GRN-2025-5678"))
        assertTrue(!a.contentEquals(b), "different records produced identical bytes")
    }

    @Test
    fun `an accepted receipt carries the ok-to-pay status line`() {
        val text = PdfReport.render(record(outcome = ReceiptOutcome.OK)).toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("STATUS: ACCEPTED - CLEARED FOR PAYMENT"))
        assertTrue(text.contains("GRN-2025-1234"))
    }

    @Test
    fun `a discrepancy in the receipt warns against payment`() {
        val text = PdfReport.render(record(outcome = ReceiptOutcome.SHORT)).toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("STATUS: HELD ON DOCK - DO NOT PAY"))
    }

    @Test
    fun `evidence frames are embedded as dct images with parsed dimensions`() {
        val text = PdfReport.render(record(dock = tinyJpeg)).toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("/Subtype /Image"))
        assertTrue(text.contains("/Filter /DCTDecode"))
        assertTrue(text.contains("/Width 2"))
        assertTrue(text.contains("/Height 3"))
        assertTrue(text.contains("/Im1 Do"))
    }

    @Test
    fun `a record without frames renders no image objects`() {
        val text = PdfReport.render(record()).toString(Charsets.ISO_8859_1)
        assertTrue(!text.contains("/Subtype /Image"))
        assertTrue(text.contains("No evidence frames were captured"))
    }

    @Test
    fun `chart section renders with packed-vs-received bars`() {
        val text = PdfReport.render(record()).toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("PACKED VS RECEIVED"))
        assertTrue(text.contains("Packed"))
        // Painted bars: at least one filled rect per item per series.
        assertTrue(text.contains(" re f"))
    }

    @Test
    fun `document is two pages`() {
        val text = PdfReport.render(record()).toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("/Count 2"))
        assertEquals(2, Regex("/Type /Page ").findAll(text).count())
    }

    @Test
    fun `confidence uses a dot decimal regardless of host locale`() {
        assertEquals("95.0%", PdfReport.pct(0.95f))
        assertEquals("98.7%", PdfReport.pct(0.987f))
    }

    @Test
    fun `pdf structure is complete and terminated`() {
        val bytes = PdfReport.render(record())
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/MediaBox [0 0 595 842]"))
        assertTrue(text.contains("xref"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun `unicode separators are transliterated for the standard fonts`() {
        assertEquals("Dock 1 - Plant Store", PdfReport.ascii("Dock 1 · Plant Store"))
        assertEquals("Sealed Crate A * Lot", PdfReport.ascii("Sealed Crate A • Lot"))
    }

    @Test
    fun `jpeg size parses sof markers and rejects non-jpeg bytes`() {
        assertEquals(2 to 3, PdfReport.jpegSize(tinyJpeg))
        assertNull(PdfReport.jpegSize(byteArrayOf(0x00, 0x01, 0x02)))
        assertNull(PdfReport.jpegSize(ByteArray(0)))
    }
}
