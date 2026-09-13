package com.veritransit.dashboard.documents

/*
 * Issue #27 — which papers a consignment needs, encoded as data:
 * rule → document → trigger → deadline.
 *
 * A [Requirement] names the [Provision] it rests on, the document, who owes it,
 * the facts that make it bite ([Trigger]) and any clock it starts ([Deadline]).
 * [ConsignmentPaperwork] evaluates [DocumentRegistry] against one
 * [Consignment]. Nothing here reads a model, the network or the wall clock, so
 * the same facts always produce the same list.
 *
 * First cut: electronics and fabric, under central law as it applies in Tamil
 * Nadu. Every provision records how far its wording was actually checked, so a
 * screen can never present a secondary-sourced rule with the confidence of one
 * read from the government's own text. The app detects and evidences; it does
 * not rule on legality.
 */

/** How far a provision's wording has been checked. */
enum class Verification {
    /** Read verbatim from the government's own text. */
    PRIMARY,

    /** Only a non-authoritative reproduction was read: the provision exists, its wording is unconfirmed. */
    SECONDARY,

    /** No checked source at all. Never present as settled. */
    UNVERIFIED,
}

data class Provision(
    /** Short citation, e.g. `CGST Rules r.138(1)`. */
    val cite: String,
    /** What it says, in one line. */
    val substance: String,
    /** Where the wording was read, and when. */
    val source: String,
    val verification: Verification,
)

enum class ConsignmentDocument(val title: String) {
    TAX_INVOICE("Tax invoice"),
    DELIVERY_CHALLAN("Delivery challan"),
    EWAY_BILL("E-way bill"),
    BILL_OF_ENTRY("Bill of entry"),
    LORRY_RECEIPT("Lorry receipt (goods receipt)"),
    ITC_04("FORM GST ITC-04"),
    CREDIT_NOTE("Credit note"),
    CARRIER_NOTICE("Written notice of loss or damage"),
    RECORDS("Accounts and records of the consignment"),
    PURCHASE_ORDER("Purchase order"),
    PACKING_LIST("Packing list"),
    GOODS_RECEIVED_NOTE("Goods received note"),
}

enum class Duty(val verb: String) {
    ISSUE("issue"),
    GENERATE("generate"),
    CARRY("carry with the goods"),
    MOVE_WITHIN("complete the movement within"),
    EXTEND("extend"),
    FILE("file"),
    RECOVER("receive the goods back"),
    RETAIN("retain"),
    SERVE("serve"),
}

enum class Holder(val label: String) {
    SUPPLIER("Supplier / consigner"),
    RECIPIENT("Recipient"),
    PRINCIPAL("Principal"),
    PRINCIPAL_OR_JOB_WORKER("Principal, or the job worker if registered"),
    MOVER("Registered person causing the movement"),
    TRANSPORTER("Person in charge of the conveyance"),
    COMMON_CARRIER("Common carrier"),
    CLAIMANT("Party claiming against the carrier"),
    EVERY_REGISTERED_PARTY("Every registered party"),
}

/** Whether a statute demands the paper, or only commerce does. */
enum class Obligation { LEGALLY_REQUIRED, CUSTOMARY }

enum class GoodsCategory(val label: String) {
    ELECTRONICS("Electronics"),
    FABRIC("Fabric"),
}

enum class TransportMode { ROAD, RAIL, AIR, VESSEL }

enum class MovementReason {
    /** Goods moving against a sale. */
    SUPPLY,

    /** Inputs or capital goods sent out by a principal to a job worker. */
    JOB_WORK,

    /** Any other movement that is not a supply — r.55(1)(c). */
    NOT_SUPPLY,
}

/** One lot of a consignment sent SKD/CKD or in batches (r.55(5)); [index] counts from 1. */
data class Lot(val index: Int, val count: Int) {
    init {
        require(count >= 1 && index in 1..count) { "lot $index of $count" }
    }
}

/**
 * r.138(1) Explanation 2: the section 15 value declared in the invoice, bill
 * of supply or challan, **plus** the taxes and cess charged in it, **minus**
 * exempt supply billed on the same invoice. Not the grand total, and not the
 * taxable value alone. Held in paise so the ₹50,000 boundary compares exactly.
 */
data class ConsignmentValue(
    val declaredPaise: Long,
    val taxPaise: Long = 0,
    val exemptPaise: Long = 0,
) {
    val paise: Long get() = declaredPaise + taxPaise - exemptPaise
}

/** The facts the registry is evaluated against. Unknowns stay null — they are never guessed. */
data class Consignment(
    val category: GoodsCategory,
    val interState: Boolean,
    val value: ConsignmentValue?,
    val reason: MovementReason = MovementReason.SUPPLY,
    val mode: TransportMode = TransportMode.ROAD,
    val supplierRegistered: Boolean = true,
    val recipientRegistered: Boolean = true,
    val imported: Boolean = false,
    val lot: Lot? = null,
    /** Job work only: capital goods rather than inputs — s.143 gives them three years, not one. */
    val capitalGoods: Boolean = false,
    /** Whether the supplier is in the class notified for e-invoicing (r.48(4)); null when not known. */
    val supplierEInvoicing: Boolean? = null,
    val byCommonCarrier: Boolean = false,
    /** Goods found short or damaged against the invoice at receipt. */
    val discrepancyAtReceipt: Boolean = false,
)

