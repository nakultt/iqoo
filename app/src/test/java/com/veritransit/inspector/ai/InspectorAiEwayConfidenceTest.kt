package com.veritransit.inspector.ai

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/**
 * JVM-side checks for the E-Way Bill per-section confidence parsing — the
 * exact decode path [InspectorAi.readEwayBill] takes on device, minus the
 * NPU. Runs without a device or the model bundle.
 */
class InspectorAiEwayConfidenceTest {

    @Test
    fun `sections absent from the reply parse as not reported`() {
        val reading = InspectorAi.parseBillReading(
            """
            {"ewb_number": "EWB-7819-2044-8831", "vehicle_number": "TN 38 BX 4491",
             "legible": true}
            """.trimIndent(),
        )
        assertNull(reading.headerConfidence)
        assertNull(reading.routeConfidence)
        assertNull(reading.itemsConfidence)
    }

    @Test
    fun `in-range confidences pass through verbatim`() {
        val reading = InspectorAi.parseBillReading(
            """
            {"ewb_number": "EWB-1", "vehicle_number": "TN 38 BX 4491",
             "header_confidence": 0.42, "route_confidence": 0.9, "items_confidence": 0.0}
            """.trimIndent(),
        )
        assertEquals(0.42f, reading.headerConfidence)
        assertEquals(0.9f, reading.routeConfidence)
        assertEquals(0.0f, reading.itemsConfidence)
    }

    @Test
    fun `percent-scale confidences are rescaled, not clamped to certainty`() {
        val reading = InspectorAi.parseBillReading(
            """{"header_confidence": 85, "route_confidence": 100, "items_confidence": 12}""",
        )
        assertEquals(0.85f, reading.headerConfidence)
        assertEquals(1f, reading.routeConfidence)
        assertEquals(0.12f, reading.itemsConfidence)
    }

    @Test
    fun `garbage confidences become not-reported instead of a false all-clear`() {
        val reading = InspectorAi.parseBillReading(
            """{"header_confidence": -3, "route_confidence": 250, "items_confidence": null}""",
        )
        assertNull(reading.headerConfidence)
        assertNull(reading.routeConfidence)
        assertNull(reading.itemsConfidence)
    }

    @Test
    fun `only the sections below the threshold are flagged`() {
        val reading = InspectorAi.parseBillReading(
            """{"header_confidence": 0.42, "route_confidence": 0.9, "items_confidence": 0.08}""",
        )
        assertEquals(listOf("header", "items"), InspectorAi.lowConfidenceSections(reading))
    }

    @Test
    fun `unreported sections are never flagged`() {
        val reading = InspectorAi.parseBillReading("""{"ewb_number": "EWB-1"}""")
        assertEquals(emptyList<String>(), InspectorAi.lowConfidenceSections(reading))
    }

    @Test
    fun `fenced replies still parse`() {
        val reading = InspectorAi.parseBillReading(
            """Sure! Here is the reading: {"header_confidence": 0.7} — hope that helps""",
        )
        assertEquals(0.7f, reading.headerConfidence)
        assertEquals("", reading.ewb)
    }
}
