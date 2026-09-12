package com.veritransit.inspector.ai

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geniex.sdk.bean.VlmChatMessage
import java.io.IOException

/**
 * Routes every AI task in the app to one of two engines: the on-device NPU
 * model (Qwen3-VL) or a fixed cloud model ([OpenRouterClient.MODEL]) over
 * OpenRouter. The officer picks the engine in Settings ([mode]) and the chosen
 * leg answers first; the other leg stays in the list as the safety net, so a
 * mid-shift outage (NPU unloaded, no network, rejected key) degrades to an
 * answer from the spare leg instead of a dead screen. Which leg actually
 * answered is always disclosed — [lastBackend] records it and screens surface
 * it rather than claiming "on-device" unconditionally.
 *
 * [InspectorAi] and [ChatSession] call this object instead of [NpuEngine]
 * directly, and screens read [isAvailable] rather than `NpuEngine.isReady`, so
 * the task stays possible on a handset that never pulled the ~3 GB bundle (or
 * has no NPU time to spare) as long as the device is online.
 *
 * The trade-off is disclosure, not just capability: a cloud-served turn sends
 * the prompt and any attached photo off the handset. The engine selector and
 * the per-reply disclosures exist so that cost is always visible before and
 * after the fact.
 */
object LlmGateway {

    private const val TAG = "LlmGateway"

    enum class Backend {
        /** Answered by the on-device Qwen3-VL bundle on the Hexagon NPU. */
        NPU,

        /** Answered by the OpenRouter fallback. */
        CLOUD,
    }

    /** Which engine the officer has selected in Settings. */
    enum class Mode {
        /** On-device NPU first; cloud only as the safety net. */
        LOCAL,

        /** GLM-5.3-Flash over OpenRouter first; the NPU as the safety net. */
        CLOUD,
    }

    /**
     * The selected engine. [Mode.LOCAL] is the default and reproduces the
     * original NPU-first routing exactly; [Mode.CLOUD] promotes the OpenRouter
     * leg to primary for officers who want the stronger model or have no NPU
     * time to spare. Deliberately not persisted: the app keeps all state in
     * memory (see [com.veritransit.inspector.data.Repo]), and a fresh process
     * starting on the private local leg is the safe default.
     */
    var mode by mutableStateOf(Mode.LOCAL)

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

    /** Model name + where it is answering from, for headers and status lines. */
    val activeModelLabel: String
        get() = when (lastBackend) {
            Backend.NPU -> "${NpuEngine.DISPLAY_NAME} · on-device"
            Backend.CLOUD -> "${OpenRouterClient.DISPLAY_NAME} · cloud"
            null -> when {
                mode == Mode.CLOUD && cloudReady -> "${OpenRouterClient.DISPLAY_NAME} · cloud"
                localReady -> "${NpuEngine.DISPLAY_NAME} · on-device"
                cloudReady -> "${OpenRouterClient.DISPLAY_NAME} · cloud fallback"
                else -> "no AI backend available"
            }
        }

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

    /** Can this leg serve a call right now? */
    private fun legReady(leg: Backend): Boolean = when (leg) {
        Backend.NPU -> localReady
        Backend.CLOUD -> cloudReady
    }

    /**
     * The legs that may serve the next call, preferred engine first. Selecting
     * an engine decides who answers first, not who may answer at all: the
     * spare leg stays eligible so a mid-shift outage on the preferred one
     * degrades to an answer (disclosed via [lastBackend]) instead of a dead
     * screen.
     */
    private fun legOrder(): List<Backend> {
        val preferred = when (mode) {
            Mode.CLOUD -> if (cloudReady) Backend.CLOUD else Backend.NPU
            Mode.LOCAL -> if (localReady) Backend.NPU else Backend.CLOUD
        }
        return buildList {
            add(preferred)
            add(if (preferred == Backend.NPU) Backend.CLOUD else Backend.NPU)
        }.filter { legReady(it) }
    }

