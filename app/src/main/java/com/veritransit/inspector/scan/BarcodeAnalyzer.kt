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
 * Both symbologies are decoded from the **same frame** on purpose. The QR and
 * the Code128 print the same package code, so reading them together is what
 * detects a label physically peeled off one carton and stuck on another
 * (§4.3 layer 3). Decoding them from separate frames would let a fraudster
 * present each code in turn.
 */
class BarcodeAnalyzer(
    private val onCodes: (qr: String?, barcode: String?) -> Unit,
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
                if (barcodes.isEmpty()) return@addOnSuccessListener
                val qr = barcodes.firstOrNull {
                    it.format == Barcode.FORMAT_QR_CODE || it.format == Barcode.FORMAT_DATA_MATRIX
                }?.rawValue
                val code128 = barcodes.firstOrNull { it.format == Barcode.FORMAT_CODE_128 }?.rawValue
                if (qr != null || code128 != null) onCodes(qr, code128)
            }
            // The frame must be closed on every path or the analysis stream stalls
            // after a handful of frames and the viewfinder silently freezes.
            .addOnCompleteListener { proxy.close() }
    }

    fun close() = scanner.close()
}
