package com.veritransit.inspector.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM-side checks for the context-window sizing — the exact parse path
 * [NpuEngine.refreshBundleContext] takes on device, minus the NPU and the
 * filesystem. Runs without a device or the model bundle.
 */
class BundleContextTest {

    private fun configWith(size: String) =
        """{"dialog": {"context": {"size": $size, "n-vocab": 151936}}}"""

    @Test
    fun `bundle declaration is adopted verbatim`() {
        assertEquals(2048, BundleContext.parseContextSize(configWith("2048")))
    }

    @Test
    fun `a larger future bundle is used to the full`() {
        assertEquals(4096, BundleContext.parseContextSize(configWith("4096")))
    }

    @Test
    fun `missing context block falls back`() {
        assertEquals(
            BundleContext.DEFAULT_CONTEXT_TOKENS,
            BundleContext.parseContextSize("""{"dialog": {}}"""),
        )
    }

    @Test
    fun `malformed json falls back`() {
        assertEquals(
            BundleContext.DEFAULT_CONTEXT_TOKENS,
            BundleContext.parseContextSize("not json at all {{{"),
        )
    }

    @Test
    fun `absurd sizes fall back instead of becoming budgets`() {
        assertEquals(BundleContext.DEFAULT_CONTEXT_TOKENS, BundleContext.parseContextSize(configWith("0")))
        assertEquals(BundleContext.DEFAULT_CONTEXT_TOKENS, BundleContext.parseContextSize(configWith("-2048")))
        assertEquals(BundleContext.DEFAULT_CONTEXT_TOKENS, BundleContext.parseContextSize(configWith("1048576")))
        assertEquals(BundleContext.DEFAULT_CONTEXT_TOKENS, BundleContext.parseContextSize(configWith("\"2048\"")))
        assertEquals(BundleContext.DEFAULT_CONTEXT_TOKENS, BundleContext.parseContextSize(configWith("20.48")))
    }

    @Test
    fun `explicit fallback is honoured when the bundle cannot be read`() {
        assertEquals(4096, BundleContext.parseContextSize("garbage", fallback = 4096))
    }

    @Test
    fun `prompt budget reserves the reply and the margin`() {
        // Today's bundle: 2048 - 512 reply - 128 margin = 1408, the chat
        // history budget the app has always run.
        assertEquals(1408, BundleContext.promptBudget(2048, 512))
        // A future 4096 bundle scales the budget up with it, not by a flag day.
        assertEquals(3456, BundleContext.promptBudget(4096, 512))
    }

    @Test
    fun `prompt budget never collapses to zero`() {
        assertEquals(BundleContext.MIN_PROMPT_BUDGET, BundleContext.promptBudget(512, 512))
    }
}