    /**
     * One stateless task turn (OCR, reconciliation, remark drafting): the
     * selected engine first, the spare leg only if that fails.
     */
    suspend fun run(
        systemPrompt: String,
        userPrompt: String,
        imagePaths: List<String> = emptyList(),
        maxTokens: Int = 640,
        temperature: Float = 0.2f,
        label: String? = null,
        onToken: (String) -> Unit = {},
        onLegSwitch: () -> Unit = {},
    ): Result<String> {
        stopRequested = false
        val order = legOrder()
        if (order.isEmpty()) {
            return Result.failure(IllegalStateException("No AI backend available"))
        }
        var lastFailure: Result<String>? = null
        var emitted = false
        for ((index, leg) in order.withIndex()) {
            val result = when (leg) {
                Backend.NPU -> NpuEngine.run(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    imagePaths = imagePaths,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    label = label,
                    onToken = { token ->
                        emitted = true
                        onToken(token)
                    },
                )
                Backend.CLOUD -> OpenRouterClient.chat(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    imagePaths = imagePaths,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    onToken = { token ->
                        emitted = true
                        onToken(token)
                    },
                )
            }
            if (result.isSuccess) {
                record(leg)
                return result
            }
            lastFailure = result
            logFailover(leg, isLastLeg = index == order.lastIndex, cause = result.exceptionOrNull())
            // A Stop the user pressed must not turn into a fresh paid
            // generation on the other leg.
            if (stopRequested) return Result.failure(IOException("Generation stopped"))
            // The dead leg may have fed the caller's accumulator already; the
            // next leg's stream must start from a clean slate.
            if (emitted) {
                onLegSwitch()
                emitted = false
            }
        }
        return requireNotNull(lastFailure)
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
        onLegSwitch: () -> Unit = {},
    ): Result<String> {
        stopRequested = false
        val order = legOrder()
        if (order.isEmpty()) {
            return Result.failure(IllegalStateException("No AI backend available"))
        }
        var lastFailure: Result<String>? = null
        var emitted = false
        for ((index, leg) in order.withIndex()) {
            val result = when (leg) {
                Backend.NPU -> NpuEngine.converse(
                    turns = turns,
                    mediaTurn = mediaTurn,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    onToken = { token ->
                        emitted = true
                        onToken(token)
                    },
                )
                Backend.CLOUD -> OpenRouterClient.converse(
                    turns = turns,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    onToken = { token ->
                        emitted = true
                        onToken(token)
                    },
                )
            }
            if (result.isSuccess) {
                record(leg)
                return result
            }
            lastFailure = result
            logFailover(leg, isLastLeg = index == order.lastIndex, cause = result.exceptionOrNull())
            if (stopRequested) return Result.failure(IOException("Generation stopped"))
            if (emitted) {
                onLegSwitch()
                emitted = false
            }
        }
        return requireNotNull(lastFailure)
    }

    /** Stops the in-flight generation on whichever backend is streaming. */
    fun stop() {
        stopRequested = true
        NpuEngine.stop()
        OpenRouterClient.cancel()
    }

    /**
     * Set by [stop] and cleared at the start of the next call, so a stopped
     * leg is reported as stopped rather than silently re-served by the spare.
     */
    @Volatile
    private var stopRequested = false

    private fun logFailover(leg: Backend, isLastLeg: Boolean, cause: Throwable?) {
        if (isLastLeg) {
            Log.w(TAG, "${legName(leg)} failed — no spare leg left: ${cause?.message ?: "unknown error"}")
            return
        }
        val to = if (leg == Backend.NPU) OpenRouterClient.DISPLAY_NAME else "${NpuEngine.DISPLAY_NAME} (NPU)"
        Log.w(TAG, "${legName(leg)} failed — failing over to $to: ${cause?.message ?: "unknown error"}")
    }

    private fun legName(leg: Backend): String = when (leg) {
        Backend.NPU -> "Local (NPU) leg"
        Backend.CLOUD -> "Cloud (${OpenRouterClient.DISPLAY_NAME}) leg"
    }

    private fun record(leg: Backend) {
        when (leg) {
            Backend.NPU -> recordNpu()
            Backend.CLOUD -> recordCloud()
        }
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
