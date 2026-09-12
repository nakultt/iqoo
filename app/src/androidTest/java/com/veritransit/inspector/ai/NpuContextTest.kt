package com.veritransit.inspector.ai

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.veritransit.inspector.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Context window against the real bundle: the engine must adopt the window
 * the downloaded bundle declares, and the multi-turn path (which arms
 * sliding-window attention) must still generate.
 *
 * Needs the Qwen3-VL bundle on disk; skips elsewhere. Run with `am instrument`
 * (never connectedAndroidTest — that uninstalls the APK and the bundle with it).
 */
@RunWith(AndroidJUnit4::class)
class NpuContextTest {

    @Test
    fun bundleContextIsDetectedFromDownloadedBundle() {
        assumeTrue(
            "bundle not on disk",
            NpuEngine.status == NpuEngine.Status.DOWNLOADED ||
                NpuEngine.status == NpuEngine.Status.READY ||
                NpuEngine.status == NpuEngine.Status.BUSY,
        )
        assertTrue("context never read from the bundle", NpuEngine.contextFromBundle)
        assertEquals(
            "bundle declares a 2048-token window",
            2048,
            NpuEngine.bundleContextTokens,
        )
        assertEquals(2048, NpuEngine.effectiveContextTokens)
    }

    @Test
    fun chatConverseWithSlidingWindowReplies() = runBlocking<Unit> {
        // Residency does not survive across tests (an unload — idle watchdog,
        // backgrounding, or the previous test's teardown — lands in DOWNLOADED),
        // so bring the cached bundle back here rather than assuming test order.
        ensureForeground()
        if (NpuEngine.status == NpuEngine.Status.DOWNLOADED) {
            NpuEngine.load()
        }
        awaitStatus(240_000) {
            it == NpuEngine.Status.READY || it == NpuEngine.Status.ERROR
        }
        assumeTrue("model not resident: ${NpuEngine.status} err=${NpuEngine.lastError}", NpuEngine.isReady)
        val turns = listOf(
            VlmChatMessage("system", listOf(VlmContent("text", "Answer with one word only."))),
            VlmChatMessage("user", listOf(VlmContent("text", "Reply with the word READY."))),
        )
        val answer = NpuEngine.converse(
            turns = turns,
            mediaTurn = turns.last(),
            maxTokens = 8,
        ).getOrThrow()
        assertTrue("empty chat reply with sliding window armed", answer.isNotBlank())
    }

    companion object {
        private var scenario: ActivityScenario<MainActivity>? = null

        @JvmStatic
        @BeforeClass
        fun bringUpEngine() {
            // cDSP grants multi-GB allocations only to the top-app cgroup, so
            // the Activity must be resumed before a load is attempted.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            runBlocking {
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                NpuEngine.initialize(ctx)
                awaitStatus(120_000) { it != NpuEngine.Status.COLD && it != NpuEngine.Status.INITIALIZING }
                if (NpuEngine.status == NpuEngine.Status.DOWNLOADED) {
                    NpuEngine.load()
                    awaitStatus(240_000) { it == NpuEngine.Status.READY || it == NpuEngine.Status.ERROR }
                }
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            scenario?.close()
            scenario = null
        }

        /**
         * The suite's activity may be gone by the time a later test runs (a
         * finished activity stops being the top app, which also costs the cDSP
         * grant a load needs), so re-enter the foreground here.
         */
        private fun ensureForeground() {
            runCatching { scenario?.close() }
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }

        private suspend fun awaitStatus(timeoutMs: Long, done: (NpuEngine.Status) -> Boolean) {
            withTimeoutOrNull(timeoutMs) {
                while (!done(NpuEngine.status)) delay(500)
            }
        }
    }
}
