package com.veritransit.inspector.data

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The QR symbols sender mode draws, read back by a real QR decoder. The test
 * image is painted from [QrMatrix.darkRuns] — the runs the screen and the
 * printed sheet draw — with the quiet zone around it, and the decoder has to
 * find the symbol itself, as it would in a camera frame.
 */
class QrMatrixTest {

    private val electronics = Presets.ALL.first()

    private fun decode(matrix: QrMatrix, scale: Int = 3): String {
        val quiet = QrMatrix.QUIET_ZONE
        val side = (matrix.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { WHITE }
        for (y in 0 until matrix.size) {
            for (run in matrix.darkRuns(y)) {
                for (x in run) {
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = BLACK
                        }
                    }
                }
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun `every label of a master box decodes back to its payload`() {
        val box = BoxPacking.build("MB-4471-01", electronics, BoxPacking.distribute(electronics.items, 3), 0L)
        for (label in box.labels()) {
            assertEquals(label.payload(), decode(QrMatrix.encode(label.payload())))
        }
    }

    @Test
    fun `dark runs cover exactly the dark modules`() {
        val matrix = QrMatrix.encode(BoxLabel.Master("PO-2025-4471", "PL-2025-4471-A", "MB-4471-01", 2, 7).payload())
        for (y in 0 until matrix.size) {
            assertEquals((0 until matrix.size).filter { matrix[it, y] }, matrix.darkRuns(y).flatten())
        }
    }

    @Test
    fun `the longest preset box label stays a small symbol at level Q`() {
        // Both electronics SKUs in one box: the most a preset puts on one label.
        val box = BoxPacking.build("MB-4471-01", electronics, BoxPacking.distribute(electronics.items, 1), 0L)
        val payload = box.boxLabel(box.boxes.single()).payload()
        val version = Encoder.encode(payload, ErrorCorrectionLevel.Q).version.versionNumber
        assertTrue(version <= 7, "version $version for $payload")
        assertEquals(17 + 4 * version, QrMatrix.encode(payload).size)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
