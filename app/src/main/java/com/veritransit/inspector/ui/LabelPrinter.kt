package com.veritransit.inspector.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import com.veritransit.inspector.data.BoxLabel
import com.veritransit.inspector.data.MasterBox
import com.veritransit.inspector.data.QrMatrix
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Prints a master box's QR labels through Android's print framework — to any
 * installed print service, or to a file with "Save as PDF". The labels are
 * cut-out cards in a grid fitted to whatever paper is chosen in the system
 * dialog, and the QR modules are vector rectangles, sharp at any resolution.
 */
object LabelPrinter {

    /** Opens the system print dialog for every label of [box]. [context] must be an Activity. */
    fun print(context: Context, box: MasterBox) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build()
        manager.print("${box.id} labels", LabelSheetAdapter(context, box), attributes)
    }
}

private const val POINTS_PER_MIL = 72f / 1000f
private const val PAD = 10f

/** Page geometry in points (1/72 in): [columns] × [rows] label cells of [cellWidth] × [cellHeight]. */
private data class Sheet(
    val columns: Int,
    val rows: Int,
    val cellWidth: Float,
    val cellHeight: Float,
    val pages: Int,
) {
    val perPage: Int get() = columns * rows

    companion object {
        /** About 10 mm, inside the reach of most printers. */
        const val MARGIN = 28f
        const val GAP = 10f

        /** No label smaller than about 85 × 50 mm, so its QR code stays around 40 mm wide. */
        const val MIN_CELL_WIDTH = 240f
        const val MIN_CELL_HEIGHT = 140f

        fun fit(pageWidth: Float, pageHeight: Float, labels: Int): Sheet {
            val columns = floor((pageWidth - 2 * MARGIN + GAP) / (MIN_CELL_WIDTH + GAP)).toInt().coerceAtLeast(1)
            val rows = floor((pageHeight - 2 * MARGIN + GAP) / (MIN_CELL_HEIGHT + GAP)).toInt().coerceAtLeast(1)
            return Sheet(
                columns = columns,
                rows = rows,
                cellWidth = (pageWidth - 2 * MARGIN - (columns - 1) * GAP) / columns,
                cellHeight = (pageHeight - 2 * MARGIN - (rows - 1) * GAP) / rows,
                pages = ceil(labels / (columns * rows).toDouble()).toInt().coerceAtLeast(1),
            )
        }
    }
}

private class LabelSheetAdapter(private val context: Context, private val box: MasterBox) : PrintDocumentAdapter() {

    private val labels = box.labels()
    private var attributes: PrintAttributes? = null
    private var sheet: Sheet? = null

    private val cutLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f
        color = Color.GRAY
        pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
    }
    private val ink = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val kindText = text(9f, Typeface.SANS_SERIF, bold = true)
    private val idText = text(11f, Typeface.MONOSPACE, bold = true)
    private val detailText = text(8f, Typeface.MONOSPACE, bold = false)
    private val bodyText = text(8.5f, Typeface.SANS_SERIF, bold = false)

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val media = newAttributes.mediaSize
        if (media == null) {
            callback.onLayoutFailed("No paper size chosen")
            return
        }
        val next = Sheet.fit(media.widthMils * POINTS_PER_MIL, media.heightMils * POINTS_PER_MIL, labels.size)
        val changed = next != sheet
        attributes = newAttributes
        sheet = next
        val info = PrintDocumentInfo.Builder("${box.id}-labels.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(next.pages)
            .build()
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val attributes = attributes
        val sheet = sheet
        if (attributes == null || sheet == null) {
            callback.onWriteFailed("The labels were not laid out")
            return
        }
        val pdf = PrintedPdfDocument(context, attributes)
        try {
            val written = mutableListOf<PageRange>()
            for (page in 0 until sheet.pages) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                if (pages.none { page in it.start..it.end }) continue
                val pdfPage = pdf.startPage(page)
                drawPage(pdfPage.canvas, sheet, page)
                pdf.finishPage(pdfPage)
                written += PageRange(page, page)
            }
            pdf.writeTo(FileOutputStream(destination.fileDescriptor))
            callback.onWriteFinished(written.toTypedArray())
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
        } finally {
            pdf.close()
        }
    }

    private fun drawPage(canvas: Canvas, sheet: Sheet, page: Int) {
        val first = page * sheet.perPage
        for (i in first until min(first + sheet.perPage, labels.size)) {
            val slot = i - first
            val left = Sheet.MARGIN + (slot % sheet.columns) * (sheet.cellWidth + Sheet.GAP)
            val top = Sheet.MARGIN + (slot / sheet.columns) * (sheet.cellHeight + Sheet.GAP)
            drawLabel(canvas, labels[i], RectF(left, top, left + sheet.cellWidth, top + sheet.cellHeight))
        }
    }

    private fun drawLabel(canvas: Canvas, label: BoxLabel, cell: RectF) {
        canvas.drawRoundRect(cell, 6f, 6f, cutLine)
        canvas.save()
        canvas.clipRect(cell)
        val qrSide = min(cell.height() - 2 * PAD, cell.width() * 0.45f)
        drawQr(canvas, label.payload(), cell.left + PAD, cell.top + (cell.height() - qrSide) / 2, qrSide)

        val x = cell.left + PAD + qrSide + PAD
        val width = cell.right - PAD - x
        var y = cell.top + PAD
        fun line(text: String, paint: Paint, space: Float = 0f) {
            y += space - paint.ascent()
            canvas.drawText(fit(text, paint, width), x, y, paint)
            y += paint.descent()
        }
        when (label) {
            is BoxLabel.Master -> {
                line("MASTER BOX", kindText)
                line(label.id, idText, 3f)
                line("${label.boxCount} boxes inside · ${label.units} units", bodyText, 3f)
                line(label.purchaseOrderId, detailText, 8f)
                line(label.packingListId, detailText, 1f)
                line(box.supplier, bodyText, 8f)
                line("Ship to ${box.shipTo}", bodyText, 1f)
            }
            is BoxLabel.Inner -> {
                line("BOX ${label.seq} OF ${label.of}", kindText)
                line(label.id, idText, 3f)
                line("In ${label.masterId}", detailText, 3f)
                line(label.purchaseOrderId, detailText, 1f)
                label.lines.forEachIndexed { i, item ->
                    line("${item.qty} × ${box.itemNames[item.sku] ?: item.sku}", bodyText, if (i == 0) 8f else 2f)
                    line(item.sku, detailText, 1f)
                }
            }
        }
        canvas.restore()
    }

    private fun drawQr(canvas: Canvas, payload: String, left: Float, top: Float, side: Float) {
        val matrix = QrMatrix.encode(payload)
        val module = side / (matrix.size + 2 * QrMatrix.QUIET_ZONE)
        val x0 = left + QrMatrix.QUIET_ZONE * module
        val y0 = top + QrMatrix.QUIET_ZONE * module
        // One path for the whole symbol: touching runs fill as a single shape,
        // with no hairline between rows at any zoom or printer resolution.
        val path = Path()
        for (y in 0 until matrix.size) {
            for (run in matrix.darkRuns(y)) {
                path.addRect(
                    x0 + run.first * module, y0 + y * module,
                    x0 + (run.last + 1) * module, y0 + (y + 1) * module,
                    Path.Direction.CW,
                )
            }
        }
        canvas.drawPath(path, ink)
    }

    /** [text] cut short with an ellipsis so it fits [width] points. */
    private fun fit(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText(ellipsis) > width) end--
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun text(size: Float, family: Typeface, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        typeface = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}
