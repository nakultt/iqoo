package com.veritransit.inspector.ai

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veritransit.inspector.MainActivity
import com.veritransit.inspector.data.CargoItem
import com.veritransit.inspector.data.InspectionRecord
import com.veritransit.inspector.data.Manifest
import com.veritransit.inspector.data.Verdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end checks against the real Hexagon NPU.
 *
 * These need a Snapdragon device with the Qwen3-VL bundle already pulled — CI
 * on an emulator has neither, so the suite skips itself rather than failing.
 *
 * Run with `am instrument`, never `connectedAndroidTest`: that task uninstalls
 * the APK afterwards, which deletes the app's data dir and the 4.4 GB bundle
 * with it. See the README for the exact command.
 */
@RunWith(AndroidJUnit4::class)
class NpuInferenceTest {

    @Test
    fun readsEwayBillFromAPhotograph() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        val frame = croppedSample()
        val reading = InspectorAi.readEwayBill(frame.absolutePath)
        Log.i(TAG, "bill reading: ${reading.getOrNull()}")
        val bill = reading.getOrThrow()

        // The fixture prints EWB-7819-2044-8831 on a TN 38 BX 4491 Tata 407.
        assertTrue("no usable fields read", bill.usable)
        assertTrue("EWB number missed: '${bill.ewb}'", bill.ewb.contains("7819"))
        assertTrue("vehicle missed: '${bill.vehicle}'", bill.vehicle.replace(" ", "").contains("4491"))
        assertTrue("no line items read", bill.items.isNotEmpty())
        Log.i(TAG, "profile: ${NpuEngine.lastProfile}")
    }

    @Test
    fun countsCargoAgainstAManifest() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        val manifest = Manifest(
            ewb = "EWB-7819-2044-8831",
            vehicle = "TN 38 BX 4491",
            vehicleModel = "Tata 407 LCV",
            consignment = "Consumer Electronics",
            route = "Chennai → Coimbatore",
            distanceKm = 498,
            items = listOf(
                CargoItem("Dell UltraSharp 27\" Monitor", "Factory Boxed", 3, 3),
                CargoItem("Logitech Mechanical Keyboard", "Bulk Carton", 4, 4),
            ),
            ref = "#8831",
        )
        val scan = InspectorAi.reconcileCargo(croppedSample().absolutePath, manifest).getOrThrow()
        Log.i(TAG, "reconciliation: ${scan.items}, observation='${scan.observation}'")

        // Every declared line must come back, whatever the photo showed — the
        // mapping is re-keyed against the manifest so nothing can be dropped.
        assertTrue("declared lines lost", scan.items.count { it.expected > 0 } == manifest.items.size)
    }

    @Test
    fun draftsOfficerRemarks() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        val record = InspectionRecord(
            id = "VT-2025-0001",
            ewb = "EWB-7819-2044-8831",
            vehicle = "TN 38 BX 4491",
            vehicleModel = "Tata 407 LCV",
            cargo = "Consumer Electronics",
            route = "Chennai → Coimbatore",
            distanceKm = 498,
            verdict = Verdict.REVIEW,
            timestamp = System.currentTimeMillis(),
            items = listOf(
                CargoItem("Dell UltraSharp 27\" Monitor", "Factory Boxed", 3, 3),
                CargoItem("Logitech Mechanical Keyboard", "Bulk Carton", 4, 3),
                CargoItem("Thermal POS Printer", "Not on manifest", 0, 1),
            ),
        )
        val note = InspectorAi.draftNote(record).getOrThrow()
        Log.i(TAG, "drafted note: $note")
        assertTrue("empty note", note.length > 20)
        assertTrue("not prose", note.contains(" "))
    }

    private fun croppedSample(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = File(ctx.cacheDir, "eway_bill_sample.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("eway_bill_sample.jpg")
            .use { input -> raw.outputStream().use { input.copyTo(it) } }
        val out = File(ctx.cacheDir, "eway_bill_cropped.jpg")
        return squareCrop(raw, out, NpuEngine.VISION_INPUT_PX)
    }

    companion object {
        private const val TAG = "NpuInferenceTest"

        private var scenario: ActivityScenario<MainActivity>? = null

        /** Loading a 4B model onto the NPU is a once-per-suite, minute-scale cost. */
        // JUnit requires this to be `public static void`, so the coroutine
        // scope is opened inside rather than as an expression body.
        @JvmStatic
        @BeforeClass
        fun bringUpEngine() {
            // The Activity has to be resumed before the load is attempted.
            // Creating the four weight-shared HTP contexts asks the cDSP for
            // several GB, and that request is only granted to a process in the
            // top-app cgroup — from a process with no visible Activity it fails
            // every time with QNN_COMMON_ERROR_RESOURCE_UNAVAILABLE (1007) on
            // the third context.
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
