package com.veritransit.inspector.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Which packing list a scanned carton label opens — or none at all. */
class PackingListLookupTest {

    private fun lookup(payload: String) = Presets.forLabel(QrLabel.parse(payload))

    @Test
    fun `a po label opens that po's packing list, not the default`() {
        assertEquals("Deccan Fasteners & Steel", lookup("PO-2025-4488")?.supplier)
        assertEquals("Sahyadri Foods LLP", lookup("""{"po":"PO20254502"}""")?.supplier)
    }

    @Test
    fun `the apples consignment qr opens the produce list the dock has`() {
        // The exact payload the Red Apples QR carries (see ApplesLabelPayload):
        // the scanner reads the PO out of the JSON and the dock's preset
        // supplies the goods lines — 120 crates, so the card shows 120 Pcs.
        val preset = assertNotNull(lookup(APPLES_QR_PAYLOAD), "the dock has this packing list")
        assertEquals("Himachal Orchards LLP", preset.supplier)
        assertEquals(120, preset.totalUnits)
        assertEquals("PRD-8101", preset.items.single().sku)
    }

    @Test
    fun `a packing-list label opens its list with or without the revision letter`() {
        assertEquals("PO-2025-4519", lookup("PL-2025-4519-A")?.purchaseOrderId)
        assertEquals("PO-2025-4519", lookup("PL-2025-4519")?.purchaseOrderId)
    }

    @Test
    fun `the po decides when a label prints both`() {
        assertEquals("PO-2025-4471", lookup("PO-2025-4471 PL-2025-4488-A")?.purchaseOrderId)
    }

    @Test
    fun `an unknown po or a code that is not a label opens nothing`() {
        assertNull(lookup("PO-2025-9999"))
        assertNull(lookup("PO-2025-9999 PL-2025-4471-A"))
        assertNull(lookup("8901234567890"))
        assertNull(lookup("https://example.com/carton/42"))
    }
}
