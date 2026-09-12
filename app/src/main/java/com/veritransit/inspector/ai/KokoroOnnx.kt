package com.veritransit.inspector.ai

import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "KokoroOnnx"

/** Kokoro-82M sample rate — fixed by the model, not a choice. */
const val KOKORO_SAMPLE_RATE = 24000

/**
 * True Kokoro inference on the phone CPU (quantized ONNX via ORT).
 *
 * Model: `model_quantized.onnx` (~92 MB, runtime-downloaded, never in the
 * APK). Voices: `af_sarah.bin`-style packs — 510 style rows of 256 floats;
 * upstream Kokoro indexes the pack by utterance length ("voice packing"), so
 * the style row is `pack[min(tokenCount, 509)]`, not a global mean. Speed 1.0.
 *
 * One session per process; inference runs off the main thread and takes a
 * low-single-digit number of seconds for alert-length sentences on a
 * flagship CPU. Short alerts only — callers cap input at 300 tokens.
 */
class KokoroOnnx(modelFile: File) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) },
    )

    init {
        Log.i(TAG, "session ready: ${session.inputNames} -> ${session.outputNames}")
    }

    /**
     * Runs the model. [styleRow] is one 256-float voice row.
     * @return mono PCM floats in [-1, 1] at [KOKORO_SAMPLE_RATE].
     */
    fun synthesize(ids: LongArray, styleRow: FloatArray, speed: Float = 1.0f): FloatArray {
        require(styleRow.size == 256) { "style must be 256 floats" }
        val inputs = mapOf(
            "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())),
            "style" to OnnxTensor.createTensor(env, FloatBuffer.wrap(styleRow), longArrayOf(1, 256)),
            "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)),
        )
        session.run(inputs).use { out ->
            @Suppress("UNCHECKED_CAST")
            val wave = ((out.get("waveform").get() as OnnxTensor).value as Array<FloatArray>)[0]
            inputs.values.forEach { runCatching { it.close() } }
            return wave
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** Reads one style row from a 510×256 float32-LE voice pack. */
        fun styleRow(pack: File, row: Int): FloatArray? {
            if (!pack.isFile || pack.length() != 510L * 256 * 4) return null
            return try {
                val all = pack.readBytes()
                val r = row.coerceIn(0, 509)
                val out = FloatArray(256)
                val buf = java.nio.ByteBuffer.wrap(all, r * 256 * 4, 256 * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until 256) out[i] = buf.float
                out
            } catch (t: Throwable) {
                Log.w(TAG, "voice pack unreadable", t)
                null
            }
        }
    }
}
