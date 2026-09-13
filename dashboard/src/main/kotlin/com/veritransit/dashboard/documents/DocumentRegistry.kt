package com.veritransit.dashboard.documents

import com.veritransit.dashboard.documents.Trigger.AllOf
import com.veritransit.dashboard.documents.Trigger.Always
import com.veritransit.dashboard.documents.Trigger.AnyOf
import com.veritransit.dashboard.documents.Trigger.ByCommonCarrier
import com.veritransit.dashboard.documents.Trigger.CapitalGoods
import com.veritransit.dashboard.documents.Trigger.DiscrepancyAtReceipt
import com.veritransit.dashboard.documents.Trigger.FirstLot
import com.veritransit.dashboard.documents.Trigger.Imported
import com.veritransit.dashboard.documents.Trigger.InLots
import com.veritransit.dashboard.documents.Trigger.InterState
import com.veritransit.dashboard.documents.Trigger.LastLot
import com.veritransit.dashboard.documents.Trigger.LaterLot
import com.veritransit.dashboard.documents.Trigger.ModeIs
import com.veritransit.dashboard.documents.Trigger.Not
import com.veritransit.dashboard.documents.Trigger.ReasonIs
import com.veritransit.dashboard.documents.Trigger.SupplierOnEInvoicing
import com.veritransit.dashboard.documents.Trigger.UnregisteredToRegistered
import com.veritransit.dashboard.documents.Trigger.ValueExceeds

/**
 * Every provision the registry and the goods profiles cite, with where its
 * wording came from. Rules re-read on CBIC's own repository are marked
 * PRIMARY with that date; those taken from #27's reading and not re-checked
 * say so in [Provision.source].
 */
object Provisions {
    private const val CBIC = "CBIC CGST repository (taxinformation.cbic.gov.in), read 2026-09-13"
    private const val PER_27 = "CBIC repository as read for #27 on 2026-09-12; not re-read"
    private const val LAW_SITE = "non-authoritative law website per #27 (gap G1): Act and sections confirmed, wording not"

    private fun cbic(cite: String, substance: String) = Provision(cite, substance, CBIC, Verification.PRIMARY)
    private fun per27(cite: String, substance: String) = Provision(cite, substance, PER_27, Verification.PRIMARY)

    val R45_CHALLAN = cbic(
        "CGST Rules r.45(1)–(2)",
        "Goods go to a job worker under the principal's challan, carrying the r.55 particulars",
    )
    val R45_ITC04 = cbic(
        "CGST Rules r.45(3)",
        "Job-work challans go into ITC-04 by the 25th after the half-year (turnover above ₹5 crore) or the year",
    )
    val R48_COPIES = cbic(
        "CGST Rules r.48(1)",
        "Invoice for goods in triplicate: ORIGINAL FOR RECIPIENT, DUPLICATE FOR TRANSPORTER, TRIPLICATE FOR SUPPLIER",
    )
    val R48_EINVOICE = cbic(
        "CGST Rules r.48(4)–(5)",
        "A notified supplier invoices through an IRN; an invoice it issues any other way is not an invoice",
    )
    val R55_CHALLAN = cbic(
        "CGST Rules r.55(1)–(2)",
        "Delivery challan in lieu of invoice for job work and non-supply movements; in triplicate",
    )
    val R55_LOTS = cbic(
        "CGST Rules r.55(5)",
        "SKD/CKD or lots: full invoice before the first lot, a challan per later lot, original invoice with the last",
    )
    val R138_VALUE = cbic(
        "CGST Rules r.138(1)",
        "E-way bill before moving goods of consignment value exceeding ₹50,000",
    )
    val R138_JOB_WORK = cbic(
        "CGST Rules r.138(1), proviso",
        "Inter-State job work: the principal or registered job worker generates the e-way bill whatever the value",
    )
    val R138_RECIPIENT = cbic(
        "CGST Rules r.138(3), Explanation 1",
        "Goods from an unregistered supplier: the registered recipient causes the movement, if known at its start",
    )
    val R138_VALIDITY = cbic(
        "CGST Rules r.138(10)",
        "Valid 1 day up to 200 km, +1 day per 200 km or part; each day ends at midnight after generation",
    )
    val R138_EXTENSION = cbic(
        "CGST Rules r.138(10), proviso",
        "Validity may be extended within eight hours from the time of expiry",
    )
    val R138_STATE_AREAS = cbic(
        "CGST Rules r.138(14)(d)",
        "No e-way bill within areas a State notifies under its own r.138(14)(d)",
    )
    val R138_NIL_SCHEDULE = cbic(
        "CGST Rules r.138(14)(e)",
        "No e-way bill for goods in the Schedule to Notification 2/2017-Central Tax (Rate)",
    )
    val R138A_INVOICE = per27(
        "CGST Rules r.138A(1)(a)",
        "The invoice, bill of supply or delivery challan travels with the conveyance",
    )
    val R138A_EWAY = per27(
        "CGST Rules r.138A(1)(b)",
        "The e-way bill travels too — copy, number or RFID — except for rail, air or vessel",
    )
    val R138A_BILL_OF_ENTRY = per27(
        "CGST Rules r.138A(1), second proviso",
        "Imported goods: the bill of entry travels, its number and date in Part A of EWB-01",
    )
    val S34_CREDIT_NOTE = per27(
        "CGST Act s.34(2)",
        "A credit note is declared by 30 November after the year of supply, or the annual return if earlier",
    )
    val S36_RETENTION = per27(
        "CGST Act s.36",
        "Accounts and records are kept for 72 months from the annual return's due date",
    )
    val S143_INPUTS = per27(
        "CGST Act s.143(3)",
        "Inputs not back from the job worker within one year are deemed supplied on the day they were sent",
    )
    val S143_CAPITAL = per27(
        "CGST Act s.143(4)",
        "Capital goods not back within three years are deemed supplied on the day they were sent",
    )
    val CBRA_GOODS_RECEIPT = Provision(
        "Carriage by Road Act 2007 s.9",
        "A common carrier issues a goods receipt for the consignment",
        LAW_SITE, Verification.SECONDARY,
    )
    val CBRA_NOTICE = Provision(
        "Carriage by Road Act 2007 s.16",
        "No suit for loss or damage unless written notice is served within 180 days of booking",
        LAW_SITE, Verification.SECONDARY,
    )
    val LMPC_RETAIL = Provision(
        "Legal Metrology (Packaged Commodities) Rules 2011 r.6",
        "Retail-pack declarations (maker, net quantity, MRP …) attach to packages for retail sale",
        "#27 scoping note; the issue does not record its source",
        Verification.UNVERIFIED,
    )
    val FIRST_ANNEXURE = Provision(
        "CGST Rules r.138(14)(a), Annexure as first notified",
        "Listed 'Khadi yarn' (Ch. 52) and 'Hearing aids' (9021); the Annexure in force lists 8 entries, neither of these",
        "gstzen.in reproduction, read 2026-09-13; the 8-entry Annexure read on CBIC the same day",
        Verification.SECONDARY,
    )
}

