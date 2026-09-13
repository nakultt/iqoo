package com.veritransit.dashboard

import com.veritransit.dashboard.documents.ConsignmentFacts
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/** The web paperwork page: the claim card for damaged goods, the gate, and honest pre-fill. */
class ReceiptPageTest {

    private val now = Instant.now()

    private fun flagged() = Vault.seeded().find("GRN-2025-8835")!! // SHORT, Bright Electronics, VRL carrier
    private fun food() = Vault.seeded().find("GRN-2025-8831")!! // OK, Sahyadri Foods — no goods family in its words

    @Test
    fun `a flagged receipt leads with the claim documents`() {
        val html = Pages.receiptPage(flagged(), ConsignmentFacts.fromRecord(flagged()), now)
        assertTrue(html.contains("Insurance &amp; claim"), "the claim card is missing")
        assertTrue(html.contains("carrier-notice") || html.contains("Written notice of loss or damage"))
        assertTrue(html.contains("180 days"), "the carrier-notice clock is missing")
        assertTrue(html.contains("no insurance-policy integration", ignoreCase = true) || html.contains("There is no insurance-policy integration"))
        assertTrue(html.contains("DO NOT PAY"))
    }

    @Test
    fun `an accepted receipt carries no claim card`() {
        val html = Pages.receiptPage(food(), ConsignmentFacts.fromRecord(food()), now)
        assertTrue(!html.contains("Insurance &amp; claim"))
        assertTrue(html.contains("OK TO PAY"))
    }

    @Test
    fun `a receipt whose words name no goods family gates the full resolution`() {
        val html = Pages.receiptPage(food(), ConsignmentFacts.fromRecord(food()), now)
        assertTrue(html.contains("Cannot resolve yet"), "expected the gate card")
        assertTrue(!html.contains("Required by law"), "no resolution may render while gated")
        assertTrue(html.contains("Consignment facts"), "the fact form must still render")
    }

    @Test
    fun `the form pre-fills from the receipt and the resolution renders once answered`() {
        // First view: category guessed from the receipt, inter-State unanswered → gated.
        val first = Pages.receiptPage(flagged(), ConsignmentFacts.fromRecord(flagged()), now)
        assertTrue(first.contains("value=\"electronics\" selected"), "the goods family pre-fill is missing")
        assertTrue(first.contains("Cannot resolve yet"))

        // Office answers on the form: the page then resolves the registry.
        val answered = ConsignmentFacts.fromRecord(
            flagged(),
            mapOf("submitted" to "1", "inter" to "true", "declared" to "240000"),
        )
        val second = Pages.receiptPage(flagged(), answered, now)
        assertTrue(second.contains("Required by law"))
        assertTrue(second.contains("E-way bill"), "an inter-State ₹2,40,000 consignment owes an e-way bill")
        assertTrue(second.contains("govt text"), "verification marks must render")
    }

    @Test
    fun `user-supplied fields are escaped`() {
        val html = Pages.receiptPage(food(), ConsignmentFacts.fromRecord(food()), now)
        assertTrue(html.contains("Sahyadri Foods LLP"))
        assertTrue(!html.contains("&amp; beyond"))
    }

    @Test
    fun `the page shows the goods picture and the receipt's own count`() {
        val html = Pages.receiptPage(flagged(), ConsignmentFacts.fromRecord(flagged()), now)
        assertTrue(html.contains("The count on this receipt"), "the line-items digest is missing")
        assertTrue(html.contains("/img/report-electronics.jpg"), "the goods picture is missing")
        assertTrue(html.contains("reference picture, not the consignment"), "the picture must be labelled as illustrative")
        assertTrue(html.contains("ELC-3305"), "the receipt's own lines are missing")
        assertTrue(html.contains(">SHORT<"), "the per-line status is missing")
        assertTrue(html.contains("2 discrepancies") || html.contains("1 discrepancy"), "the discrepancy tally is missing")
    }

    @Test
    fun `required documents carry an operational checklist`() {
        val html = Pages.receiptPage(flagged(), ConsignmentFacts.fromRecord(flagged(), mapOf("submitted" to "1", "inter" to "true", "declared" to "240000")), now)
        assertTrue(html.contains("When you hold it, check"), "the checklist block is missing")
        assertTrue(html.contains("vehicle number filled"), "the e-way Part B check is missing")
    }
}
