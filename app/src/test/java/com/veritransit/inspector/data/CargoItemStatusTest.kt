package com.veritransit.inspector.data

import kotlin.test.assertEquals
import kotlin.test.Test

/** Status derivation, including the overage case that used to be erased. */
class CargoItemStatusTest {

    @Test
    fun `matching counts are matched`() {
        assertEquals(ItemStatus.MATCHED, CargoItem("Crate", "Box", 3, 3).status)
    }

    @Test
    fun `fewer than declared is a shortage`() {
        assertEquals(ItemStatus.SHORTAGE, CargoItem("Crate", "Box", 3, 1).status)
    }

    @Test
    fun `more than declared is an overage, not a match`() {
        assertEquals(ItemStatus.OVERAGE, CargoItem("Crate", "Box", 3, 5).status)
    }

    @Test
    fun `goods with no declared line at all are unlisted`() {
        assertEquals(ItemStatus.UNLISTED, CargoItem("Generator", "Not on manifest", 0, 2).status)
    }

    @Test
    fun `an overage flags the record for review`() {
        val record = InspectionRecord(
            id = "VT-2025-0001",
            ewb = "EWB-1",
            vehicle = "TN 38 BX 4491",
            vehicleModel = "",
            cargo = "Electronics",
            route = "Chennai → Coimbatore",
            distanceKm = 498,
            verdict = Verdict.PENDING,
            timestamp = 0L,
            items = listOf(CargoItem("Crate", "Box", 3, 5)),
        )
        assertEquals(1, record.discrepancyCount)
        assertEquals(true, record.items.any { it.status == ItemStatus.OVERAGE })
    }
}
