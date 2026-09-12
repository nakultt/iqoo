package com.veritransit.inspector.ai

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veritransit.inspector.MainActivity
import com.veritransit.inspector.data.PackingItem
import com.veritransit.inspector.data.PackingList
import com.veritransit.inspector.data.ReceiptOutcome
import com.veritransit.inspector.data.ReceivingRecord
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
    fun readsPackingListFromAPhotograph() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        val frame = croppedSample()
        val reading = ReceivingAi.readPackingList(frame.absolutePath)
        Log.i(TAG, "packing-list reading: ${reading.getOrNull()}")
        val list = reading.getOrThrow()

        // The fixture prints the supplier's packing list with its purchase
        // order and packing-list references — and no goods table, so the honest
        // OCR result is populated header fields and no line items.
        assertTrue("model judged the fixture illegible", list.legible)
        assertTrue(
            "purchase order missed: '${list.purchaseOrderId}'",
            list.purchaseOrderId.filter { it.isDigit() }.contains("4471"),
        )
        assertTrue(
            "packing list reference missed: '${list.packingListId}'",
            list.packingListId.isNotBlank(),
        )
        assertTrue(
            "supplier name missed: '${list.supplier}'",
            list.supplier.contains("Bright", ignoreCase = true),
        )
        // The fixture prints no goods rows, so a populated items list means
        // the model invented them — exactly the hallucination a record must
        // never inherit.
        assertTrue("model invented packed lines on a header-only list: ${list.items}", list.items.isEmpty())
    }

    @Test
    fun countsDeliveredGoodsAgainstAPackingList() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        val packingList = PackingList(
            purchaseOrderId = "PO-2025-4471",
            packingListId = "PL-2025-4471-A",
            supplier = "Bright Electronics Pvt Ltd",
            goods = "Consumer Electronics",
            dock = "Dock 3 · Central DC",
            carrier = "BlueDart Surface",
            items = listOf(
                PackingItem("ELC-2710", "Dell UltraSharp 27\" Monitor", "Factory Boxed", 3, 3),
                PackingItem("ELC-1180", "Logitech Mechanical Keyboard", "Bulk Carton", 4, 4),
            ),
        )
        val count = ReceivingAi.countDelivery(croppedSample().absolutePath, packingList).getOrThrow()
        Log.i(TAG, "count: ${count.items}, observation='${count.observation}', conf=${count.confidence}")

        // Every packed line must come back, whatever the photo showed — the
        // mapping is re-keyed against the packing list so nothing can be dropped.
        assertTrue("packed lines lost", count.items.count { it.expected > 0 } == packingList.items.size)

        // The fixture is a photograph of a paper list, not the delivered goods:
        // the counts are the model's word, but they must stay inside the scale
        // the prompt demands, and the observation must actually describe something.
        assertTrue(
            "counts outside 0..99: ${count.items.map { it.received }}",
            count.items.all { it.received in 0..99 },
        )
        assertTrue(
            "confidence outside 0..1: ${count.confidence}",
            count.confidence in 0f..1f,
        )
        assertTrue("empty observation", count.observation.isNotBlank())
        // The fixture is a paper list, not the delivered goods: whatever the
        // model lists as unlisted must never include the packed lines themselves.
        val unlistedNames = count.items.filter { it.expected == 0 }.map { it.name }
        assertTrue(
            "packed item reappeared as unlisted: $unlistedNames",
            unlistedNames.none { u -> packingList.items.any { it.name.equals(u, ignoreCase = true) } },
        )
        Log.i(TAG, "unlisted read: ${count.items.filter { it.expected == 0 }}")
    }

    @Test
    fun draftsReceiverNote() = runBlocking<Unit> {
        assumeTrue("model not resident", NpuEngine.isReady)

        val record = ReceivingRecord(
            id = "GRN-2025-0001",
            purchaseOrderId = "PO-2025-4471",
            packingListId = "PL-2025-4471-A",
            supplier = "Bright Electronics Pvt Ltd",
            goods = "Consumer Electronics",
            dock = "Dock 3 · Central DC",
            carrier = "BlueDart Surface",
            outcome = ReceiptOutcome.OVER,
            timestamp = System.currentTimeMillis(),
            items = listOf(
                PackingItem("ELC-2710", "Dell UltraSharp 27\" Monitor", "Factory Boxed", 3, 3),
                PackingItem("ELC-1180", "Logitech Mechanical Keyboard", "Bulk Carton", 4, 3),
                // Exercises the unlisted branch of the note prompt: goods the
                // packing list never declared must reach the note, not vanish.
                PackingItem("", "Thermal POS Printer", "Not on packing list", 0, 1),
                PackingItem("HDM-9001", "HDMI Cable", "Bulk Carton", 2, 5),
            ),
        )
        val note = ReceivingAi.draftNote(record).getOrThrow()
        Log.i(TAG, "drafted note: $note")
        assertTrue("empty note", note.length > 20)
        assertTrue("not prose", note.contains(" "))
        assertTrue(
            "note dropped the overage: $note",
            note.contains("HDMI", ignoreCase = true) || note.contains("over", ignoreCase = true) ||
                note.contains("five", ignoreCase = true) || note.contains("5", ignoreCase = true),
        )
    }

    private fun croppedSample(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = File(ctx.cacheDir, "packing_list_sample.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("packing_list_sample.jpg")
            .use { input -> raw.outputStream().use { input.copyTo(it) } }
        val out = File(ctx.cacheDir, "packing_list_fitted.jpg")
        // Same preprocessing the receiving screen uses, so the test measures what
        // the receiver actually gets.
        return squareFit(raw, out, NpuEngine.VISION_INPUT_PX)
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
