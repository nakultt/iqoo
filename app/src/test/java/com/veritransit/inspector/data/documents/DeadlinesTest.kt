package com.veritransit.inspector.data.documents

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Each clock against the arithmetic of its rule — dates a reader can check with a pen. */
class DeadlinesTest {

    private val road = Deadline.EwayBillValidity()

    @Test
    fun `validity is a day up to 200 km and a day per further 200 km or part`() {
        assertEquals(1, road.days(0))
        assertEquals(1, road.days(200))
        assertEquals(2, road.days(201))
        assertEquals(2, road.days(400))
        assertEquals(3, road.days(401))
    }

    @Test
    fun `over-dimensional cargo counts 20 km to the day`() {
        val odc = Deadline.EwayBillValidity(Deadline.OVER_DIMENSIONAL_KM_PER_DAY)
        assertEquals(1, odc.days(20))
        assertEquals(2, odc.days(21))
    }

    @Test
    fun `negative distance is rejected rather than read as a short trip`() {
        assertFailsWith<IllegalArgumentException> { road.days(-1) }
    }

    @Test
    fun `a day runs to the midnight after the generation date, not for 24 hours`() {
        val lateEvening = LocalDateTime.of(2026, 9, 10, 23, 30)
        assertEquals(LocalDate.of(2026, 9, 11), road.lastValidDay(lateEvening, 150))
        assertEquals(LocalDateTime.of(2026, 9, 12, 0, 0), road.expiresAt(lateEvening, 150))
    }

    @Test
    fun `the extension window closes eight hours after expiry`() {
        // 498 km is three days: valid through 13 September, expired at midnight.
        val expiry = road.expiresAt(LocalDateTime.of(2026, 9, 10, 11, 0), 498)
        val extension = DocumentRegistry.byId("eway-extension").deadline as Deadline.HoursAfter
        assertEquals(LocalDateTime.of(2026, 9, 14, 8, 0), extension.due(expiry))
    }

    @Test
    fun `credit note cut-off is 30 November after the financial year of supply`() {
        assertEquals(LocalDate.of(2027, 11, 30), Deadline.CreditNoteCutoff.due(LocalDate.of(2026, 9, 12)))
        assertEquals(LocalDate.of(2027, 11, 30), Deadline.CreditNoteCutoff.due(LocalDate.of(2027, 3, 31)))
        assertEquals(LocalDate.of(2028, 11, 30), Deadline.CreditNoteCutoff.due(LocalDate.of(2027, 4, 1)))
    }

    @Test
    fun `an annual return furnished earlier brings the credit note cut-off forward`() {
        val supply = LocalDate.of(2026, 9, 12)
        val early = LocalDate.of(2027, 10, 15)
        assertEquals(early, Deadline.CreditNoteCutoff.due(supply, early))
        assertEquals(LocalDate.of(2027, 11, 30), Deadline.CreditNoteCutoff.due(supply, LocalDate.of(2027, 12, 20)))
    }

    @Test
    fun `ITC-04 is half-yearly above five crore turnover`() {
        assertEquals(LocalDate.of(2026, 10, 25), Deadline.Itc04.due(LocalDate.of(2026, 5, 20), true))
        assertEquals(LocalDate.of(2027, 4, 25), Deadline.Itc04.due(LocalDate.of(2026, 11, 2), true))
        assertEquals(LocalDate.of(2027, 4, 25), Deadline.Itc04.due(LocalDate.of(2027, 1, 15), true))
    }

    @Test
    fun `ITC-04 covers the whole financial year otherwise`() {
        assertEquals(LocalDate.of(2027, 4, 25), Deadline.Itc04.due(LocalDate.of(2026, 5, 20), false))
    }

    @Test
    fun `job work clocks run one year for inputs and three for capital goods`() {
        val sent = LocalDate.of(2026, 9, 13)
        val inputs = DocumentRegistry.byId("job-work-inputs-back").deadline as Deadline.YearsAfter
        val capital = DocumentRegistry.byId("job-work-capital-goods-back").deadline as Deadline.YearsAfter
        assertEquals(LocalDate.of(2027, 9, 13), inputs.due(sent))
        assertEquals(LocalDate.of(2029, 9, 13), capital.due(sent))
    }
}
