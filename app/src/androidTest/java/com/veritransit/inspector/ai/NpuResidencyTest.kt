package com.veritransit.inspector.ai

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veritransit.inspector.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The residency lifecycle on real hardware (issue #13): the session must be
 * releasable and the next AI call must reload it transparently — no restart,
 * no detour through the NPU screen.
 *
 * Needs a Snapdragon device with the bundle already pulled; skips itself
 * otherwise. Run with `am instrument`, never `connectedDebugAndroidTest`
 * (that deletes the bundle — see NpuInferenceTest).
 */
@RunWith(AndroidJUnit4::class)
class NpuResidencyTest {

    @Test
    fun `release then auto-reload completes without a restart`() = runBlocking<Unit> {
        assumeTrue("model bundle not on disk", NpuEngine.status == NpuEngine.Status.READY ||
            NpuEngine.status == NpuEngine.Status.DOWNLOADED)

        // Bring the session down on purpose, whichever state we arrived in.
        if (NpuEngine.status == NpuEngine.Status.READY) NpuEngine.unload()
        awaitStatus(30_000) { it == NpuEngine.Status.DOWNLOADED }
        Log.i(TAG, "released; status=${NpuEngine.status}")

        // The next AI call must reload on demand and actually answer.
        val reply = NpuEngine.run(
            systemPrompt = "You are a test harness.",
            userPrompt = "Reply with the single word OK and nothing else.",
            maxTokens = 8,
            temperature = 0.1f,
            label = "Residency reload",
        ).getOrThrow()
        Log.i(TAG, "post-reload reply: $reply")
        assertTrue("reload produced no answer: '$reply'", reply.isNotBlank())

        // The engine must be back to a healthy resident state afterwards.
        awaitStatus(10_000) { it == NpuEngine.Status.READY }
        assertEquals(NpuEngine.Status.READY, NpuEngine.status)
    }

    private companion object {
        const val TAG = "NpuResidencyTest"

        private var scenario: ActivityScenario<MainActivity>? = null

        @JvmStatic
        @BeforeClass
        fun bringUpEngine() {
            // Same top-app-cgroup requirement as NpuInferenceTest: a visible
            // activity is what lets the cDSP grant the context allocations.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            runBlocking {
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                NpuEngine.initialize(ctx)
                awaitStatus(120_000) { it != NpuEngine.Status.COLD && it != NpuEngine.Status.INITIALIZING }
                if (NpuEngine.status == NpuEngine.Status.DOWNLOADED) {
                    NpuEngine.load()
                    awaitStatus(240_000) { it == NpuEngine.Status.READY || it == NpuEngine.Status.ERROR }
                }
                Log.i(TAG, "engine status after load: ${NpuEngine.status} err=${NpuEngine.lastError}")
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            scenario?.close()
            scenario = null
        }

        private suspend fun awaitStatus(timeoutMs: Long, done: (NpuEngine.Status) -> Boolean) {
            withTimeoutOrNull(timeoutMs) {
                while (!done(NpuEngine.status)) delay(250)
            }
        }
    }
}