/**
 * The per-consignment document spine of #27 Part 1, as data. List order is
 * presentation order: what travels with the goods, what is generated, what
 * follows the receipt, then commercial practice.
 */
object DocumentRegistry {

    /** r.138(1): "exceeding fifty thousand rupees". */
    const val EWAY_BILL_THRESHOLD_PAISE: Long = 50_000_00L

    private val JOB_WORK_INTER_STATE = AllOf(ReasonIs(MovementReason.JOB_WORK), InterState)
    private val BY_VALUE = ValueExceeds(EWAY_BILL_THRESHOLD_PAISE)

    /** An e-way bill exists for this consignment: by value, or by inter-State job work at any value. */
    val EWAY_BILL_REQUIRED: Trigger = AnyOf(BY_VALUE, JOB_WORK_INTER_STATE)

    private val LEGAL = Obligation.LEGALLY_REQUIRED
    private val CUSTOM = Obligation.CUSTOMARY

    val ALL: List<Requirement> = listOf(
        // ------------------------------------------------ what travels with the goods
        Requirement(
            "invoice-carry", ConsignmentDocument.TAX_INVOICE, Duty.CARRY, Holder.TRANSPORTER,
            AllOf(ReasonIs(MovementReason.SUPPLY), Not(InLots)), LEGAL, Provisions.R138A_INVOICE,
            note = "the copy marked DUPLICATE FOR TRANSPORTER (r.48(1)); r.48(6) lifts that for an e-invoice",
        ),
        Requirement(
            "challan-carry", ConsignmentDocument.DELIVERY_CHALLAN, Duty.CARRY, Holder.TRANSPORTER,
            AnyOf(ReasonIs(MovementReason.JOB_WORK), ReasonIs(MovementReason.NOT_SUPPLY)), LEGAL,
            Provisions.R138A_INVOICE,
            note = "the copy marked DUPLICATE FOR TRANSPORTER (r.55(2))",
        ),
        Requirement(
            "lots-certified-invoice-copy", ConsignmentDocument.TAX_INVOICE, Duty.CARRY, Holder.TRANSPORTER,
            InLots, LEGAL, Provisions.R55_LOTS,
            note = "each lot: copies of its delivery challan with a duly certified copy of the invoice",
        ),
        Requirement(
            "lots-original-invoice-last", ConsignmentDocument.TAX_INVOICE, Duty.CARRY, Holder.TRANSPORTER,
            LastLot, LEGAL, Provisions.R55_LOTS,
            note = "the original invoice travels with the last lot",
        ),
        Requirement(
            "eway-carry", ConsignmentDocument.EWAY_BILL, Duty.CARRY, Holder.TRANSPORTER,
            AllOf(EWAY_BILL_REQUIRED, ModeIs(TransportMode.ROAD)), LEGAL, Provisions.R138A_EWAY,
            note = "not valid by road until Part B (the vehicle) is filled — r.138(3) Explanation 2",
        ),
        Requirement(
            "bill-of-entry-carry", ConsignmentDocument.BILL_OF_ENTRY, Duty.CARRY, Holder.TRANSPORTER,
            Imported, LEGAL, Provisions.R138A_BILL_OF_ENTRY,
            note = "its number and date also go into Part A of the e-way bill",
        ),

        // ------------------------------------------------ issued or generated before movement
        Requirement(
            "e-invoice-irn", ConsignmentDocument.TAX_INVOICE, Duty.ISSUE, Holder.SUPPLIER,
            AllOf(ReasonIs(MovementReason.SUPPLY), SupplierOnEInvoicing), LEGAL, Provisions.R48_EINVOICE,
            note = "issued any other way it is not an invoice — and the recipient's credit rests on it",
        ),
        Requirement(
            "challan-job-work", ConsignmentDocument.DELIVERY_CHALLAN, Duty.ISSUE, Holder.PRINCIPAL,
            ReasonIs(MovementReason.JOB_WORK), LEGAL, Provisions.R45_CHALLAN,
        ),
        Requirement(
            "challan-not-supply", ConsignmentDocument.DELIVERY_CHALLAN, Duty.ISSUE, Holder.SUPPLIER,
            ReasonIs(MovementReason.NOT_SUPPLY), LEGAL, Provisions.R55_CHALLAN,
        ),
        Requirement(
            "lots-invoice-before-first", ConsignmentDocument.TAX_INVOICE, Duty.ISSUE, Holder.SUPPLIER,
            FirstLot, LEGAL, Provisions.R55_LOTS,
            note = "the complete invoice, before the first lot is dispatched",
        ),
        Requirement(
            "lots-challan-each-later", ConsignmentDocument.DELIVERY_CHALLAN, Duty.ISSUE, Holder.SUPPLIER,
            LaterLot, LEGAL, Provisions.R55_LOTS,
            note = "one per lot, referencing the invoice",
        ),
        Requirement(
            "eway-generate", ConsignmentDocument.EWAY_BILL, Duty.GENERATE, Holder.MOVER,
            AllOf(BY_VALUE, Not(JOB_WORK_INTER_STATE), Not(UnregisteredToRegistered)), LEGAL,
            Provisions.R138_VALUE,
            note = "before movement; by rail, air or vessel it may follow the start of movement (r.138(2A))",
        ),
        Requirement(
            "eway-generate-recipient", ConsignmentDocument.EWAY_BILL, Duty.GENERATE, Holder.RECIPIENT,
            AllOf(BY_VALUE, Not(JOB_WORK_INTER_STATE), UnregisteredToRegistered), LEGAL,
            Provisions.R138_RECIPIENT,
            note = "the receiving business, not the supplier, owes this bill",
        ),
        Requirement(
            "eway-generate-job-work", ConsignmentDocument.EWAY_BILL, Duty.GENERATE,
            Holder.PRINCIPAL_OR_JOB_WORKER, JOB_WORK_INTER_STATE, LEGAL, Provisions.R138_JOB_WORK,
            note = "whatever the consignment value",
        ),

        // ------------------------------------------------ clocks the movement starts
        Requirement(
            "eway-validity", ConsignmentDocument.EWAY_BILL, Duty.MOVE_WITHIN, Holder.TRANSPORTER,
            EWAY_BILL_REQUIRED, LEGAL, Provisions.R138_VALIDITY,
            deadline = Deadline.EwayBillValidity(),
        ),
        Requirement(
            "eway-extension", ConsignmentDocument.EWAY_BILL, Duty.EXTEND, Holder.TRANSPORTER,
            EWAY_BILL_REQUIRED, LEGAL, Provisions.R138_EXTENSION,
            deadline = Deadline.HoursAfter(8, "the e-way bill expires"),
        ),
        Requirement(
            "itc-04", ConsignmentDocument.ITC_04, Duty.FILE, Holder.PRINCIPAL,
            ReasonIs(MovementReason.JOB_WORK), LEGAL, Provisions.R45_ITC04,
            deadline = Deadline.Itc04,
        ),
        Requirement(
            "job-work-inputs-back", ConsignmentDocument.DELIVERY_CHALLAN, Duty.RECOVER, Holder.PRINCIPAL,
            AllOf(ReasonIs(MovementReason.JOB_WORK), Not(CapitalGoods)), LEGAL, Provisions.S143_INPUTS,
            deadline = Deadline.YearsAfter(1, "the inputs were sent out"),
            note = "or supplied from the job worker's premises; otherwise tax falls due as of the day sent (r.45(4))",
        ),
        Requirement(
            "job-work-capital-goods-back", ConsignmentDocument.DELIVERY_CHALLAN, Duty.RECOVER,
            Holder.PRINCIPAL, AllOf(ReasonIs(MovementReason.JOB_WORK), CapitalGoods), LEGAL,
            Provisions.S143_CAPITAL,
            deadline = Deadline.YearsAfter(3, "the capital goods were sent out"),
        ),
        Requirement(
            "lorry-receipt", ConsignmentDocument.LORRY_RECEIPT, Duty.ISSUE, Holder.COMMON_CARRIER,
            AllOf(ModeIs(TransportMode.ROAD), ByCommonCarrier), LEGAL, Provisions.CBRA_GOODS_RECEIPT,
        ),
        Requirement(
            "carrier-notice", ConsignmentDocument.CARRIER_NOTICE, Duty.SERVE, Holder.CLAIMANT,
            AllOf(ModeIs(TransportMode.ROAD), ByCommonCarrier, DiscrepancyAtReceipt), LEGAL,
            Provisions.CBRA_NOTICE,
            deadline = Deadline.DaysAfter(180, "the consignment was booked"),
            note = "in writing, before any suit — counted from booking, not from delivery",
        ),
        Requirement(
            "records-retain", ConsignmentDocument.RECORDS, Duty.RETAIN, Holder.EVERY_REGISTERED_PARTY,
            Always, LEGAL, Provisions.S36_RETENTION,
            deadline = Deadline.MonthsAfter(72, "the due date of the year's annual return"),
        ),

        // ------------------------------------------------ commercial practice, not law
        Requirement(
            "credit-note", ConsignmentDocument.CREDIT_NOTE, Duty.ISSUE, Holder.SUPPLIER,
            DiscrepancyAtReceipt, CUSTOM, Provisions.S34_CREDIT_NOTE,
            deadline = Deadline.CreditNoteCutoff,
            note = "no statute makes the supplier issue one; s.34(2) only fixes how late it can be declared",
        ),
        Requirement(
            "purchase-order", ConsignmentDocument.PURCHASE_ORDER, Duty.ISSUE, Holder.RECIPIENT,
            ReasonIs(MovementReason.SUPPLY), CUSTOM,
            note = "the anchor of the purchase order ↔ invoice ↔ goods received note match",
        ),
        Requirement(
            "packing-list", ConsignmentDocument.PACKING_LIST, Duty.ISSUE, Holder.SUPPLIER, Always, CUSTOM,
            note = "what was packed, carton by carton — what the count reconciles against",
        ),
        Requirement(
            "goods-received-note", ConsignmentDocument.GOODS_RECEIVED_NOTE, Duty.ISSUE, Holder.RECIPIENT,
            Always, CUSTOM,
            note = "sign \"subject to inspection\" until counted, never a clean receipt for unchecked goods",
        ),
    )

