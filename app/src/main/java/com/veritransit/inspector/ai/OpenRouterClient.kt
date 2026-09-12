package com.veritransit.inspector.ai

import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.veritransit.inspector.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Cloud fallback backend: `z-ai/glm-5.3-flash` on OpenRouter.
 *
 * This is the leg [LlmGateway] hands a task to when the on-device NPU model is
 * not resident, or its generation failed. It is deliberately a single fixed
 * model — no per-task routing, no second provider — so what the officer sees
 * stays predictable and cost is auditable in one OpenRouter dashboard.
 *
 * GLM-5.3-Flash is a reasoning model and OpenRouter refuses to switch thinking
 * off for it (HTTP 400 "Reasoning is mandatory for this endpoint"), so
 * reasoning is *excluded* from the streamed content instead and capped at
 * [REASONING_TOKENS]; the same headroom is added to the completion budget so
 * thinking cannot starve the actual reply into a truncated empty string.
 *
 * Replies stream over SSE, mirroring [NpuEngine]'s token callback, so callers
 * need no special-casing: an officer watches the note appear either way.
 */
object OpenRouterClient {

    /**
     * Injected at build time from the gitignored `local.properties`
     * (`openrouter.api.key=…`) — the same convention the telegram-bot module
     * uses for its secrets. GitHub push protection blocks commits carrying
     * API keys, so the key is baked into the APK on the owner's machine
     * rather than stored in source control; [isConfigured] gates the cloud
     * leg when it is absent.
     */
    val API_KEY: String = BuildConfig.OPENROUTER_API_KEY.trim()

    /** False when no key was injected at build time — the cloud leg is off. */
    val isConfigured: Boolean get() = API_KEY.isNotEmpty()

    const val MODEL = "z-ai/glm-5.3-flash"
    const val DISPLAY_NAME = "GLM-5.3-Flash"

    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    /**
     * Headroom for thinking tokens, on top of the caller's reply budget, and
     * the cap the reasoning parameter asks the provider to enforce.
     */
    internal const val REASONING_TOKENS = 512

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000

    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    // ------------------------------------------------------------------ API

