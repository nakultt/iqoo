package com.veritransit.inspector.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.veritransit.core.ReasonCode
import com.veritransit.core.ScanResult
import com.veritransit.core.VoiceAlertText
import com.veritransit.inspector.data.DeviceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val TAG = "KokoroVoice"

/**
 * The phone's voice: TRUE Kokoro neural speech first, system TTS behind it.
 *
 * When `files/kokoro/model_quantized.onnx` + the selected voice pack are on
 * disk, verdicts are spoken by the Kokoro-82M model itself (af_sarah default)
 * — real Kokoro voices, fully offline. Anything missing or failing, and the
 * same sentences go out over Android's offline TTS instead, so a dock never
 * goes silent because a download didn't finish.
 *
 * Every screen talks to [speakScan]/[speakGate]/[synthesizeVoiceNote]; the
 * engine choice is internal, which is also what lets the neural runtime swap
 * in without touching callers.
 */
object KokoroVoice {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // System TTS fallback.
    private var tts: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)
    private val ttsPending = ConcurrentLinkedQueue<String>()

    // Neural engine, loaded lazily off the main thread.
    @Volatile private var onnx: KokoroOnnx? = null
    private val onnxLoading = AtomicBoolean(false)

    /** Last voice description, for Settings. */
    var voiceLabel: String = "initialising…"
        private set

    fun initialize(appContext: Context) {
        if (tts == null) {
            tts = TextToSpeech(appContext.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    voiceLabel = neuralLabel(appContext) ?: "unavailable"
                    return@TextToSpeech
                }
                ttsReady.set(true)
                selectSystemVoice()
                while (true) ttsPending.poll()?.let { speakSystemNow(it) } ?: break
            }
        }
        maybeWarmNeural(appContext)
    }

    /** True when Kokoro weights + voice are ready to speak without network. */
    fun neuralReady(context: Context): Boolean {
        if (onnx != null) return true
        val s = DeviceSettings(context)
        return KokoroDownload.hasModel(context) &&
            KokoroDownload.hasVoice(context, s.kokoroVoice)
    }

    // ------------------------------------------------------- spoken verdicts

    fun speakScan(
        context: Context,
        result: ScanResult,
        packageCode: String?,
        reasons: List<ReasonCode>,
        announcePasses: Boolean,
    ) {
        if (tryNeuralScan(context, result, packageCode, reasons, announcePasses)) return
        val text = VoiceAlertText.forScan(result, packageCode, reasons, announcePasses) ?: return
        speakSystem(text)
    }

    fun speakGateCounted(context: Context, ewbDigits: String, lines: Int, unlisted: Int) {
        if (lines == 0 && unlisted == 0) return
        if (tryNeuralGate(context, ewbDigits, lines, unlisted)) return
        val text = VoiceAlertText.forGateCounted(ewbDigits, lines, unlisted) ?: return
        speakSystem(text)
    }

    fun speakSystem(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!ttsReady.get()) {
            if (ttsPending.size < 4) ttsPending.add(clean)
            return
        }
        speakSystemNow(clean)
    }

    // ------------------------------------------------------- voice-note files

    /**
     * Renders the alert to a WAV — neural Kokoro PCM when available, system
     * TTS synthesis otherwise. This exact audio is what the supervisor hears.
     */
    suspend fun synthesizeVoiceNote(
        context: Context,
        result: ScanResult,
        packageCode: String?,
        reasons: List<ReasonCode>,
    ): File? {
        // Neural path first: same phonemes the dock would hear live.
        val phonemes = KokoroG2P.scan(result, packageCode, reasons, announcePasses = false)
        if (phonemes != null) {
            val pcm = runNeural(context, phonemes)
            if (pcm != null) return writeWav(context, pcm)
        }
        // System fallback renders the full text (with item names, if any).
        val text = VoiceAlertText.forScan(result, packageCode, reasons, false) ?: return null
        return synthesizeSystem(context, text)
    }

    // ------------------------------------------------------- internals: neural

    private fun engine(context: Context): KokoroOnnx? {
        onnx?.let { return it }
        if (!KokoroDownload.hasModel(context)) return null
        val s = DeviceSettings(context)
        if (!KokoroDownload.hasVoice(context, s.kokoroVoice)) return null
        if (!onnxLoading.compareAndSet(false, true)) return null
        return try {
            KokoroOnnx(KokoroDownload.modelFile(context)).also {
                onnx = it
                voiceLabel = "Kokoro ${s.kokoroVoice} · neural on-device"
                Log.i(TAG, "neural engine ready")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "neural engine failed to load", t)
            null
        } finally {
            onnxLoading.set(false)
        }
    }

    private fun maybeWarmNeural(appContext: Context) {
        scope.launch {
            runCatching { engine(appContext.applicationContext) }
            val label = neuralLabel(appContext.applicationContext)
            if (label != null && !ttsReady.get()) voiceLabel = label
        }
    }

    private fun neuralLabel(context: Context): String? {
        val s = DeviceSettings(context)
        return if (KokoroDownload.hasModel(context) && KokoroDownload.hasVoice(context, s.kokoroVoice)) {
            "Kokoro ${s.kokoroVoice} · neural on-device"
        } else null
    }

    private fun styleFor(context: Context, tokenCount: Int): FloatArray? {
        val s = DeviceSettings(context)
        return KokoroOnnx.styleRow(
            KokoroDownload.voiceFile(context, s.kokoroVoice),
            tokenCount,
        )
    }

    private fun runNeural(context: Context, phonemes: String): FloatArray? {
        val app = context.applicationContext
        val eng = try {
            engine(app)
        } catch (t: Throwable) {
            Log.w(TAG, "neural run failed", t)
            null
        } ?: return null
        return try {
            val ids = KokoroVocab.tokenize(phonemes)
            if (ids.isEmpty()) return null
            val style = styleFor(app, ids.size) ?: return null
            eng.synthesize(ids, style)
        } catch (t: Throwable) {
            Log.w(TAG, "neural synth failed", t)
            null
        }
    }

    private fun tryNeuralScan(
        context: Context,
        result: ScanResult,
        code: String?,
        reasons: List<ReasonCode>,
        announcePasses: Boolean,
    ): Boolean {
        if (!neuralReady(context)) return false
        val phonemes = KokoroG2P.scan(result, code, reasons, announcePasses) ?: return false
        scope.launch {
            val pcm = runNeural(context, phonemes)
            if (pcm != null) playPcm(pcm) else {
                VoiceAlertText.forScan(result, code, reasons, announcePasses)?.let { speakSystem(it) }
            }
        }
        return true
    }

    private fun tryNeuralGate(context: Context, ewbDigits: String, lines: Int, unlisted: Int): Boolean {
        if (!neuralReady(context)) return false
        val phonemes = KokoroG2P.gate(ewbDigits, lines, unlisted) ?: return false
        scope.launch {
            val pcm = runNeural(context, phonemes)
            if (pcm != null) playPcm(pcm) else {
                VoiceAlertText.forGateCounted(ewbDigits, lines, unlisted)?.let { speakSystem(it) }
            }
        }
        return true
    }

    private fun playPcm(pcm: FloatArray) {
        try {
            val pcm16 = ShortArray(pcm.size) { i ->
                (pcm[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(KOKORO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes((pcm16.size * 2).coerceAtLeast(8192))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm16, 0, pcm16.size)
            track.play()
            // Block until done, then release — static mode, short clips only.
            val ms = (pcm16.size * 1000L) / KOKORO_SAMPLE_RATE + 400
            Thread.sleep(ms)
            runCatching { track.stop() }
            runCatching { track.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "playback failed", t)
        }
    }

    private fun writeWav(context: Context, pcm: FloatArray): File? {
        return try {
            val out = File(context.cacheDir, "voice_alert_${System.currentTimeMillis()}.wav")
            val data = ByteArray(pcm.size * 2)
            val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { buf.putShort((it.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()) }
            out.outputStream().use { f ->
                f.write(wavHeader(data.size))
                f.write(data)
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "wav write failed", t)
            null
        }
    }

    private fun wavHeader(dataBytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataBytes); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(KOKORO_SAMPLE_RATE); b.putInt(KOKORO_SAMPLE_RATE * 2)
        b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(dataBytes)
        return b.array()
    }

    // ------------------------------------------------------- internals: system

    private fun speakSystemNow(text: String) {
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), UUID.randomUUID().toString())
        }.onFailure { Log.w(TAG, "speak failed", it) }
    }

    private suspend fun synthesizeSystem(appContext: Context, text: String): File? {
        val engine = tts
        if (!ttsReady.get() || engine == null) return null
        val out = File(appContext.cacheDir, "voice_alert_${System.currentTimeMillis()}.wav")
        return suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) {
                    if (utteranceId == id && cont.isActive) cont.resume(out.takeIf { it.exists() })
                }
                @Deprecated("deprecated")
                override fun onError(utteranceId: String) {
                    if (utteranceId == id && cont.isActive) cont.resume(null)
                }
                override fun onError(utteranceId: String, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resume(null)
                }
            }
            engine.setOnUtteranceProgressListener(listener)
            val rc = engine.synthesizeToFile(text, Bundle(), out, id)
            if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(null)
        }
    }

    private fun selectSystemVoice() {
        val engine = tts ?: return
        val voices: Set<Voice> = runCatching { engine.voices }.getOrNull().orEmpty()
        val pick = voices.firstOrNull { it.locale == Locale("en", "IN") && !it.isNetworkConnectionRequired }
            ?: voices.firstOrNull { it.locale.language == "en" && !it.isNetworkConnectionRequired }
        if (pick != null) {
            runCatching { engine.voice = pick }
            if (onnx == null) voiceLabel = "${pick.locale.displayName} · Kokoro profile (system)"
        } else {
            runCatching { engine.language = Locale("en", "IN") }
            if (onnx == null) voiceLabel = "default voice · Kokoro profile (system)"
        }
        engine.setSpeechRate(1.0f)
        engine.setPitch(1.05f)
    }

    /** Test hook for Settings ("does Kokoro actually speak?"). */
    fun testAnnouncement(context: Context) {
        speakScan(
            context, ScanResult.REJECTED, "VT-P-FORGED01",
            listOf(ReasonCode.SIGNATURE_INVALID), announcePasses = false,
        )
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady.set(false)
        runCatching { onnx?.close() }
        onnx = null
    }
}
