package com.veritransit.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §11: "token sign/verify round-trip and tamper cases". */
class LabelTokenTest {

    private val sample =
        "VT1|P=VT-P-8F3K2M9D|S=SHP-2026-090231|N=1|T=20667|SIG=" + "A".repeat(86)

    @Test
    fun `parses the documented token shape`() {
        val t = LabelToken.parse(sample)!!
        assertEquals("VT-P-8F3K2M9D", t.packageCode)
        assertEquals("SHP-2026-090231", t.shipmentRef)
        assertEquals(1, t.copyNo)
        assertEquals(20667L, t.issuedEpochDay)
    }

    @Test
    fun `round-trips through toString`() {
        val t = LabelToken.parse(sample)!!
        assertEquals(sample, t.toString())
        assertEquals(LabelToken.bodyToSign(t.packageCode, t.shipmentRef, t.copyNo, t.issuedEpochDay), t.signedBody)
    }

    @Test
    fun `signed body excludes the signature field`() {
        val t = LabelToken.parse(sample)!!
        assertTrue(!t.signedBody.contains("SIG="))
    }

    @Test
    fun `rejects foreign and malformed codes rather than throwing`() {
        // A camera sees plenty of codes that are not ours; none of it is exceptional.
        assertNull(LabelToken.parse("https://example.com/product/123"))
        assertNull(LabelToken.parse("VT2|P=X|S=Y|N=1|T=1|SIG=${"A".repeat(86)}"))
        assertNull(LabelToken.parse("VT1|P=VT-P-1|S=SHP-1|N=1|T=1|SIG=tooshort"))
        assertNull(LabelToken.parse("VT1|P=|S=SHP-1|N=1|T=1|SIG=${"A".repeat(86)}"))
        assertNull(LabelToken.parse("VT1|P=VT-P-1|S=SHP-1|N=0|T=1|SIG=${"A".repeat(86)}"))
        assertNull(LabelToken.parse("VT1|P=VT-P-1|S=SHP-1|N=1|T=1|X=9|SIG=${"A".repeat(86)}"))
        assertNull(LabelToken.parse(""))
    }

    // --------------------------------------------------- the check stack

    private val known = PackageRecord(
        packageCode = "VT-P-8F3K2M9D", shipmentRef = "SHP-2026-090231",
        kind = PackageKind.MASTER, qty = 10, copyNo = 1,
    )

    @Test
    fun `a forged signature is rejected outright and short-circuits`() {
        val (result, reasons) = LabelChecks.evaluate(
            LabelToken.parse(sample)!!, verified = false,
            expectedShipment = "SHP-OTHER", known = null, duplicate = true,
        )
        assertEquals(ScanResult.REJECTED, result)
        // Only the signature reason: nothing else is worth telling the officer
        // when the label was not issued by the platform at all.
        assertEquals(listOf(ReasonCode.SIGNATURE_INVALID), reasons)
    }

    @Test
    fun `a good label on the right shipment passes clean`() {
        val (result, reasons) = LabelChecks.evaluate(
            LabelToken.parse(sample)!!, verified = true,
            expectedShipment = "SHP-2026-090231",
            barcodeCode = "VT-P-8F3K2M9D", known = known,
        )
        assertEquals(ScanResult.VERIFIED, result)
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `a label peeled onto another carton fails the cross-check`() {
        val (result, reasons) = LabelChecks.evaluate(
            LabelToken.parse(sample)!!, verified = true,
            expectedShipment = "SHP-2026-090231",
            barcodeCode = "VT-P-SOMETHINGELSE", known = known,
        )
        assertEquals(ScanResult.SUSPECT_REVIEW, result)
        assertContains(reasons, ReasonCode.QR_BARCODE_MISMATCH)
    }

    @Test
    fun `a valid label from another truck is flagged, not passed`() {
        val (result, reasons) = LabelChecks.evaluate(
            LabelToken.parse(sample)!!, verified = true,
            expectedShipment = "SHP-2026-090999", known = known,
        )
        assertEquals(ScanResult.SUSPECT_REVIEW, result)
        assertContains(reasons, ReasonCode.WRONG_SHIPMENT)
    }

    @Test
    fun `a photocopied label is caught by duplicate detection`() {
        val (result, reasons) = LabelChecks.evaluate(
            LabelToken.parse(sample)!!, verified = true,
            expectedShipment = "SHP-2026-090231", known = known, duplicate = true,
        )
        assertEquals(ScanResult.SUSPECT_REVIEW, result)
        assertContains(reasons, ReasonCode.DUPLICATE_LABEL)
    }

    @Test
    fun `an old copy of a reprinted label is superseded`() {
        val old = LabelToken.parse(sample)!!
        val (result, reasons) = LabelChecks.evaluate(
            old, verified = true, expectedShipment = "SHP-2026-090231",
            known = known.copy(copyNo = 2),
        )
        assertEquals(ScanResult.SUSPECT_REVIEW, result)
        assertContains(reasons, ReasonCode.REPRINT_SUPERSEDED)
    }

    @Test
    fun `a package the device has never heard of is not silently accepted`() {
        val (result, reasons) = LabelChecks.evaluate(
            LabelToken.parse(sample)!!, verified = true,
            expectedShipment = "SHP-2026-090231", known = null,
        )
        assertEquals(ScanResult.SUSPECT_REVIEW, result)
        assertContains(reasons, ReasonCode.NOT_IN_MANIFEST)
    }

    @Test
    fun `risk bands follow the documented thresholds`() {
        assertEquals(RiskBand.LOW, RiskBands.of(0))
        assertEquals(RiskBand.LOW, RiskBands.of(30))
        assertEquals(RiskBand.MEDIUM, RiskBands.of(31))
        assertEquals(RiskBand.MEDIUM, RiskBands.of(60))
        assertEquals(RiskBand.HIGH, RiskBands.of(61))
        assertEquals(RiskBand.HIGH, RiskBands.of(100))
    }
}
