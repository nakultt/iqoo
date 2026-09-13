package com.veritransit.inspector.data

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR symbol as a square grid of dark and light modules, for drawing on screen
 * or on a printed label sheet. ZXing does the encoding; whoever draws it leaves
 * [QUIET_ZONE] light modules clear on every side.
 */
class QrMatrix private constructor(val size: Int, private val dark: BooleanArray) {

    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    /** Row [y]'s dark modules as column runs — one rectangle to draw per run, not per module. */
    fun darkRuns(y: Int): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var x = 0
        while (x < size) {
            if (!this[x, y]) {
                x++
                continue
            }
            val start = x
            while (x < size && this[x, y]) x++
            runs += start until x
        }
        return runs
    }

    companion object {
        /** Light margin a reader needs around the symbol, in modules (ISO/IEC 18004). */
        const val QUIET_ZONE = 4

        /**
         * [text] at error-correction level Q: about a quarter of the symbol can
         * be lost to a scuff, a crease or tape glare and it still reads — worth
         * the slightly larger symbol on a box that travels.
         */
        fun encode(text: String): QrMatrix {
            val matrix = Encoder.encode(text, ErrorCorrectionLevel.Q).matrix
            val size = matrix.width
            return QrMatrix(size, BooleanArray(size * size) { i -> matrix.get(i % size, i / size).toInt() == 1 })
        }
    }
}
