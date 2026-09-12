package com.veritransit.inspector.ai

import com.veritransit.inspector.data.CargoItem
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * JVM-side checks for the reconciliation mapping — the part that turns the
 * model's JSON into record lines. These run without a device, so a regression
 * here fails CI rather than waiting for the next NPU bench run.
 */
class InspectorAiReconciliationTest {

    private fun manifest(vararg items: Pair<String, Int>) = Manifest(
        ewb = "EWB-7819-2044-8831",
        vehicle = "TN 38 BX 4491",
        vehicleModel = "Tata 407 LCV",
        consignment = "Consumer Electronics",
        route = "Chennai → Coimbatore",
        distanceKm = 498,
        items = items.map { CargoItem(it.first, "Declared line item", it.second, it.second) },
        ref = "#8831",
    )

    @Test
    fun `over-count is preserved, not clamped to the declared quantity`() {
        val m = manifest("Dell UltraSharp 27\" Monitor" to 3)
        val parsed = InspectorAi.Reconciliation(
            items = listOf(InspectorAi.CountedItem("Dell UltraSharp 27\" Monitor", found = 5)),
        )
        val scan = InspectorAi.applyReconciliation(parsed, m)
        val line = scan.items.single()
        assertEquals(5, line.found)
        assertEquals(ItemStatus.OVERAGE, line.status)
    }

    @Test
    fun `shortage still reconciles as a shortage`() {
        val m = manifest("Logitech Mechanical Keyboard" to 4)
        val parsed = InspectorAi.Reconciliation(
            items = listOf(InspectorAi.CountedItem("Logitech Mechanical Keyboard", found = 2)),
        )
        val scan = InspectorAi.applyReconciliation(parsed, m)
        assertEquals(2, scan.items.single().found)
        assertEquals(ItemStatus.SHORTAGE, scan.items.single().status)
    }

    @Test
    fun `unlisted goods come back as manifest extras`() {
        val m = manifest("Dell UltraSharp 27\" Monitor" to 3)
        val parsed = InspectorAi.Reconciliation(
            items = listOf(InspectorAi.CountedItem("Dell UltraSharp 27\" Monitor", found = 3)),
            unlisted = listOf(InspectorAi.UnlistedItem("Diesel Generator", count = 2)),
        )
        val scan = InspectorAi.applyReconciliation(parsed, m)
        val extra = scan.items.last()
        assertEquals("Diesel Generator", extra.name)
        assertEquals(0, extra.expected)
        assertEquals(2, extra.found)
        assertEquals(ItemStatus.UNLISTED, extra.status)
    }

    @Test
    fun `a renamed declared line cannot be silently dropped`() {
        val m = manifest("Dell UltraSharp 27\" Monitor" to 3)
        val parsed = InspectorAi.Reconciliation(
            items = listOf(InspectorAi.CountedItem("Dell Monitor", found = 3)),
        )
        val scan = InspectorAi.applyReconciliation(parsed, m)
        // The rename does not match, so the declared line reports 0 seen —
        // visible as a shortage, never as a missing row.
        assertEquals(m.items.size, scan.items.count { it.expected > 0 })
        assertEquals(0, scan.items.first().found)
    }

    @Test
    fun `schema echoes never reach the record as cargo lines`() {
        val m = manifest("Dell UltraSharp 27\" Monitor" to 3)
        val parsed = InspectorAi.Reconciliation(
            unlisted = listOf(
                InspectorAi.UnlistedItem("line item", count = 1),
                InspectorAi.UnlistedItem("Not on the manifest", count = 1),
                InspectorAi.UnlistedItem("", count = 1),
            ),
        )
        val scan = InspectorAi.applyReconciliation(parsed, m)
        assertTrue(scan.items.none { it.expected == 0 }, "echoed placeholders leaked through: ${scan.items}")
    }

    @Test
    fun `raw confidence below the old floor survives the pipeline`() {
        assertEquals(0.42f, InspectorAi.normaliseConfidence(0.42f))
        assertEquals(0.08f, InspectorAi.normaliseConfidence(0.08f))
    }

    @Test
    fun `confidence reported in percent is rescaled and not clamped to certainty`() {
        assertEquals(0.85f, InspectorAi.normaliseConfidence(85f))
        assertEquals(1f, InspectorAi.normaliseConfidence(250f))
    }

    @Test
    fun `negative confidence is floored at zero`() {
        assertEquals(0f, InspectorAi.normaliseConfidence(-3f))
    }

    @Test
    fun `fenced or trailing prose around the JSON object still parses`() {
        assertEquals(
            "{\"found\": 3}",
            InspectorAi.extractJsonObject("Here is the JSON: {\"found\": 3} — hope that helps!"),
        )
        assertEquals("{\"a\": {\"b\": 1}}", InspectorAi.extractJsonObject("{\"a\": {\"b\": 1}}"))
        assertEquals(
            "{\"s\": \"brace } inside string\"}",
            InspectorAi.extractJsonObject("prefix {\"s\": \"brace } inside string\"} suffix"),
        )
    }
}
