package com.veritransit.inspector.ai

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
 * A free-form conversation with the on-device model, kept inside the bundle's
 * fixed context window.
 *
 * The whole transcript is re-sent on every turn, so it has to be trimmed here —
 * there is no server-side session to lean on, and overflowing a 2048-token
 * context does not degrade gracefully, it fails the generate call.
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
            val mediaTurn = history.last()
            NpuEngine.converse(
                turns = history,
                mediaTurn = mediaTurn,
                maxTokens = REPLY_TOKENS,
                onToken = { token ->
                    builder.append(token)
                    pending = builder.toString()
                },
            ).onSuccess { reply ->
                val profile = NpuEngine.lastProfile
                turns.add(
                    ChatTurn(
                        role = ChatTurn.Role.ASSISTANT,
                        text = reply.trim().ifEmpty { "(no reply)" },
                        stats = profile?.let {
                            "%.0f tok/s · %d tokens".format(it.decodingSpeed, it.generatedTokens)
                        },
                    ),
                )
            }.onFailure {
                error = it.message ?: "Generation failed"
                turns.removeAt(turns.lastIndex)
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
     */
    private fun buildHistory(): List<VlmChatMessage> {
        val system = VlmChatMessage("system", listOf(VlmContent("text", SYSTEM_PROMPT)))
        val kept = ArrayDeque<ChatTurn>()
        var budget = CONTEXT_BUDGET - estimateTokens(SYSTEM_PROMPT)

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

    private companion object {
        const val SYSTEM_PROMPT =
            "You are a helpful assistant running entirely on this phone's " +
                "Snapdragon NPU. Answer briefly and directly. When shown an " +
                "image, describe only what is actually in it."

        /** Tokens of the window left for the prompt, so a reply always fits. */
        val CONTEXT_BUDGET = NpuEngine.CONTEXT_TOKENS - REPLY_TOKENS - 128

        const val REPLY_TOKENS = 512

        /** Rough English average; deliberately pessimistic so the trim is safe. */
        fun estimateTokens(text: String) = (text.length / 3) + 8
    }
}