/**
 * When a requirement bites. Evaluation is three-valued — `true`, `false`, or
 * `null` when the consignment lacks the fact the test needs — because an
 * unknown value has to surface as "cannot tell yet", never quietly as "not
 * required".
 */
sealed interface Trigger {
    val label: String
    fun test(c: Consignment): Boolean?

    data object Always : Trigger {
        override val label = "every consignment"
        override fun test(c: Consignment) = true
    }

    /** r.138(1) says *exceeding*: a consignment of exactly the threshold is outside. */
    data class ValueExceeds(val paise: Long) : Trigger {
        override val label get() = "consignment value exceeds ${formatRupees(paise)}"
        override fun test(c: Consignment) = c.value?.let { it.paise > paise }
    }

    data class ReasonIs(val reason: MovementReason) : Trigger {
        override val label get() = "movement is ${reason.name.lowercase().replace('_', ' ')}"
        override fun test(c: Consignment) = c.reason == reason
    }

    data class ModeIs(val mode: TransportMode) : Trigger {
        override val label get() = "moving by ${mode.name.lowercase()}"
        override fun test(c: Consignment) = c.mode == mode
    }

    data object InterState : Trigger {
        override val label = "inter-State movement"
        override fun test(c: Consignment) = c.interState
    }

    data object Imported : Trigger {
        override val label = "imported goods"
        override fun test(c: Consignment) = c.imported
    }

    data object UnregisteredToRegistered : Trigger {
        override val label = "unregistered supplier, registered recipient"
        override fun test(c: Consignment) = !c.supplierRegistered && c.recipientRegistered
    }

    data object CapitalGoods : Trigger {
        override val label = "capital goods"
        override fun test(c: Consignment) = c.capitalGoods
    }

    data object SupplierOnEInvoicing : Trigger {
        override val label = "supplier notified for e-invoicing"
        override fun test(c: Consignment) = c.supplierEInvoicing
    }

    data object InLots : Trigger {
        override val label = "sent SKD/CKD or in lots"
        override fun test(c: Consignment) = c.lot != null
    }

    data object FirstLot : Trigger {
        override val label = "first lot"
        override fun test(c: Consignment) = c.lot?.index == 1
    }

    data object LaterLot : Trigger {
        override val label = "a lot after the first"
        override fun test(c: Consignment) = (c.lot?.index ?: 1) > 1
    }

    data object LastLot : Trigger {
        override val label = "last of several lots"
        override fun test(c: Consignment) = c.lot?.let { it.count > 1 && it.index == it.count } ?: false
    }

    data object ByCommonCarrier : Trigger {
        override val label = "handed to a common carrier"
        override fun test(c: Consignment) = c.byCommonCarrier
    }

    data object DiscrepancyAtReceipt : Trigger {
        override val label = "goods short or damaged at receipt"
        override fun test(c: Consignment) = c.discrepancyAtReceipt
    }

    data class Not(val inner: Trigger) : Trigger {
        override val label get() = "not (${inner.label})"
        override fun test(c: Consignment) = inner.test(c)?.not()
    }

    data class AllOf(val parts: List<Trigger>) : Trigger {
        constructor(vararg parts: Trigger) : this(parts.toList())

        override val label get() = parts.joinToString(" and ") { it.label }

        override fun test(c: Consignment): Boolean? {
            var unknown = false
            for (part in parts) {
                when (part.test(c)) {
                    false -> return false
                    null -> unknown = true
                    true -> Unit
                }
            }
            return if (unknown) null else true
        }
    }

    data class AnyOf(val parts: List<Trigger>) : Trigger {
        constructor(vararg parts: Trigger) : this(parts.toList())

        override val label get() = parts.joinToString(" or ") { it.label }

        override fun test(c: Consignment): Boolean? {
            var unknown = false
            for (part in parts) {
                when (part.test(c)) {
                    true -> return true
                    null -> unknown = true
                    false -> Unit
                }
            }
            return if (unknown) null else false
        }
    }
}

data class Requirement(
    val id: String,
    val document: ConsignmentDocument,
    val duty: Duty,
    val holder: Holder,
    val trigger: Trigger,
    val obligation: Obligation,
    /** The rule it rests on. A customary paper may cite one for its clock, never for its existence. */
    val provision: Provision? = null,
    val deadline: Deadline? = null,
    val note: String? = null,
) {
    init {
        require(obligation != Obligation.LEGALLY_REQUIRED || provision != null) {
            "$id: a legal requirement must cite its provision"
        }
        require(deadline == null || provision != null) { "$id: a deadline must rest on a provision" }
    }
}

/** A verification gap that could change an answer; surfaced wherever it touches one. */
data class OpenGap(
    val id: String,
    val question: String,
    /** Ids of the [Requirement]s whose answer this gap could change. */
    val affects: Set<String>,
    /** Only relevant to movement within one State. */
    val intraStateOnly: Boolean = false,
)

/** `₹50,000`, `₹1,00,000` — Indian digit grouping; paise only when present. */
internal fun formatRupees(paise: Long): String {
    val digits = (paise / 100).toString()
    val tail = digits.takeLast(3)
    val head = digits.dropLast(3)
    val grouped = if (head.isEmpty()) tail else head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
    val rest = paise % 100
    return "₹" + grouped + if (rest != 0L) "." + rest.toString().padStart(2, '0') else ""
}
