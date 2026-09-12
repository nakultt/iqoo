package com.veritransit.inspector.ai

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veritransit.inspector.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Residency lifecycle against the real NPU: an unload must be observable, and
 * inference must bring the cached bundle back without a process restart —
 * the recovery path the screens depend on once residency is released
 * automatically (idle timeout, backgrounding).
 *
 * Needs the Qwen3-VL bundle on disk; skips elsewhere. Run with `am instrument`
 * (never connectedAndroidTest — that uninstalls the APK and the bundle with it).
 */
@RunWith(AndroidJUnit4::class)
class NpuResidencyTest {

    @Test
    fun unloadsThenReloadsOnDemandWithoutRestart() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        // Release the session and observe it let go of the NPU.
        NpuEngine.unload()
        awaitStatus(30_000) { it == NpuEngine.Status.DOWNLOADED }
        Log.i(TAG, "unloaded; status=${NpuEngine.status}")

        // The very next inference call must reload the cached bundle on its own.
        val answer = NpuEngine.run(
            systemPrompt = "Answer with one word only.",
            userPrompt = "Reply with the word READY.",
            maxTokens = 8,
            label = "residency probe",
        ).getOrThrow()
        Log.i(TAG, "on-demand answer after unload: '$answer' profile=${NpuEngine.lastProfile}")
        assertTrue("empty answer after auto-reload", answer.isNotBlank())
        assertTrue("engine not resident after on-demand call", NpuEngine.isReady)
    }

    @Test
    fun backgroundGraceReleasesTheSession() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        // The hook MainActivity.onStop calls: after the grace period with no
        // activity in front, the session must let go of the NPU on its own.
        NpuEngine.onHostBackgrounded()
        awaitStatus(120_000) { it == NpuEngine.Status.DOWNLOADED }
        Log.i(TAG, "released after background grace; status=${NpuEngine.status}")
        NpuEngine.onHostForegrounded()
        assertTrue("session not released by background grace", NpuEngine.status == NpuEngine.Status.DOWNLOADED)
    }

    companion object {
        private const val TAG = "NpuResidencyTest"
        private var scenario: ActivityScenario<MainActivity>? = null

        /** Loading a 4B model onto the NPU is a once-per-suite, minute-scale cost. */
        @JvmStatic
        @BeforeClass
        fun bringUpEngine() {
            // Same constraint as NpuInferenceTest: cDSP grants multi-GB
            // allocations only to the top-app cgroup, so the Activity must be
            // resumed before a load is attempted.
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
                while (!done(NpuEngine.status)) delay(500)
            }
        }
    }
}
