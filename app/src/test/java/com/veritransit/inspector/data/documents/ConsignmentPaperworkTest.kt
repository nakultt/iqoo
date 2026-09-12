package com.veritransit.inspector.data.documents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registry resolved against the electronics and fabric consignments a
 * receiving dock actually sees. Values are held in paise, so the ₹50,000
 * boundary is tested exactly.
 */
class ConsignmentPaperworkTest {

    private fun rupees(amount: Long) = ConsignmentValue(declaredPaise = amount * 100)

    private fun electronics(value: ConsignmentValue?, interState: Boolean = true) =
        Consignment(GoodsCategory.ELECTRONICS, interState, value)

    private fun fabric(value: ConsignmentValue?, interState: Boolean = false) =
        Consignment(GoodsCategory.FABRIC, interState, value)

    private fun resolve(c: Consignment) = ConsignmentPaperwork.resolve(c)

    // ------------------------------------------------------------ the threshold

    @Test
    fun `exactly fifty thousand rupees needs no e-way bill, one paisa more does`() {
        val at = resolve(electronics(rupees(50_000)))
        assertFalse(at.has("eway-generate"))
        assertFalse(at.has("eway-carry"))

        val over = resolve(electronics(ConsignmentValue(declaredPaise = 50_000_01)))
        assertTrue(over.has("eway-generate"))
        assertTrue(over.has("eway-carry"))
    }

    @Test
    fun `consignment value counts the tax and drops exempt supply`() {
        // ₹45,000 of goods with ₹8,100 GST crosses the line ₹45,000 alone does not.
        assertTrue(resolve(electronics(ConsignmentValue(45_000_00, taxPaise = 8_100_00))).has("eway-generate"))
        // A ₹60,000 invoice carrying ₹15,000 of exempt supply moves ₹45,000.
        assertFalse(resolve(fabric(ConsignmentValue(60_000_00, exemptPaise = 15_000_00))).has("eway-generate"))
    }

    @Test
    fun `an unknown value is undetermined, never quietly not required`() {
        val p = resolve(electronics(null))
        assertFalse(p.has("eway-generate"))
        assertTrue(p.undetermined.any { it.id == "eway-generate" })
        assertTrue(p.undetermined.any { it.id == "eway-carry" })
    }

    // ------------------------------------------------------------ electronics

    @Test
    fun `by rail the e-way bill is generated but need not travel`() {
        val p = resolve(electronics(rupees(2_00_000)).copy(mode = TransportMode.RAIL))
        assertTrue(p.has("eway-generate"))
        assertFalse(p.has("eway-carry"))
        assertTrue(p.has("invoice-carry"))
    }

    @Test
    fun `imported electronics travel with the bill of entry`() {
        assertTrue(resolve(electronics(rupees(3_00_000)).copy(imported = true)).has("bill-of-entry-carry"))
        assertFalse(resolve(electronics(rupees(3_00_000))).has("bill-of-entry-carry"))
    }

    @Test
    fun `lots get the invoice before the first, a challan after, the original with the last`() {
        fun lot(index: Int) = resolve(electronics(rupees(4_00_000)).copy(lot = Lot(index, 3)))
        val first = lot(1)
        val middle = lot(2)
        val last = lot(3)

        assertTrue(first.has("lots-invoice-before-first"))
        assertFalse(first.has("lots-challan-each-later"))
        assertFalse(first.has("invoice-carry"))

        assertTrue(middle.has("lots-challan-each-later"))
        assertFalse(middle.has("lots-original-invoice-last"))

        assertTrue(last.has("lots-original-invoice-last"))
        listOf(first, middle, last).forEach { assertTrue(it.has("lots-certified-invoice-copy")) }
    }

    @Test
    fun `e-invoicing stays undetermined until the supplier's status is known`() {
        val unknown = resolve(electronics(rupees(2_00_000)))
        assertTrue(unknown.undetermined.any { it.id == "e-invoice-irn" })
        assertTrue(unknown.gaps.any { it.id == "G7" })

        assertTrue(resolve(electronics(rupees(2_00_000)).copy(supplierEInvoicing = true)).has("e-invoice-irn"))

        val notCovered = resolve(electronics(rupees(2_00_000)).copy(supplierEInvoicing = false))
        assertFalse(notCovered.has("e-invoice-irn"))
        assertFalse(notCovered.gaps.any { it.id == "G7" })
    }

    @Test
    fun `carrier paperwork is required but flagged as secondary-sourced`() {
        val p = resolve(
            electronics(rupees(6_00_000))
                .copy(byCommonCarrier = true, discrepancyAtReceipt = true, supplierEInvoicing = true),
        )
        assertTrue(p.has("lorry-receipt"))
        assertTrue(p.has("carrier-notice"))
        assertEquals(setOf("lorry-receipt", "carrier-notice"), p.weaklySourced.map { it.id }.toSet())
        assertTrue(p.gaps.map { it.id }.containsAll(listOf("G1", "G2", "G8")))
    }

    // ------------------------------------------------------------ fabric