    /**
     * One stateless task turn — the cloud twin of [NpuEngine.run]. Photos are
     * inlined as base64 data URLs; evidence frames are 512 px JPEGs (~100 KB),
     * small enough not to warrant a file-upload round trip.
     */
    suspend fun chat(
        systemPrompt: String,
        userPrompt: String,
        imagePaths: List<String> = emptyList(),
        maxTokens: Int = 640,
        temperature: Float = 0.2f,
        onToken: (String) -> Unit = {},
    ): Result<String> {
        val messages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(RequestMessage("system", listOf(ContentPart.text(systemPrompt))))
            }
            add(
                RequestMessage(
                    role = "user",
                    content = buildList {
                        imagePaths.forEach { add(ContentPart.imageDataUrl(imageDataUrl(it))) }
                        add(ContentPart.text(userPrompt))
                    },
                ),
            )
        }
        return send(messages, maxTokens, temperature, onToken)
    }

    /**
     * Multi-turn conversation — the cloud twin of [NpuEngine.converse]. The
     * local path carries at most one image (the newest turn that has one),
     * because the NPU encoder desyncs otherwise; the API is stateless, so
     * every image in [turns] is simply forwarded.
     */
    suspend fun converse(
        turns: List<VlmChatMessage>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit = {},
    ): Result<String> = send(mapTurns(turns), maxTokens, temperature, onToken)

    /**
     * Aborts the in-flight stream. A partial reply is kept and returned as
     * success — the same semantics as stopping a local generation mid-stream.
     */
    fun cancel() {
        aborted = true
        runCatching { connection?.disconnect() }
    }

    /** Usage the most recent completed call reported (0 before the first). */
    @Volatile
    var lastPromptTokens: Long = 0L
        internal set

    @Volatile
    var lastCompletionTokens: Long = 0L
        internal set

    // -------------------------------------------------------------- plumbing

    /** One cloud call at a time: [cancel] targets the single live connection. */
    private val callMutex = Mutex()

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var aborted = false

    private suspend fun send(
        messages: List<RequestMessage>,
        maxTokens: Int,
        temperature: Float,
        onToken: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            callMutex.withLock { stream(messages, maxTokens, temperature, onToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun stream(
        messages: List<RequestMessage>,
        maxTokens: Int,
        temperature: Float,
        onToken: (String) -> Unit,
    ): Result<String> {
        if (!isConfigured) {
            return Result.failure(IllegalStateException("No OpenRouter API key configured"))
        }
        aborted = false
        val request = buildRequest(messages, maxTokens, temperature)

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Title", "VeriTransit")
            conn.outputStream.use { it.write(json.encodeToString(request).toByteArray()) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                val detail = errorMessage(body).ifEmpty { "request failed" }
                return Result.failure(IOException("OpenRouter HTTP $code: $detail"))
            }

            val reply = ReplyAccumulator()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    coroutineContext.ensureActive()
                    if (aborted) break
                    reply.feedLine(line)
                }
            }

            reply.errorMessage?.let {
                return Result.failure(IOException("OpenRouter: $it"))
            }
            lastPromptTokens = reply.usage?.promptTokens?.toLong() ?: 0L
            lastCompletionTokens = reply.usage?.completionTokens?.toLong() ?: 0L

            val text = reply.content
            when {
                text.isNotBlank() -> return Result.success(text)
                // Stopped with nothing on the wire yet: report it, so the caller
                // does not commit an empty reply as if the model had spoken.
                aborted -> return Result.failure(IOException("Generation stopped"))
                reply.finishReason == "length" -> return Result.failure(
                    IOException(
                        "OpenRouter: reply was empty — the model's reasoning " +
                            "exhausted the completion budget",
                    ),
                )
                else -> return Result.failure(IOException("OpenRouter: empty reply from model"))
            }
        } finally {
            connection = null
            runCatching { conn.disconnect() }
        }
    }

    /**
     * The wire request: reasoning is excluded and capped at
     * [REASONING_TOKENS] (this model refuses to disable thinking outright),
     * and the completion budget is the caller's reply budget plus that same
     * headroom, so thinking cannot starve the visible answer.
     */
    internal fun buildRequest(
        messages: List<RequestMessage>,
        maxTokens: Int,
        temperature: Float,
    ): ChatRequest = ChatRequest(
        model = MODEL,
        messages = messages,
        maxTokens = maxTokens + REASONING_TOKENS,
        temperature = temperature.toDouble(),
        stream = true,
        streamOptions = StreamOptions(includeUsage = true),
        reasoning = ReasoningConfig(exclude = true, maxTokens = REASONING_TOKENS),
    )

    /** Pulls `error.message` out of an OpenRouter JSON error body, if it is one. */
    private fun errorMessage(body: String): String =
        runCatching { json.decodeFromString<ErrorBody>(body).error?.message }.getOrNull().orEmpty()

    // ------------------------------------------------------- SSE accumulation

    /**
     * Folds the SSE stream into the reply text. Kept dumb and side-effect free
     * on purpose so the whole protocol shape is unit-testable: keep-alive
     * comment lines (`: OPENROUTER PROCESSING`), the `[DONE]` sentinel,
     * `delta.reasoning` (dropped — the request already asks for exclusion, but
     * a provider that ignores it must not leak thinking into an officer's
     * record), the final usage chunk, and an `error` object carried mid-stream.
     */
    internal class ReplyAccumulator {

        var content: String = ""
            private set

        var usage: Usage? = null
            private set

        var finishReason: String? = null
            private set

        var errorMessage: String? = null
            private set

        private val chunkJson = Json { ignoreUnknownKeys = true }

        fun feedLine(line: String) {
            val trimmed = line.trim()
            // SSE comments are OpenRouter's keep-alives, not data.
            if (trimmed.isEmpty() || trimmed.startsWith(":")) return
            if (!trimmed.startsWith("data:")) return
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") return

            val chunk = runCatching { chunkJson.decodeFromString<StreamChunk>(payload) }
                .getOrNull() ?: return
            chunk.error?.let { error ->
                // First error wins; keep draining but do not overwrite it.
                if (errorMessage == null) errorMessage = error.message ?: "stream error"
                return
            }
            chunk.choices.firstOrNull()?.let { choice ->
                choice.delta.content?.let { content += it }
                choice.finishReason?.let { finishReason = it }
            }
            chunk.usage?.let { usage = it }
        }
    }

    // ---------------------------------------------------------------- mapping

    /** Maps the app's chat turns to OpenRouter messages; images become data URLs. */
    internal fun mapTurns(
        turns: List<VlmChatMessage>,
        readBytes: (String) -> ByteArray = { File(it).readBytes() },
    ): List<RequestMessage> = turns.map { turn ->
        RequestMessage(
            role = turn.role ?: "user",
            content = turn.contents.mapNotNull { content ->
                when (content.type) {
                    "image" -> content.text?.let { ContentPart.imageDataUrl(imageDataUrl(it, readBytes)) }
                    else -> content.text?.let { ContentPart.text(it) }
                }
            },
        )
    }

    /** `data:<mime>;base64,…` for one evidence frame. */
    internal fun imageDataUrl(
        path: String,
        readBytes: (String) -> ByteArray = { File(it).readBytes() },
    ): String = imageDataUrl(readBytes(path), mimeFor(path))

    internal fun imageDataUrl(bytes: ByteArray, mime: String): String =
        "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    /** The camera writes JPEGs; the others are honoured in case that ever changes. */
    internal fun mimeFor(path: String): String = when (File(path).extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    // ------------------------------------------------------------------- DTOs

    @Serializable
    internal data class ChatRequest(
        val model: String,
        val messages: List<RequestMessage>,
        @SerialName("max_tokens") val maxTokens: Int,
        val temperature: Double,
        val stream: Boolean,
        @SerialName("stream_options") val streamOptions: StreamOptions,
        val reasoning: ReasoningConfig,
    )

    @Serializable
    internal data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean)

    @Serializable
    internal data class ReasoningConfig(
        val exclude: Boolean,
        @SerialName("max_tokens") val maxTokens: Int,
    )

    @Serializable
    internal data class RequestMessage(val role: String, val content: List<ContentPart>)

    @Serializable
    internal data class ContentPart(
        val type: String,
        val text: String? = null,
        @SerialName("image_url") val imageUrl: ImageUrl? = null,
    ) {
        companion object {
            fun text(text: String) = ContentPart(type = "text", text = text)
            fun imageDataUrl(url: String) = ContentPart(type = "image_url", imageUrl = ImageUrl(url))
        }
    }

    @Serializable
    internal data class ImageUrl(val url: String)

    @Serializable
    internal data class StreamChunk(
        val choices: List<ChoiceChunk> = emptyList(),
        val usage: Usage? = null,
        val error: ApiError? = null,
    )

    @Serializable
    internal data class ChoiceChunk(
        val delta: Delta = Delta(),
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    internal data class Delta(val content: String? = null, val reasoning: String? = null)

    @Serializable
    internal data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
    )

    @Serializable
    internal data class ApiError(val message: String? = null, val code: JsonElement? = null)

    @Serializable
    private data class ErrorBody(val error: ApiError? = null)
}
