package com.veritransit.inspector.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.ceil

private const val TAG = "Evidence"

/**
 * Handle for the live camera feed, held by a screen and driven by its buttons.
 * [ready] flips true once the use case is bound, which is what gates the
 * shutter — pressing capture before then throws inside CameraX.
 */
@Stable
class EvidenceCamera {
    internal var imageCapture: ImageCapture? = null
    internal var camera: Camera? = null

    var ready by mutableStateOf(false)
        internal set

    var error by mutableStateOf<String?>(null)
        internal set

    /** Whether this camera has a flash unit to offer as an assist light. */
    val hasTorch: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    fun setTorch(on: Boolean) {
        runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    /**
     * Takes a frame and returns it square and upright, sized for the vision
     * tower. [fit] chooses how a non-square frame is made square — see
     * [squareFit] and [squareCrop]. Returns null if the camera is not bound or
     * the shot fails.
     */
    suspend fun capture(context: Context, fit: Fit = Fit.COVER): File? {
        val capture = imageCapture ?: return null
        val raw = File(context.cacheDir, "evidence_raw_${System.currentTimeMillis()}.jpg")
        val saved = suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(raw).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "capture failed", exception)
                        error = exception.message
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }
        if (!saved) return null
        return withContext(Dispatchers.IO) {
            val out = File(context.cacheDir, "evidence_${System.currentTimeMillis()}.jpg")
            runCatching {
                when (fit) {
                    Fit.COVER -> squareCrop(raw, out, NpuEngine.VISION_INPUT_PX)
                    Fit.CONTAIN -> squareFit(raw, out, NpuEngine.VISION_INPUT_PX)
                }
            }
                .onFailure { Log.e(TAG, "resize failed", it) }
                .also { raw.delete() }
                .getOrNull()
        }
    }

    /** How a non-square frame is squared off for the encoder. */
    enum class Fit {
        /** Fill the square, trimming the long edge. For scenes. */
        COVER,

        /** Fit the whole frame inside the square. For documents. */
        CONTAIN,
    }
}

/**
 * Live viewfinder bound to the composition's lifecycle. The preview is the
 * officer's framing aid; the still that reaches the model comes from a separate
 * full-resolution capture so framing quality does not cap OCR quality.
 */
@Composable
fun EvidenceViewfinder(camera: EvidenceCamera, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    // Documents and stencilled carton labels are detail-bound,
                    // not latency-bound — the extra ~200 ms buys legible text.
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider?.unbindAll()
                camera.camera = provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                camera.imageCapture = capture
                camera.ready = true
                camera.error = null
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                camera.error = e.message
                camera.ready = false
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            camera.imageCapture = null
            camera.camera = null
            camera.ready = false
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Rotates [source] upright per its EXIF tag, scales its shorter edge to [size],
 * then centre-crops a [size]x[size] square into [out].
 *
 * Filling the square edge to edge keeps full resolution on the middle of the
 * frame, which is what a photograph of a cargo bay wants — the subject is in
 * the centre and the edges are bay wall. Use [squareFit] for anything where
 * losing the edges loses content.
 */
/**
 * Decodes [source], downsampling during decode but never below [size] on the
 * shorter edge, and rotates it upright per its EXIF tag. Returns null if the
 * file will not decode.
 */
private fun decodeUpright(source: File, size: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)

    val opts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = run {
            val shorter = minOf(bounds.outWidth, bounds.outHeight)
            var s = 1
            while (shorter / (s * 2) >= size) s *= 2
            s
        }
    }
    var bmp = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return null

    val orientation = runCatching {
        ExifInterface(source.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        }
    }
    if (!matrix.isIdentity) {
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated !== bmp) {
            bmp.recycle()
            bmp = rotated
        }
    }
    return bmp
}

/**
 * Rotates [source] upright and fits the whole frame inside a [size]x[size]
 * square on white, padding the short axis.
 *
 * This is the right shape for documents and the wrong one for scenes. A
 * portrait photo of an A4 bill is about 3:4, so a centre-crop would take a
 * quarter of the page off — and the part it takes is the goods table at the
 * bottom. Padding costs some of the fixed 256 image tokens, but losing the
 * table costs the whole reading. White, not black, because that is what the
 * paper around the print already is.
 */
internal fun squareFit(source: File, out: File, size: Int): File {
    val bmp = decodeUpright(source, size) ?: error("decode failed")
    val scale = size.toFloat() / maxOf(bmp.width, bmp.height)
    val w = (bmp.width * scale).toInt().coerceAtLeast(1)
    val h = (bmp.height * scale).toInt().coerceAtLeast(1)
    val scaled = if (bmp.width != w || bmp.height != h) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
    if (scaled !== bmp) bmp.recycle()

    val canvasBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(canvasBmp).apply {
        drawColor(Color.WHITE)
        drawBitmap(scaled, ((size - w) / 2).toFloat(), ((size - h) / 2).toFloat(), null)
    }
    if (!scaled.isRecycled) scaled.recycle()

    FileOutputStream(out).use { canvasBmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    canvasBmp.recycle()
    return out
}

internal fun squareCrop(source: File, out: File, size: Int): File {
    val bmp = decodeUpright(source, size) ?: error("decode failed")

    val scale = size.toFloat() / minOf(bmp.width, bmp.height)
    val w = ceil(bmp.width * scale).toInt().coerceAtLeast(size)
    val h = ceil(bmp.height * scale).toInt().coerceAtLeast(size)
    val scaled = if (bmp.width != w || bmp.height != h) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
    if (scaled !== bmp) bmp.recycle()

    val cropped = Bitmap.createBitmap(scaled, (scaled.width - size) / 2, (scaled.height - size) / 2, size, size)
    if (cropped !== scaled) scaled.recycle()

    FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    if (!cropped.isRecycled) cropped.recycle()
    return out
}
