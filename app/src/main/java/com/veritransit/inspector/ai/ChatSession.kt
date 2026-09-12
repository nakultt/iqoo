package com.veritransit.inspector.ai

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent

/** One line of the transcript. */
data class ChatTurn(
    val role: Role,
    val text: String,
    /** Cropped frame sent with this turn, if the user attached one. */
    val imagePath: String? = null,
    /** Timings, set on an assistant turn once it completes. */
    val stats: String? = null,
) {
    enum class Role { USER, ASSISTANT }
}

/**
 * A free-form conversation with the model, kept inside the on-device bundle's
 * context window.
 *
 * The whole transcript is re-sent on every turn, so it has to be trimmed here —
 * there is no server-side session to lean on, and overflowing the window does
 * not degrade gracefully, it fails the generate call. The trim budget tracks
 * [NpuEngine.effectiveContextTokens], and sliding-window attention stays armed
 * underneath as a second net. The cloud fallback could take a much longer
 * transcript, but the trim is deliberately kept: it bounds what a fallback
 * turn can leak off the handset in one shot, and it keeps estimator drift
 * corrections meaningful across both backends.
 */
@Stable
class ChatSession {

    val turns = mutableStateListOf<ChatTurn>()

    var streaming by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Partial reply while tokens are arriving; null when idle. */
    var pending by mutableStateOf<String?>(null)
        private set

    fun clear() {
        turns.clear()
        pending = null
        error = null
        // The anchors described the old transcript; keep nothing of it.
        measuredPromptTokens = 0L
        predictedPromptTokens = 0L
    }

    /**
     * Sends [text] (optionally with [imagePath]) and streams the reply into
     * [pending], committing it to [turns] when the model stops.
     */
    suspend fun send(text: String, imagePath: String? = null) {
        if (streaming) return
        val userTurn = ChatTurn(ChatTurn.Role.USER, text, imagePath)
        turns.add(userTurn)
        streaming = true
        pending = ""
        error = null

        val builder = StringBuilder()
        try {
            val history = buildHistory()
            // The image has to be handed over on the turn that carries it, not
            // on the last message: on a text-only follow-up the newest image
            // sits further back in the transcript, and pointing the encoder at
            // the last turn would drop it (and desync the template's image
            // markers against the bitmap that never arrived).
            val mediaTurn = history.lastOrNull { msg -> msg.contents.any { it.type == "image" } }
                ?: history.last()
            // What the estimator thinks this prompt costs. Held locally until
            // the exchange completes: committing it on a failed send would
            // pair this prediction with the measurement of an earlier
            // exchange, and the resulting bogus drift term compounds with
            // every retry — the overflow the budget exists to prevent.
            val predicted = history.sumOf { msg ->
                msg.contents.sumOf { content ->
                    if (content.type == "image") NpuEngine.VISION_TOKENS.toLong()
                    else estimateTokens(content.text.orEmpty()).toLong()
                }
            }
            LlmGateway.converse(
                turns = history,
                mediaTurn = mediaTurn,
                maxTokens = REPLY_TOKENS,
                onToken = { token ->
                    builder.append(token)
                    pending = builder.toString()
                },
                // If the NPU leg died mid-stream and the cloud leg took over,
                // the dead leg's tokens must not stay in the transcript view.
                onLegSwitch = {
                    builder.setLength(0)
                    pending = ""
                },
            ).onSuccess { reply ->
                // The trim budget is sized for the bundle's window, so only an
                // NPU measurement may steer it: the fallback's tokenizer counts
                // the same transcript differently, and image tokens are
                // accounted per the provider, not the bundle's flat 256. Cloud
                // usage is display-only (it does feed lastStats).
                if (LlmGateway.lastBackend == LlmGateway.Backend.NPU) {
                    val measured = LlmGateway.lastPromptTokens
                    if (measured > 0) {
                        // Both anchors commit together, so the drift term is
                        // always measured-vs-predicted for one and the same
                        // exchange.
                        measuredPromptTokens = measured
                        predictedPromptTokens = predicted
                        Log.i(
                            TAG,
                            "prompt measured at $measuredPromptTokens tokens " +
                                "(estimated $predictedPromptTokens, drift ${measuredPromptTokens - predictedPromptTokens})",
                        )
                    }
                }
                turns.add(
                    ChatTurn(
                        role = ChatTurn.Role.ASSISTANT,
                        text = reply.trim().ifEmpty { "(no reply)" },
                        stats = LlmGateway.lastStats,
                    ),
                )
            }.onFailure {
                error = it.message ?: "Generation failed"
                // The receiver's message stays in the transcript: deleting it
                // turned a failed send into a lost message with no retry.
            }
        } finally {
            streaming = false
            pending = null
        }
    }

