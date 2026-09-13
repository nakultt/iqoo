package com.veritransit.inspector.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.veritransit.inspector.data.QrMatrix
import kotlin.math.floor

/**
 * A QR code for [payload], black on white with its quiet zone, drawn in code
 * like every other visual in the app.
 *
 * Modules land on whole pixels, so neighbouring rows meet without an
 * anti-aliased seam and a phone camera pointed at the screen sees hard edges.
 */
@Composable
fun QrCodeImage(payload: String, modifier: Modifier = Modifier, description: String? = null) {
    val matrix = remember(payload) { QrMatrix.encode(payload) }
    val runs = remember(matrix) { List(matrix.size) { matrix.darkRuns(it) } }
    Canvas(
        modifier
            .aspectRatio(1f)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        drawRect(Color.White)
        val cells = matrix.size + 2 * QrMatrix.QUIET_ZONE
        val module = floor(size.minDimension / cells)
        if (module < 1f) return@Canvas
        val left = floor((size.width - module * cells) / 2) + module * QrMatrix.QUIET_ZONE
        val top = floor((size.height - module * cells) / 2) + module * QrMatrix.QUIET_ZONE
        runs.forEachIndexed { y, row ->
            for (run in row) {
                drawRect(
                    Color.Black,
                    topLeft = Offset(left + run.first * module, top + y * module),
                    size = Size((run.last - run.first + 1) * module, module),
                )
            }
        }
    }
}
