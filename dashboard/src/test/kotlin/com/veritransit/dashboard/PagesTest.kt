package com.veritransit.dashboard

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks for the served board HTML. Every receipt must carry a report the
 * reader can open on the page itself — an inline viewer keyed to the record —
 * plus the download link, and nothing user-supplied may break the markup.
 */
class PagesTest {

    private fun vault(vararg records: ReceivingRecord) =
        Vault().apply { records.forEach { upsert(it) } }

    private fun record(
        id: String = "GRN-2025-1234",
        outcome: ReceiptOutcome = ReceiptOutcome.OK,
        supplier: String = "Deccan Fasteners & Steel",
    ) = ReceivingRecord(
        id = id,
        purchaseOrderId = "PO-2025-4488",
        packingListId = "PL-2025-4488-A",
        supplier = supplier,
        goods = "Industrial Hardware (Fasteners/Plates)",
        dock = "Dock 1",
        carrier = "TCI Freight",
        outcome = outcome,
        timestamp = 1_760_000_000_000,
        items = listOf(PackingItem("HDW-4412", "MS Hex Bolts M12", "Sealed Crate A", 12, 12)),
    )

    @Test
    fun `every receipt row carries an inline report viewer`() {
        val html = Pages.dashboard(vault(record(), record(id = "GRN-2025-5678")), Instant.now())
        assertEquals(
            2,
            Regex("class=\"pdf view\" data-id=\"[^\"]+\"").findAll(html).count(),
            "expected one view control per receipt row",
        )
        assertTrue(html.contains("<dialog id=\"report\""), "the viewer dialog is missing")
        assertTrue(html.contains("<iframe"), "the viewer frame is missing")
    }

    @Test
    fun `the view control targets the same url as the download`() {
        val html = Pages.dashboard(vault(record(id = "GRN-2025-1234")), Instant.now())
        assertTrue(html.contains("data-id=\"GRN-2025-1234\""))
        assertTrue(html.contains("href=\"/report/GRN-2025-1234.pdf\""))
        assertTrue(html.contains("'/report/' + encodeURIComponent(id) + '.pdf'"))
    }

    @Test
    fun `the download link is still on the board`() {
        val html = Pages.dashboard(vault(record()), Instant.now())
        assertTrue(html.contains("PDF report"))
    }

    @Test
    fun `user-supplied text is escaped`() {
        val html = Pages.dashboard(vault(record(supplier = "A < B \"C\" & D")), Instant.now())
        assertTrue(html.contains("A &lt; B &quot;C&quot; &amp; D"))
        assertTrue(!html.contains("A < B"))
    }

    @Test
    fun `ids are url-encoded in report links`() {
        assertEquals("/report/GRN-2025-1234.pdf", Pages.reportUrl("GRN-2025-1234"))
        assertEquals("/report/GRN-2025%2F1234.pdf", Pages.reportUrl("GRN-2025/1234"))
    }
}
