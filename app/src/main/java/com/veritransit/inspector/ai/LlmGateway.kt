package com.veritransit.inspector.ai

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geniex.sdk.bean.VlmChatMessage

/**
 * Routes every AI task in the app: the on-device NPU model first, and — when
 * that is not resident or its generation fails — one fixed cloud model
 * ([OpenRouterClient.MODEL]) over OpenRouter. The fallback is automatic and
 * per-call: an officer never picks a backend, the answer just arrives from
 * wherever it could be produced.
 *
 * [InspectorAi] and [ChatSession] call this object instead of [NpuEngine]
 * directly, and screens read [isAvailable] rather than `NpuEngine.isReady`, so
 * the task stays possible on a handset that never pulled the ~3 GB bundle (or
 * has no NPU time to spare) as long as the device is online.
 *
 * The trade-off is disclosure, not just capability: a cloud-served turn sends
 * the prompt and any attached photo off the handset. [lastBackend] records
 * which leg answered, and screens surface it rather than claiming "on-device"
 * unconditionally.
 */
object LlmGateway {

    private const val TAG = "LlmGateway"

    enum class Backend {
        /** Answered by the on-device Qwen3-VL bundle on the Hexagon NPU. */
        NPU,

        /** Answered by the OpenRouter fallback. */
        CLOUD,
    }

    /** True while the Qwen3-VL bundle is resident (or being used) on the NPU. */
    val localReady: Boolean get() = NpuEngine.isReady

    /**
     * The OpenRouter key is injected at build time from the gitignored
     * `local.properties` (see [OpenRouterClient.API_KEY]); without one the
     * cloud leg is off and tasks need the on-device model as before.
     * Reachability is checked by trying, not by probing.
     */
    val cloudReady: Boolean get() = OpenRouterClient.isConfigured

    /** Whether any backend can serve a task right now. */
    val isAvailable: Boolean get() = localReady || cloudReady

    /** Which backend answered the most recent call; null before the first. */
    var lastBackend by mutableStateOf<Backend?>(null)
        private set

    /**
     * Prompt tokens (image tokens included) the answering backend reported for
     * the last completed exchange — [ChatSession]'s budget anchor, so the
     * estimator stays corrected on the cloud path too.
     */
    var lastPromptTokens by mutableStateOf(0L)
        private set

    /** One-line timings/size for the last completed exchange, for the stats row. */
    var lastStats by mutableStateOf<String?>(null)
        private set

    /** Model name + where it is answering from, for headers and status lines. */
    val activeModelLabel: String
        get() = when (lastBackend) {
            Backend.NPU -> "${NpuEngine.DISPLAY_NAME} · on-device"
            Backend.CLOUD -> "${OpenRouterClient.DISPLAY_NAME} · cloud"
            null ->
                if (NpuEngine.isReady) {
                    "${NpuEngine.DISPLAY_NAME} · on-device"
                } else {
                    "${OpenRouterClient.DISPLAY_NAME} · cloud fallback"
                }
        }

    /**
     * One stateless task turn (OCR, reconciliation, remark drafting): local
     * first, cloud only when the NPU is not resident or the generation failed.
     */
    suspend fun run(
        systemPrompt: String,
        userPrompt: String,
        imagePaths: List<String> = emptyList(),
        maxTokens: Int = 640,
        temperature: Float = 0.2f,
        label: String? = null,
        onToken: (String) -> Unit = {},
    ): Result<String> {
        if (NpuEngine.isReady) {
            val local = NpuEngine.run(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                imagePaths = imagePaths,
                maxTokens = maxTokens,
                temperature = temperature,
                label = label,
                onToken = onToken,
            )
            if (local.isSuccess) {
                recordNpu()
                return local
            }
            logFallback(local.exceptionOrNull())
        }
        return OpenRouterClient.chat(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imagePaths = imagePaths,
            maxTokens = maxTokens,
            temperature = temperature,
            onToken = onToken,
        ).onSuccess { recordCloud() }
    }

    /**
     * Multi-turn conversation. [mediaTurn] only matters to the local leg (the
     * NPU encoder takes images from a single turn); the cloud leg forwards
     * every image in [turns].
     */
    suspend fun converse(
        turns: List<VlmChatMessage>,
        mediaTurn: VlmChatMessage,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit = {},
    ): Result<String> {
        if (NpuEngine.isReady) {
            val local = NpuEngine.converse(
                turns = turns,
                mediaTurn = mediaTurn,
                maxTokens = maxTokens,
                temperature = temperature,
                onToken = onToken,
            )
            if (local.isSuccess) {
                recordNpu()
                return local
            }
            logFallback(local.exceptionOrNull())
        }
        return OpenRouterClient.converse(
            turns = turns,
            maxTokens = maxTokens,
            temperature = temperature,
            onToken = onToken,
        ).onSuccess { recordCloud() }
    }

    /** Stops the in-flight generation on whichever backend is streaming. */
    fun stop() {
        NpuEngine.stop()
        OpenRouterClient.cancel()
    }

    private fun logFallback(cause: Throwable?) {
        Log.w(
            TAG,
            "NPU leg failed — falling back to ${OpenRouterClient.DISPLAY_NAME}: " +
                (cause?.message ?: "unknown error"),
        )
    }

    private fun recordNpu() {
        lastBackend = Backend.NPU
        val profile = NpuEngine.lastProfile
        lastPromptTokens = profile?.promptTokens?.coerceAtLeast(0L) ?: 0L
        lastStats = profile?.let {
            "%.0f tok/s · %d tokens".format(it.decodingSpeed, it.generatedTokens)
        }
    }

    private fun recordCloud() {
        lastBackend = Backend.CLOUD
        lastPromptTokens = OpenRouterClient.lastPromptTokens.coerceAtLeast(0L)
        val completion = OpenRouterClient.lastCompletionTokens
        lastStats = when {
            completion > 0L -> "${OpenRouterClient.DISPLAY_NAME} · %d tokens".format(completion)
            else -> "${OpenRouterClient.DISPLAY_NAME} · cloud"
        }
    }
}
