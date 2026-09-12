package com.veritransit.inspector.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * JVM-side checks for the QR payload extraction — the exact parse path the
 * bill scanner takes on device, minus the camera and ML Kit. Runs without a
 * device.
 */
class QrBillTest {

    @Test
    fun `bare 12-digit number is the ewb`() {
        val fields = QrBill.parse("781920448831")
        assertEquals("7819-2044-8831", fields.ewb)
        assertNull(fields.vehicle)
    }

    @Test
    fun `digits are grouped like the manual entry format`() {
        val fields = QrBill.parse("EWB No: 904828104422")
        assertEquals("9048-2810-4422", fields.ewb)
    }

    @Test
    fun `a longer digit run is not read as an ewb`() {
        assertNull(QrBill.parse("0123456789012345678901").ewb)
    }

    @Test
    fun `json payload yields ewb and plate`() {
        val fields = QrBill.parse(
            """{"ewbNo":"781920448831","vehNo":"TN38BX4491","from":"Chennai"}""",
        )
        assertEquals("7819-2044-8831", fields.ewb)
        assertEquals("TN38BX4491", fields.vehicle)
    }

    @Test
    fun `spaced plate keeps its grouping`() {
        val fields = QrBill.parse("VEHICLE TN 38 BX 4491 BILL 781920448831")
        assertEquals("TN 38 BX 4491", fields.vehicle)
        assertEquals("7819-2044-8831", fields.ewb)
    }

    @Test
    fun `an unrelated qr still carries its raw text`() {
        val fields = QrBill.parse("https://example.com/consignment/abc")
        assertNull(fields.ewb)
        assertNull(fields.vehicle)
        assertEquals("https://example.com/consignment/abc", fields.raw)
    }
}