    private val EWAY_IDS = setOf(
        "eway-carry", "eway-generate", "eway-generate-recipient", "eway-generate-job-work",
        "eway-validity", "eway-extension",
    )

    val GAPS: List<OpenGap> = listOf(
        OpenGap(
            "G1", "Carriage by Road Act 2007 wording has not been read from a government source",
            setOf("lorry-receipt", "carrier-notice"),
        ),
        OpenGap(
            "G2", "Carriage by Road Rules 2011 — the prescribed goods receipt form — not located",
            setOf("lorry-receipt"),
        ),
        OpenGap(
            "G6", "Tamil Nadu areas or values exempted under its own r.138(14)(d) are not researched",
            EWAY_IDS, intraStateOnly = true,
        ),
        OpenGap(
            "G7", "The ₹5 crore e-invoicing threshold is not checked against notifications after 10/2023-CT",
            setOf("e-invoice-irn"),
        ),
        OpenGap(
            "G8", "The carrier liability cap under the Carriage by Road Act s.10 is not verified",
            setOf("lorry-receipt"),
        ),
        OpenGap(
            "R138-14E", "The Schedule to Notification 2/2017-CT(Rate) that r.138(14)(e) exempts is not read",
            EWAY_IDS,
        ),
    )

    fun byId(id: String): Requirement = ALL.first { it.id == id }
}
