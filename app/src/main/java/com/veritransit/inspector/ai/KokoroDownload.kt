package com.veritransit.inspector.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

private const val TAG = "KokoroDownload"

/**
 * Runtime fetch for the Kokoro weights — the 92 MB quantized model and the
 * tiny voice packs. Never bundled (APK stays shippable); downloads once into
 * the app's private `files/kokoro/` dir, resumable across process death by
 * simple existence checks, deletable from Settings.
 */
object KokoroDownload {

    private const val BASE =
        "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main"

    const val MODEL_URL = "$BASE/onnx/model_quantized.onnx"
    const val MODEL_BYTES = 92_361_116L

    /** Voices offered in Settings. Sarah carries the gate; Bella the backup. */
    val VOICES = listOf("af_sarah", "af_bella")

    fun voiceUrl(name: String) = "$BASE/voices/$name.bin"

    fun dir(context: Context): File = File(context.filesDir, "kokoro").apply { mkdirs() }
    fun modelFile(context: Context): File = File(dir(context), "model_quantized.onnx")
    fun voiceFile(context: Context, name: String): File = File(dir(context), "$name.bin")

    fun hasModel(context: Context): Boolean =
        modelFile(context).let { it.isFile && it.length() > MODEL_BYTES / 2 }

    fun hasVoice(context: Context, name: String): Boolean =
        voiceFile(context, name).let { it.isFile && it.length() == 510L * 256 * 4 }

    suspend fun fetchModel(context: Context, onProgress: (Int) -> Unit): Boolean =
        download(MODEL_URL, modelFile(context), onProgress)

    suspend fun fetchVoice(context: Context, name: String): Boolean =
        download(voiceUrl(name), voiceFile(context, name)) {}

    private suspend fun download(url: String, out: File, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "VeriTransit/1.1")
                }
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "HTTP ${conn.responseCode} for $url")
                    return@withContext false
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                val tmp = File(out.parent, out.name + ".part")
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                if (tmp.renameTo(out)) {
                    onProgress(100)
                    true
                } else false
            } catch (t: Throwable) {
                Log.w(TAG, "download failed", t)
                false
            } finally {
                conn?.disconnect()
            }
        }
}
