package com.veritransit.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone, the server caption and the bot must all say the same thing. */
class VoiceAlertTextTest {

    @Test
    fun `reject names the code, the reason and the action`() {
        val text = VoiceAlertText.forScan(
            ScanResult.REJECTED, "VT-P-FORGED01", listOf(ReasonCode.SIGNATURE_INVALID),
        )!!
        assertContains(text, "VT-P-FORGED01")
        assertContains(text, "Do not load this carton.")
    }

    @Test
    fun `suspect names why, briefly`() {
        val text = VoiceAlertText.forScan(
            ScanResult.SUSPECT_REVIEW, "VT-P-X",
            listOf(ReasonCode.QR_BARCODE_MISMATCH, ReasonCode.DUPLICATE_LABEL),
        )!!
        assertContains(text, "Suspect package")
        assertContains(text, "QR and barcode disagree")
    }

    @Test
    fun `verified stays silent unless passes are enabled`() {
        assertNull(VoiceAlertText.forScan(ScanResult.VERIFIED, "VT-P-X", emptyList()))
        assertTrue(
            VoiceAlertText.forScan(ScanResult.VERIFIED, "VT-P-X", emptyList(), announcePasses = true)!!
                .startsWith("Verified"),
        )
    }

    @Test
    fun `clean gate result stays silent, discrepant speaks`() {
        assertNull(VoiceAlertText.forGate("7819-2044-8831", emptyList(), 0))
        val text = VoiceAlertText.forGate("7819-2044-8831", listOf("Short by 2"), unlisted = 1)!!
        assertContains(text, "7819-2044-8831")
        assertContains(text, "Hold for review.")
    }

    @Test
    fun `counted gate variant speaks counts without names`() {
        val text = VoiceAlertText.forGateCounted("781920448831", 2, 1)!!
        assertContains(text, "2 lines discrepant.")
        assertContains(text, "1 unlisted parcel observed.")
        assertContains(text, "Hold for review.")
        assertNull(VoiceAlertText.forGateCounted("781920448831", 0, 0))
    }

    @Test
    fun `telegram caption carries shipment, verdict and reasons`() {
        val caption = VoiceAlertText.telegramCaption(
            "SHP-2026-090255", "VT-P-FORGED01",
            ScanResult.REJECTED, listOf(ReasonCode.SIGNATURE_INVALID),
        )
        assertContains(caption, "SHP-2026-090255")
        assertContains(caption, "REJECTED")
    }
}
