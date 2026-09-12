package com.veritransit.inspector.ai

import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM-side checks for the OpenRouter fallback's protocol shape — the request
 * the cloud leg sends, the turn mapping, and the SSE folding that turns the
 * stream into a reply. These run without a device or a network, so a protocol
 * regression fails CI instead of a receiver's count.
 */
class OpenRouterClientTest {

    // ------------------------------------------------------- request shape

    @Test
    fun `request targets glm 5_3 flash with reasoning excluded and budgeted`() {
        val request = OpenRouterClient.buildRequest(
            messages = listOf(OpenRouterClient.RequestMessage("user", listOf(OpenRouterClient.ContentPart.text("hi")))),
            maxTokens = 448,
            temperature = 0.1f,
        )

        assertEquals(OpenRouterClient.MODEL, request.model)
        assertTrue(request.stream)
        assertTrue(request.streamOptions.includeUsage)
        // The reply budget plus the reasoning headroom, so thinking cannot
        // starve the visible answer into an empty string.
        assertEquals(448 + OpenRouterClient.REASONING_TOKENS, request.maxTokens)
        assertTrue(request.reasoning.exclude)
        assertEquals(OpenRouterClient.REASONING_TOKENS, request.reasoning.maxTokens)
        assertEquals(0.1, request.temperature, absoluteTolerance = 1e-6)
    }

    @Test
    fun `encoded request carries image parts and omits null text fields`() {
        val request = OpenRouterClient.buildRequest(
            messages = listOf(
                OpenRouterClient.RequestMessage(
                    "user",
                    listOf(
                        OpenRouterClient.ContentPart.imageDataUrl("data:image/jpeg;base64,QUJD"),
                        OpenRouterClient.ContentPart.text("what is in this photo?"),
                    ),
                ),
            ),
            maxTokens = 512,
            temperature = 0.7f,
        )
        val encoded = OpenRouterClient.json.encodeToString(
            OpenRouterClient.ChatRequest.serializer(),
            request,
        )

        assertTrue(encoded.contains("\"model\":\"z-ai/glm-5.3-flash\""))
        assertTrue(encoded.contains("\"image_url\":{\"url\":\"data:image/jpeg;base64,QUJD\"}"))
        assertTrue(encoded.contains("\"text\":\"what is in this photo?\""))
        // explicitNulls = false: a text part must not carry a null image_url
        // and an image part must not carry a null text — strict providers 400.
        assertFalse(encoded.contains("null"))
        assertFalse(encoded.contains("\"type\":\"image_url\",\"text\":"))
    }

    // --------------------------------------------------------- turn mapping

    @Test
    fun `turns map roles and inline images as data urls`() {
        val turns = listOf(
            VlmChatMessage("system", listOf(VlmContent("text", "be brief"))),
            VlmChatMessage("user", listOf(VlmContent("image", "/frames/bay.png"), VlmContent("text", "count these"))),
        )

        val mapped = OpenRouterClient.mapTurns(turns, readBytes = { "PNGBYTES".toByteArray() })

        assertEquals(2, mapped.size)
        assertEquals("system", mapped[0].role)
        assertEquals(listOf(OpenRouterClient.ContentPart.text("be brief")), mapped[0].content)

        assertEquals("user", mapped[1].role)
        assertEquals(2, mapped[1].content.size)
        val image = mapped[1].content[0]
        assertEquals("image_url", image.type)
        // PNG extension must be honoured in the data URL's mime type.
        assertTrue(image.imageUrl!!.url.startsWith("data:image/png;base64,"))
        assertEquals("text", mapped[1].content[1].type)
        assertEquals("count these", mapped[1].content[1].text)
    }

    @Test
    fun `evidence frames default to jpeg mime and base64 cleanly`() {
        val url = OpenRouterClient.imageDataUrl("/evidence/bill.jpg") { "abc".toByteArray() }

        // "abc" → base64 YWJj
        assertEquals("data:image/jpeg;base64,YWJj", url)
        assertEquals("image/png", OpenRouterClient.mimeFor("/evidence/shot.PNG"))
        assertEquals("image/jpeg", OpenRouterClient.mimeFor("/evidence/shot"))
    }

    // -------------------------------------------------------- SSE folding

    private fun chunk(content: String? = null, reasoning: String? = null, finish: String? = null) =
        "data: " + """{"choices":[{"delta":{"content":${jsonStr(content)},"reasoning":${jsonStr(reasoning)}},"finish_reason":${jsonStr(finish)}}]}"""

    private fun jsonStr(s: String?) = if (s == null) "null" else "\"$s\""

    @Test
    fun `content deltas accumulate and reasoning never reaches the reply`() {
        val acc = OpenRouterClient.ReplyAccumulator()
        acc.feedLine("""data: {"choices":[{"delta":{"reasoning":"thinking about the bay"}}]}""")
        acc.feedLine(chunk(content = "One pallet"))
        acc.feedLine(chunk(content = " of cartons,"))
        acc.feedLine(chunk(content = " 3 visible."))

        assertEquals("One pallet of cartons, 3 visible.", acc.content)
        assertNull(acc.errorMessage)
        assertNull(acc.usage)
    }

    @Test
    fun `final usage chunk is captured for the budget anchor`() {
        val acc = OpenRouterClient.ReplyAccumulator()
        acc.feedLine(chunk(content = "ok"))
        acc.feedLine(
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":19,"completion_tokens":15}}""",
        )

        assertEquals(19, acc.usage?.promptTokens)
        assertEquals(15, acc.usage?.completionTokens)
        assertEquals("stop", acc.finishReason)
    }

