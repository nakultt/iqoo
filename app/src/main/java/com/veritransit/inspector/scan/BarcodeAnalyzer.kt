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
 * Three properties this analyser has to get right, none of which are obvious:
 *
 *  * **Every code in the frame is delivered, not just the first of each kind.**
 *    Pointing the phone at a pallet and verifying all its labels in one shot is
 *    the §7.1 flow — [VerificationEngine.evaluateFrame] takes the list and gives
 *    each signed token its own verdict, so there is no "wrong box picked" to
 *    guard against and no reason to prefer the code nearest the reticle.
 *
 *  * **QR and Code128 are paired across a short window, not one frame.** §4.3
 *    layer 3 compares the two codes on one label to catch one peeled off
 *    another carton, but the two symbologies almost never resolve in the same
 *    frame at dock distance. A code stays "live" for [PAIR_WINDOW_MS], and a
 *    2-D code is only emitted once a partner barcode has appeared or the window
 *    closes — giving the second symbology time to arrive while still staying
 *    far inside the 1.5 s budget.
 *
 *  * **Every frame is closed.** On every path, or the analysis stream stalls
 *    after a handful of frames and the viewfinder silently freezes.
 */
class BarcodeAnalyzer(
    private val onFrame: (codes: List<String>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX)
            .build()
    )

    /** Codes still on screen: raw value → what the camera has seen of it. */
    private val live = HashMap<String, LiveCode>()

    private class LiveCode(val firstSeen: Long, val is2D: Boolean) {
        var lastSeen: Long = firstSeen
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }

        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val now = System.currentTimeMillis()
                barcodes.mapNotNull { it.rawValue }.forEach { raw ->
                    val is2D = barcodes.first { it.rawValue == raw }.let {
                        it.format == Barcode.FORMAT_QR_CODE || it.format == Barcode.FORMAT_DATA_MATRIX
                    }
                    val seen = live[raw]
                    if (seen == null) live[raw] = LiveCode(now, is2D) else seen.lastSeen = now
                }
                // A code the camera has lost stops being part of the scene.
                live.entries.removeAll { now - it.value.lastSeen >= PAIR_WINDOW_MS }

                val ready = readyCodes(now)
                if (ready.isNotEmpty()) onFrame(ready)
            }
            // The frame must be closed on every path or the analysis stream stalls
            // after a handful of frames and the viewfinder silently freezes.
            .addOnCompleteListener { proxy.close() }
    }

    /**
     * The codes ready to verify: every live plain barcode, plus every 2-D code
     * that has either been joined by a barcode (the layer-3 pairing check is
     * possible now) or has waited out the window (emit alone rather than never).
     */
    private fun readyCodes(now: Long): List<String> {
        val hasBarcode = live.values.any { !it.is2D }
        return live.entries.mapNotNull { (raw, code) ->
            when {
                !code.is2D -> raw
                hasBarcode || now - code.firstSeen >= PAIR_WINDOW_MS -> raw
                else -> null
            }
        }
    }

    fun close() = scanner.close()

    private companion object {
        /**
         * How long a lone symbology waits for its partner. Long enough for the
         * next few frames at 30 fps, short enough that the officer perceives the
         * verdict as instant.
         */
        const val PAIR_WINDOW_MS = 350L
    }
}
