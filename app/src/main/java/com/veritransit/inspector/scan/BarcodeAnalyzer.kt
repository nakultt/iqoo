package com.veritransit.inspector.scan

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.math.hypot

/**
 * §6.1 / §7.1 — CameraX frames into ML Kit, entirely on-device.
 *
 * Three properties this analyser has to get right, none of which are obvious:
 *
 *  * **The officer's label wins.** A dock frame routinely contains four cartons.
 *    Taking `firstOrNull` hands back whichever code ML Kit happened to list
 *    first, so the verdict can belong to a box the officer never aimed at. The
 *    code nearest the frame centre — the reticle — is the one that is picked.
 *  * **QR and Code128 are paired across a short window, not one frame.** §4.3
 *    layer 3 compares the two codes on a label to catch one peeled off another
 *    carton, but the two symbologies almost never resolve in the same frame at
 *    dock distance. Emitting on the first code alone made layer 3 dead in
 *    practice; [PAIR_WINDOW_MS] gives the second symbology time to arrive while
 *    still staying far inside the §7.1 1.5 s budget.
 *  * **Every frame is closed.** On every path, or the analysis stream stalls
 *    after a handful of frames and the viewfinder silently freezes.
 */
class BarcodeAnalyzer(
    private val onCodes: (qr: String?, barcode: String?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX)
            .build()
    )

    /** The half-decoded label being assembled, if a window is open. */
    private var pendingQr: String? = null
    private var pendingBarcode: String? = null
    private var pendingSince = 0L

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }

        val rotation = proxy.imageInfo.rotationDegrees
        // Bounding boxes come back in the *rotated* frame, so the centre this is
        // measured against has to be the rotated one too.
        val upright = rotation == 90 || rotation == 270
        val frameW = if (upright) proxy.height else proxy.width
        val frameH = if (upright) proxy.width else proxy.height

        val image = InputImage.fromMediaImage(media, rotation)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isEmpty()) {
                    flushIfStale()
                    return@addOnSuccessListener
                }
                val qr = nearestCentre(
                    barcodes.filter {
                        it.format == Barcode.FORMAT_QR_CODE || it.format == Barcode.FORMAT_DATA_MATRIX
                    },
                    frameW, frameH,
                )
                val code128 = nearestCentre(
                    barcodes.filter { it.format == Barcode.FORMAT_CODE_128 },
                    frameW, frameH,
                )
                accumulate(qr, code128)
            }
            .addOnCompleteListener { proxy.close() }
    }

    /**
     * Holds a lone code briefly so its partner symbology can join it, and emits
     * as soon as the pair is complete or the window closes.
     */
    private fun accumulate(qr: String?, barcode: String?) {
        if (qr == null && barcode == null) { flushIfStale(); return }

        val now = System.currentTimeMillis()
        // A different label entered the frame — the half-read one is abandoned
        // rather than merged, or layer 3 would compare two different cartons.
        val switched = (qr != null && pendingQr != null && qr != pendingQr) ||
            (barcode != null && pendingBarcode != null && barcode != pendingBarcode)
        if (switched) {
            pendingQr = null
            pendingBarcode = null
        }

        if (pendingQr == null && pendingBarcode == null) pendingSince = now
        if (qr != null) pendingQr = qr
        if (barcode != null) pendingBarcode = barcode

        val paired = pendingQr != null && pendingBarcode != null
        if (paired || now - pendingSince >= PAIR_WINDOW_MS) emit()
    }

    private fun flushIfStale() {
        if (pendingQr == null && pendingBarcode == null) return
        if (System.currentTimeMillis() - pendingSince >= PAIR_WINDOW_MS) emit()
    }

    private fun emit() {
        val qr = pendingQr
        val barcode = pendingBarcode
        pendingQr = null
        pendingBarcode = null
        pendingSince = 0L
        if (qr != null || barcode != null) onCodes(qr, barcode)
    }

    /**
     * The code whose bounding box sits closest to the middle of the frame — the
     * one under the officer's reticle. A barcode with no box (ML Kit omits it
     * rarely) sorts last rather than being dropped.
     */
    private fun nearestCentre(candidates: List<Barcode>, frameW: Int, frameH: Int): String? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0].rawValue
        val cx = frameW / 2f
        val cy = frameH / 2f
        return candidates.minByOrNull { code ->
            val box = code.boundingBox ?: return@minByOrNull Float.MAX_VALUE
            hypot(box.exactCenterX() - cx, box.exactCenterY() - cy)
        }?.rawValue
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
