package com.veritransit.inspector.scan

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * §6.1 / §7.1 — CameraX frames into ML Kit, entirely on-device.
 *
 * Every symbology is decoded from the **same frame**, and **every code in the
 * frame is delivered**, not just the first of each kind: pointing the phone at
 * a pallet and reading all its labels in one shot is the §7.1 demo. The QR and
 * the Code128 print the same package code, so reading them together is also
 * what detects a label physically peeled off one carton and stuck on another
 * (§4.3 layer 3) — that pairing check happens per token downstream, in
 * [VerificationEngine.evaluateFrame].
 */
class BarcodeAnalyzer(
    private val onFrame: (codes: List<String>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX)
            .build()
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }

        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val codes = barcodes.mapNotNull { it.rawValue }.distinct()
                if (codes.isNotEmpty()) onFrame(codes)
            }
            // The frame must be closed on every path or the analysis stream stalls
            // after a handful of frames and the viewfinder silently freezes.
            .addOnCompleteListener { proxy.close() }
    }

    fun close() = scanner.close()
}
