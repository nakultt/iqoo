package com.veritransit.inspector.ai

import com.veritransit.inspector.data.BoxLabel
import com.veritransit.inspector.data.BoxLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM-side checks for the box photo-vs-label check — the part that turns the
 * model's JSON into a match/mismatch verdict. These run without a device, so
 * a regression here fails CI rather than waiting for the next NPU bench run.
 */
class ReceivingAiBoxVerifyTest {

    private val names = mapOf(
        "ELC-2710" to "Dell UltraSharp 27\" Monitor",
        "ELC-1180" to "Logitech Mechanical Keyboard",
    )

    private val box = BoxLabel.Inner(
        "PO-2025-4471", "PL-2025-4471-A", "MB-4471-01-B01",
        masterId = "MB-4471-01", seq = 1, of = 2,
        lines = listOf(BoxLine("ELC-2710", 3)),
    )

    @Test
    fun `a match verdict parses with its observation`() {
        val verdict = ReceivingAi.parseBoxVerdict(
            """{"matches": true, "observation": "Three boxed monitors are stacked on the dock.", "confidence": 0.9}""",
        )
        assertTrue(verdict.matches)
        assertEquals("Three boxed monitors are stacked on the dock.", verdict.observation)
        assertEquals(0.9f, verdict.confidence)
    }

    @Test
    fun `a mismatch verdict never reads as a match`() {
        val verdict = ReceivingAi.parseBoxVerdict(
            """Here is the JSON: {"matches": false, "observation": "Only keyboards are visible.", "confidence": 0.8} trailing words""",
        )
        assertFalse(verdict.matches)
        assertEquals("Only keyboards are visible.", verdict.observation)
    }

    @Test
    fun `percent confidence is rescaled, not clamped into a false all-clear`() {
        val verdict = ReceivingAi.parseBoxVerdict(
            """{"matches": true, "observation": "Matches.", "confidence": 85}""",
        )
        assertEquals(0.85f, verdict.confidence)
    }

    @Test
    fun `a reply with no json is a failure, never a pass`() {
        assertFailsWith<IllegalArgumentException> {
            ReceivingAi.parseBoxVerdict("the photo is too dark to tell")
        }
    }

    @Test
    fun `the declaration names the product, count and supplier in plain words`() {
        val text = ReceivingAi.describeBox(box, names, "Bright Electronics Pvt Ltd")
        assertTrue("3 × Dell UltraSharp 27\" Monitor (ELC-2710)" in text)
        assertTrue("MB-4471-01-B01" in text)
        assertTrue("Bright Electronics Pvt Ltd" in text)
    }

    @Test
    fun `the master declaration names the little boxes and the total`() {
        val master = BoxLabel.Master("PO-2025-4471", "PL-2025-4471-A", "MB-4471-01", boxCount = 2, units = 7)
        val text = ReceivingAi.describeBox(master, names, "Bright Electronics Pvt Ltd")
        assertTrue("2 little boxes" in text)
        assertTrue("7 items" in text)
    }
}
