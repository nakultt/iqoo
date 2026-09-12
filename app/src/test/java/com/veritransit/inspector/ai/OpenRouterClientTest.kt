package com.veritransit.inspector.ai

import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM-side checks for the OpenRouter fallback's protocol shape — the request
 * the cloud leg sends, the turn mapping, and the SSE folding that turns the
 * stream into a reply. These run without a device or a network, so a protocol
 * regression fails CI instead of an officer's inspection.
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
}
