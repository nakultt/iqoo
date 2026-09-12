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
    fun backgroundingReleasesTheSession() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        // The hook MainActivity.onStop calls. Release is immediate — this
        // ROM's fast_freezer parks backgrounded processes within ~10 s, so a
        // grace period would never fire while the user is actually away.
        NpuEngine.onHostBackgrounded()
        awaitStatus(30_000) { it == NpuEngine.Status.DOWNLOADED }
        Log.i(TAG, "released on backgrounding; status=${NpuEngine.status}")
        assertTrue("session not released on backgrounding", NpuEngine.status == NpuEngine.Status.DOWNLOADED)
    }

    @Test
    fun loadFinishingWhileBackgroundedRollsTheFreshSessionBack() = runBlocking<Unit> {
        // Bring the engine resident whatever an earlier test in the class
        // left behind (JUnit gives no ordering guarantees).
        awaitStatus(240_000) { it == NpuEngine.Status.READY || it == NpuEngine.Status.ERROR || it == NpuEngine.Status.DOWNLOADED }
        if (NpuEngine.status == NpuEngine.Status.DOWNLOADED) {
            NpuEngine.load()
            awaitStatus(240_000) { it == NpuEngine.Status.READY || it == NpuEngine.Status.ERROR }
        }
        assumeTrue("engine did not come up", NpuEngine.isReady)

        // Park the host (onStop) while a load is in flight — the exact window
        // from issue #18: onHostBackgrounded no-ops on LOADING, so without the
        // commit-time rollback the fresh ~4 GB session would sit resident in a
        // process the freezer is about to park.
        NpuEngine.unload()
        awaitStatus(30_000) { it == NpuEngine.Status.DOWNLOADED }
        NpuEngine.onHostBackgrounded()
        NpuEngine.load()

        // The commit must observe the backgrounded flag: the session is
        // released, not resident, whichever way the create lands.
        awaitStatus(240_000) { it != NpuEngine.Status.LOADING }
        Log.i(TAG, "post-background-load status=${NpuEngine.status} err=${NpuEngine.lastError}")
        assertTrue(
            "session taken while backgrounded: ${NpuEngine.status}",
            NpuEngine.status == NpuEngine.Status.DOWNLOADED,
        )

        // Foreground again: the next inference reloads on demand as usual.
        NpuEngine.onHostForegrounded()
        val answer = NpuEngine.run(
            systemPrompt = "Answer with one word only.",
            userPrompt = "Reply with the word READY.",
            maxTokens = 8,
            label = "backgrounded-load reload",
        ).getOrThrow()
        assertTrue("empty answer after foreground reload", answer.isNotBlank())
        awaitStatus(10_000) { it == NpuEngine.Status.READY }
        assertTrue("engine not resident after foreground use", NpuEngine.isReady)
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