    /**
     * Builds the message list for the runtime, oldest turns dropped until the
     * estimate fits. Images are attached only to the most recent turn that
     * carried one: each costs a flat [NpuEngine.VISION_TOKENS] and replaying
     * older ones would desync the encoder's markers besides.
     *
     * The budget is anchored on the runtime, not the estimator: the SDK ships
     * no pre-send tokenizer (verified against geniex-android 0.4.0), but every
     * completed generation reports the prompt's true token count, and the gap
     * between that and our estimate is reapplied here. Drift from the
     * chars-per-token guess therefore never accumulates across a conversation
     * — each measurement re-anchors it before the next send.
     */
    private fun buildHistory(): List<VlmChatMessage> {
        val system = VlmChatMessage("system", listOf(VlmContent("text", SYSTEM_PROMPT)))
        val kept = ArrayDeque<ChatTurn>()
        var budget = contextBudget() - estimatorDrift()

        // Walk backwards so the newest turns are the ones that survive.
        for (turn in turns.reversed()) {
            val cost = estimateTokens(turn.text) +
                if (turn.imagePath != null && kept.isEmpty()) NpuEngine.VISION_TOKENS else 0
            if (budget - cost < 0 && kept.isNotEmpty()) break
            budget -= cost
            kept.addFirst(turn)
        }

        val newestWithImage = kept.lastOrNull { it.imagePath != null }
        return buildList {
            add(system)
            kept.forEach { turn ->
                val contents = buildList {
                    if (turn === newestWithImage && turn.imagePath != null) {
                        add(VlmContent("image", turn.imagePath))
                    }
                    add(VlmContent("text", turn.text))
                }
                add(VlmChatMessage(if (turn.role == ChatTurn.Role.USER) "user" else "assistant", contents))
            }
        }
    }

    /**
     * How far the estimator was off on the last measured exchange: positive
     * means prompts really cost more than estimated, so the budget shrinks.
     * Zero until the first generation completes.
     */
    private fun estimatorDrift(): Int {
        if (measuredPromptTokens <= 0L) return 0
        val cap = contextBudget().toLong()
        return (measuredPromptTokens - predictedPromptTokens).coerceIn(-cap, cap).toInt()
    }

    private companion object {
        const val TAG = "ChatSession"

        const val SYSTEM_PROMPT =
            "You are a helpful assistant for a warehouse receiving clerk, running " +
                "on this phone's Snapdragon NPU — or, when the on-device model is " +
                "unavailable, on a cloud model reached through OpenRouter. Answer " +
                "briefly and directly. When shown an image, describe only what is " +
                "actually in it."

        /** Tokens of the window left for the prompt, so a reply always fits. */
        fun contextBudget(): Int =
            BundleContext.promptBudget(NpuEngine.effectiveContextTokens, REPLY_TOKENS)

        const val REPLY_TOKENS = 512

        /**
         * Fallback average for turns the runtime has not measured yet; the
         * drift correction keeps the running total honest from the first
         * completed exchange onward. Deliberately pessimistic so the trim errs
         * toward dropping history rather than overflowing the context.
         */
        fun estimateTokens(text: String) = (text.length / 3) + 8
    }

    /** Prompt size the runtime reported for the last completed exchange. */
    private var measuredPromptTokens = 0L

    /** What the estimator predicted for that same prompt. */
    private var predictedPromptTokens = 0L
}