    @Test
    fun `fabric to an out-of-State job worker needs an e-way bill at any value`() {
        val p = resolve(fabric(rupees(12_000), interState = true).copy(reason = MovementReason.JOB_WORK))

        assertTrue(p.has("eway-generate-job-work"))
        assertEquals(Holder.PRINCIPAL_OR_JOB_WORKER, p.required.single { it.id == "eway-generate-job-work" }.holder)
        assertTrue(p.has("eway-carry"))
        assertFalse(p.has("eway-generate"))

        assertTrue(p.has("challan-job-work"))
        assertTrue(p.has("challan-carry"))
        assertTrue(p.has("itc-04"))
        assertTrue(p.has("job-work-inputs-back"))

        assertFalse(p.has("invoice-carry"))
        assertFalse(p.has("job-work-capital-goods-back"))
        assertFalse(p.has("purchase-order"))
    }

    @Test
    fun `the same job work inside the State falls back to the value threshold`() {
        val small = resolve(fabric(rupees(12_000)).copy(reason = MovementReason.JOB_WORK))
        assertFalse(small.has("eway-generate-job-work"))
        assertFalse(small.has("eway-generate"))
        assertTrue(small.has("challan-job-work"))

        assertTrue(resolve(fabric(rupees(80_000)).copy(reason = MovementReason.JOB_WORK)).has("eway-generate"))
    }

    @Test
    fun `buying fabric from an unregistered weaver puts the e-way bill on the buyer`() {
        val p = resolve(fabric(rupees(1_20_000)).copy(supplierRegistered = false))
        assertTrue(p.has("eway-generate-recipient"))
        assertFalse(p.has("eway-generate"))
        assertEquals(Holder.RECIPIENT, p.required.single { it.id == "eway-generate-recipient" }.holder)
    }

    @Test
    fun `capital goods on job work get three years, not one`() {
        val p = resolve(fabric(rupees(9_00_000)).copy(reason = MovementReason.JOB_WORK, capitalGoods = true))
        assertTrue(p.has("job-work-capital-goods-back"))
        assertFalse(p.has("job-work-inputs-back"))
    }

    @Test
    fun `the Tamil Nadu intra-State gap is raised only under an intra-State e-way bill`() {
        assertTrue(resolve(fabric(rupees(75_000))).gaps.any { it.id == "G6" })
        assertFalse(resolve(fabric(rupees(75_000), interState = true)).gaps.any { it.id == "G6" })
        assertFalse(resolve(fabric(rupees(20_000))).gaps.any { it.id == "G6" })
    }

    @Test
    fun `a shortage starts the credit note clock as practice, not law`() {
        val p = resolve(fabric(rupees(90_000), interState = true).copy(discrepancyAtReceipt = true))
        assertTrue(p.customary.any { it.id == "credit-note" })
        assertFalse(p.required.any { it.id == "credit-note" })
    }

    @Test
    fun `customary paperwork is never presented as law`() {
        val p = resolve(electronics(rupees(10_000), interState = false))
        assertEquals(setOf("purchase-order", "packing-list", "goods-received-note"), p.customary.map { it.id }.toSet())
        assertTrue(p.required.none { it.obligation == Obligation.CUSTOMARY })
    }

    @Test
    fun `each category resolves to its own profile`() {
        assertEquals(GoodsProfiles.FABRIC, resolve(fabric(rupees(1))).profile)
        assertEquals(GoodsProfiles.ELECTRONICS, resolve(electronics(rupees(1))).profile)
        assertTrue(GoodsProfiles.FABRIC.notes.any { it.provision == Provisions.R138_RECIPIENT })
        assertTrue(GoodsProfiles.ELECTRONICS.notes.any { it.provision == Provisions.R55_LOTS })
    }

    // ------------------------------------------------------------ the registry itself

    @Test
    fun `requirement ids are unique and every gap points at one that exists`() {
        val ids = DocumentRegistry.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        DocumentRegistry.GAPS.forEach { gap ->
            assertTrue(ids.containsAll(gap.affects), "${gap.id} names unknown ids ${gap.affects - ids.toSet()}")
        }
    }

    @Test
    fun `the carrier rules are the only legal requirements not read from primary text`() {
        val weak = DocumentRegistry.ALL
            .filter { it.obligation == Obligation.LEGALLY_REQUIRED }
            .filter { it.provision?.verification != Verification.PRIMARY }
            .map { it.id }
            .toSet()
        assertEquals(setOf("lorry-receipt", "carrier-notice"), weak)
    }

    @Test
    fun `a legal requirement cannot be built without its provision`() {
        assertFailsWith<IllegalArgumentException> {
            Requirement(
                "uncited", ConsignmentDocument.EWAY_BILL, Duty.GENERATE, Holder.MOVER,
                Trigger.Always, Obligation.LEGALLY_REQUIRED,
            )
        }
    }

    @Test
    fun `rupees are grouped the Indian way`() {
        assertEquals("₹999", formatRupees(999_00))
        assertEquals("₹50,000", formatRupees(50_000_00))
        assertEquals("₹50,000.01", formatRupees(50_000_01))
        assertEquals("₹1,00,000", formatRupees(1_00_000_00))
        assertEquals(
            "consignment value exceeds ₹50,000",
            Trigger.ValueExceeds(DocumentRegistry.EWAY_BILL_THRESHOLD_PAISE).label,
        )
    }
}
