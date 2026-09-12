package com.veritransit.inspector.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * On-device QR rendering for the sender flow.
 *
 * The phone is the label printer: after the backend signs a token (or the
 * offline draft path mints a DRAFT payload), this turns the payload string
 * into a scannable bitmap with no network. ZXing core only — no Android
 * dependency, works offline.
 */
object QrCode {
    fun render(content: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to "1",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) 0xFF141414.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bmp
    }
}

@Composable
fun QrImage(payload: String, modifier: Modifier = Modifier, sizePx: Int = 512) {
    val bmp = remember(payload) {
        runCatching { QrCode.render(payload, sizePx) }.getOrNull()
    }
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = "QR $payload", modifier = modifier)
    }
}