    @Test
    fun `keep alive comments, done sentinel and junk lines are ignored`() {
        val acc = OpenRouterClient.ReplyAccumulator()
        acc.feedLine(": OPENROUTER PROCESSING")
        acc.feedLine("")
        acc.feedLine("data: [DONE]")
        acc.feedLine("data: not-json-at-all")
        acc.feedLine("event: ping")

        assertEquals("", acc.content)
        assertNull(acc.errorMessage)
        assertNull(acc.usage)
    }

    @Test
    fun `mid stream error is surfaced, not silently truncated`() {
        val acc = OpenRouterClient.ReplyAccumulator()
        acc.feedLine(chunk(content = "partial"))
        acc.feedLine("""data: {"error":{"message":"rate limited","code":429}}""")
        acc.feedLine(chunk(content = " more"))

        assertEquals("rate limited", acc.errorMessage)
    }

    @Test
    fun `length finish reason survives for the empty reply diagnosis`() {
        val acc = OpenRouterClient.ReplyAccumulator()
        acc.feedLine("""data: {"choices":[{"delta":{},"finish_reason":"length"}]}""")

        assertEquals("length", acc.finishReason)
        assertTrue(acc.content.isEmpty())
    }

    // --------------------------------------------- task message assembly

    @Test
    fun `task messages put the system turn first and images before the prompt`() {
        val messages = OpenRouterClient.buildTaskMessages(
            systemPrompt = "You read bills.",
            userPrompt = "Read it.",
            imagePaths = listOf("/evidence/bill.jpg"),
            readBytes = { "JPEGBYTES".toByteArray() },
        )

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals(listOf(OpenRouterClient.ContentPart.text("You read bills.")), messages[0].content)
        assertEquals("user", messages[1].role)
        assertEquals(2, messages[1].content.size)
        assertTrue(messages[1].content[0].imageUrl!!.url.startsWith("data:image/jpeg;base64,"))
        assertEquals("Read it.", messages[1].content[1].text)

        // A blank system prompt is omitted, not sent as an empty turn.
        val noSystem = OpenRouterClient.buildTaskMessages("", "hi", emptyList())
        assertEquals(1, noSystem.size)
        assertEquals("user", noSystem[0].role)
    }

    // --------------------------------------- HTTP paths via a local server

    @Test
    fun `http 402 becomes a readable credit failure`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(402).setBody("""{"error":{"message":"insufficient credits"}}"""),
        )
        val previousEndpoint = OpenRouterClient.endpoint
        val previousKey = OpenRouterClient.apiKey
        OpenRouterClient.endpoint = server.url("/v1/chat/completions").toString()
        OpenRouterClient.apiKey = "test-key"
        try {
            val result = runBlocking { OpenRouterClient.chat("sys", "read this") }
            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("out of credit"), message)
        } finally {
            OpenRouterClient.endpoint = previousEndpoint
            OpenRouterClient.apiKey = previousKey
            server.shutdown()
        }
    }

    @Test
    fun `aborting mid stream keeps the partial reply`() {
        val server = MockWebServer()
        server.start()
        // Plenty of chunks at a crawl, so the stream is still open when the
        // abort lands.
        val chunks = (1..200).joinToString("") {
            "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(8, 50, TimeUnit.MILLISECONDS)
                .setBody(chunks),
        )
        val previousEndpoint = OpenRouterClient.endpoint
        val previousKey = OpenRouterClient.apiKey
        OpenRouterClient.endpoint = server.url("/v1/chat/completions").toString()
        OpenRouterClient.apiKey = "test-key"
        try {
            // Abort on the first decoded token — deterministic, no sleeps.
            val result = runBlocking {
                async {
                    OpenRouterClient.chat("sys", "read this", onToken = { OpenRouterClient.cancel() })
                }.await()
            }
            // Stopping mirrors the NPU leg: whatever arrived stays a success.
            assertTrue(
                result.isSuccess,
                "expected success, got: ${result.exceptionOrNull()?.javaClass?.simpleName}: ${result.exceptionOrNull()?.message}",
            )
            assertTrue(!result.getOrNull().isNullOrEmpty(), "partial was empty")
        } finally {
            OpenRouterClient.endpoint = previousEndpoint
            OpenRouterClient.apiKey = previousKey
            server.shutdown()
        }
    }

    @Test
    fun `streamed tokens reach the caller as they decode`() {
        val server = MockWebServer()
        server.start()
        val body = """
            data: {"choices":[{"delta":{"content":"One pallet"}}]}

            data: {"choices":[{"delta":{"reasoning":"ignored"}}]}

            data: {"choices":[{"delta":{"content":" of cartons"}}]}

            data: [DONE]

        """.trimIndent()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body),
        )
        val previousEndpoint = OpenRouterClient.endpoint
        val previousKey = OpenRouterClient.apiKey
        OpenRouterClient.endpoint = server.url("/v1/chat/completions").toString()
        OpenRouterClient.apiKey = "test-key"
        try {
            val seen = mutableListOf<String>()
            val result = runBlocking { OpenRouterClient.chat("sys", "read this", onToken = { seen.add(it) }) }
            assertTrue(result.isSuccess)
            assertEquals("One pallet of cartons", result.getOrNull())
            assertEquals(listOf("One pallet", " of cartons"), seen)
        } finally {
            OpenRouterClient.endpoint = previousEndpoint
            OpenRouterClient.apiKey = previousKey
            server.shutdown()
        }
    }
}
