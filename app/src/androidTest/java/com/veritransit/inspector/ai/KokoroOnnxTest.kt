package com.veritransit.inspector.ai

import androidx.test.platform.app.InstrumentationRegistry
import com.veritransit.core.ReasonCode
import com.veritransit.core.ScanResult
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Proves true Kokoro speech on real hardware: the quantized model behind
 * [KokoroOnnx] turns the fixed phoneme tables into non-silent 24 kHz audio.
 *
 * Needs the runtime weights in the app's files dir (Settings → Kokoro neural
 * voice → Download, or adb push) — the suite skips itself rather than failing
 * without them, like the NPU suite. Run with `am instrument` (never
 * `connectedDebugAndroidTest`, which wipes the app data dir).
 */
class KokoroOnnxTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun every_fixed_sentence_tokenizes() {
        var covered = 0
        (KokoroPhrases.SENTENCE.values + KokoroPhrases.LETTER.values).forEach { phonemes ->
            val ids = KokoroVocab.tokenize(phonemes)
            assertTrue("untranscribable: $phonemes", ids.isNotEmpty())
            covered++
        }
        android.util.Log.i("KokoroOnnx", "$covered fixed strings tokenize")
    }

    @Test
    fun neural_speaks_reject_alert() {
        if (!KokoroDownload.hasModel(target) ||
            !KokoroDownload.hasVoice(target, "af_sarah")
        ) {
            android.util.Log.i("KokoroOnnx", "SKIP — no Kokoro weights on device")
            return
        }
        val phonemes = KokoroG2P.scan(
            ScanResult.REJECTED, "VT-P-FORGED01",
            listOf(ReasonCode.SIGNATURE_INVALID),
        )!!
        val ids = KokoroVocab.tokenize(phonemes)
        assertTrue("reject alert tokenized empty", ids.isNotEmpty())

        val engine = KokoroOnnx(KokoroDownload.modelFile(target))
        val style = KokoroOnnx.styleRow(
            KokoroDownload.voiceFile(target, "af_sarah"), ids.size,
        )!!
        val pcm = engine.synthesize(ids, style)
        engine.close()

        assertTrue("no audio produced", pcm.size > KOKORO_SAMPLE_RATE)
        val peak = pcm.maxOf { abs(it) }
        assertTrue("silent waveform (peak=$peak)", peak > 0.05f)
        val seconds = pcm.size.toDouble() / KOKORO_SAMPLE_RATE
        android.util.Log.i(
            "KokoroOnnx",
            "reject alert: ${ids.size} tokens -> ${"%.1f".format(seconds)}s audio, peak=${"%.2f".format(peak)}",
        )
    }
}
