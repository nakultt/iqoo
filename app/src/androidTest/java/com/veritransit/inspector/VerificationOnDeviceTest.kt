package com.veritransit.inspector

import androidx.test.platform.app.InstrumentationRegistry
import com.veritransit.core.LabelToken
import com.veritransit.core.ReasonCode
import com.veritransit.core.ScanKind
import com.veritransit.core.ScanResult
import com.veritransit.inspector.crypto.LabelVerifier
import com.veritransit.inspector.data.VeriTransitRepo
import com.veritransit.inspector.data.local.VeriTransitDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §9 Phase 0/1 acceptance, on real hardware:
 * *"tampered token fails verification"* and *"a planted label-swap is flagged
 * offline with the reason on screen"*.
 *
 * This runs against whatever the device actually bootstrapped — the same Room
 * rows and the same pinned public key the scanner uses — so it exercises the
 * real offline path rather than a fixture. Run it with the device in airplane
 * mode and it still passes; that is the point.
 *
 *   adb shell am instrument -w \
 *     -e class com.veritransit.inspector.VerificationOnDeviceTest \
 *     com.veritransit.inspector.test/androidx.test.runner.AndroidJUnitRunner
 */
class VerificationOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = VeriTransitDatabase.get(context)

    @Test
    fun bootstrapped_labels_verify_offline_with_the_pinned_key(): Unit = runBlocking {
        val keys = db.keys().all()
        assertTrue("no signing key cached — run a sync first", keys.isNotEmpty())

        val verifier = LabelVerifier(keys.associate { it.keyId to it.publicKey })
        assertTrue("verifier could not load any pinned key", verifier.isConfigured)

        val labelled = db.query()
        assertTrue("no cached labels to verify", labelled.isNotEmpty())

        var verified = 0
        labelled.forEach { payload ->
            val token = LabelToken.parse(payload)
            assertNotNull("stored payload is not a valid VT1 token: $payload", token)
            assertTrue("signature did not verify for ${token!!.packageCode}", verifier.verify(token))
            verified++
        }
        assertTrue(verified > 0)
        android.util.Log.i("VeriTransit", "verified $verified cached labels offline on-device")
    }

    @Test
    fun a_tampered_token_is_rejected(): Unit = runBlocking {
        val keys = db.keys().all()
        val verifier = LabelVerifier(keys.associate { it.keyId to it.publicKey })
        val payload = db.query().first()
        val token = LabelToken.parse(payload)!!

        // Repoint a genuine signature at a different package — the exact forgery
        // §3 check 1 exists to catch.
        val forged = token.copy(packageCode = "VT-P-FORGED01")
        assertFalse("a forged package code verified — the check stack is broken",
            verifier.verify(forged))

        val engine = VeriTransitRepo.get(context).engine()
        val verdict = engine.evaluate(
            qr = forged.toString(), barcode = null,
            expectedShipment = token.shipmentRef, kind = ScanKind.LOAD,
        )
        assertEquals(ScanResult.REJECTED, verdict.result)
        assertEquals(listOf(ReasonCode.SIGNATURE_INVALID), verdict.reasons)
    }

    @Test
    fun a_genuine_label_on_the_wrong_truck_is_flagged_not_passed(): Unit = runBlocking {
        val payload = db.query().first()
        val token = LabelToken.parse(payload)!!
        val engine = VeriTransitRepo.get(context).engine()

        val verdict = engine.evaluate(
            qr = payload, barcode = null,
            expectedShipment = "SHP-2026-999999", kind = ScanKind.LOAD,
        )
        assertEquals(ScanResult.SUSPECT_REVIEW, verdict.result)
        assertTrue(ReasonCode.WRONG_SHIPMENT in verdict.reasons)
        android.util.Log.i("VeriTransit", "wrong-shipment verdict: ${verdict.explanation}")
    }

    @Test
    fun a_label_peeled_onto_another_carton_fails_the_cross_check(): Unit = runBlocking {
        val payload = db.query().first()
        val token = LabelToken.parse(payload)!!
        val engine = VeriTransitRepo.get(context).engine()

        // Same label, a different Code128 underneath it.
        val verdict = engine.evaluate(
            qr = payload, barcode = "VT-P-OTHERBOX",
            expectedShipment = token.shipmentRef, kind = ScanKind.LOAD,
        )
        assertEquals(ScanResult.SUSPECT_REVIEW, verdict.result)
        assertTrue(ReasonCode.QR_BARCODE_MISMATCH in verdict.reasons)
        android.util.Log.i("VeriTransit", "label-swap verdict: ${verdict.explanation}")
    }

    /** Raw label payloads cached on this device. */
    private fun VeriTransitDatabase.query(): List<String> =
        openHelper.readableDatabase.query(
            "SELECT labelPayload FROM package_record WHERE labelPayload IS NOT NULL LIMIT 25"
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
}
