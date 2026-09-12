package com.veritransit.inspector.data

/**
 * What the receiver concluded about a delivery as a whole.
 *
 * This app records goods **receiving** for B2B trade — a supplier packs against
 * a purchase order and a packing list, a warehouse receives the shipment and
 * books what actually arrived. It is not a GST, customs or highway-compliance
 * instrument and states no legal position: [ReceiptOutcome] is a stock
 * movement, not a ruling on anything.
 */
enum class ReceiptOutcome { OK, SHORT, OVER, MISMATCH, PENDING }

/**
 * How one packing-list line landed against what was declared.
 *
 * [DAMAGED] is counted separately from a quantity mismatch: the goods arrived
 * in the right number but are not fit to book into stock.
 */
enum class ItemStatus { MATCHED, SHORT, OVER, UNLISTED, DAMAGED }

/**
 * One line of a packing list, with what the dock actually received.
 *
 * [sku] is the key — a declared line is matched by SKU first and by name only
 * as a fallback, so a supplier renaming a description cannot drop a line.
 */
data class PackingItem(
    val sku: String,
    val name: String,
    val detail: String,
    val expected: Int,
    val received: Int,
    val damaged: Int = 0,
) {
    val status: ItemStatus
        get() = when {
            // Goods with no declared line at all: the packing list never
            // mentioned them, so there is no expectation to compare against.
            expected <= 0 && received > 0 -> ItemStatus.UNLISTED
            // Arrived, but not fit to book — a mismatch regardless of count.
            damaged > 0 -> ItemStatus.DAMAGED
            // Received in greater number than packed. Kept as an over, never
            // clamped down to a clean match: the record must show what the dock
            // actually counted.
            received > expected -> ItemStatus.OVER
            received < expected -> ItemStatus.SHORT
            else -> ItemStatus.MATCHED
        }

    /** Signed difference between received and packed, for the short/over chips. */
    val delta: Int get() = received - expected
}

/**
 * One completed receiving record — the goods-received note.
 *
 * [id] is the GRN number. The purchase order and packing list are the
 * identifying references; [dock] and [carrier] are optional logistics metadata
 * about how the shipment arrived, never the identity of the record.
 */
data class ReceivingRecord(
    val id: String,
    val purchaseOrderId: String,
    val packingListId: String,
    val supplier: String,
    val goods: String,
    val dock: String,
    val carrier: String,
    val outcome: ReceiptOutcome,
    val timestamp: Long,
    val items: List<PackingItem> = emptyList(),
    val confidence: Float = 0f,
    val note: String = "",
    val grnFiled: Boolean = false,
) {
    val flagged: Boolean get() = outcome != ReceiptOutcome.OK && outcome != ReceiptOutcome.PENDING
    val discrepancyCount: Int get() = items.count { it.status != ItemStatus.MATCHED }
}

/**
 * The declaration a shipment is received against: a purchase order plus the
 * supplier's packing list.
 */
data class PackingList(
    val purchaseOrderId: String,
    val packingListId: String,
    val supplier: String,
    val goods: String,
    val dock: String,
    val carrier: String,
    val items: List<PackingItem>,
) {
    val totalUnits: Int get() = items.filter { it.expected > 0 }.sumOf { it.expected }
}

/**
 * What the receiver does with the delivery. Plain warehouse actions — accept
 * it into stock, hold it for a supervisor, or ask the dock to re-count.
 */
enum class ReceivingAction(val title: String, val detail: String) {
    ACCEPT("Accept Delivery", "Quantities agree with the packing list — book the goods into stock."),
    FLAG("Flag for Review", "A line does not match the packing list — hold the delivery for a supervisor."),
    RECOUNT("Request Recount", "Ask the dock to re-count the short, over or damaged line."),
    NOT_RECEIVED("Not Received", "Delivery rejected or refused at the dock — return it to the carrier."),
}

/**
 * The outcome implied by the line statuses, so a record and its lines can never
 * disagree. A mismatch outranks a count difference: goods the packing list
 * never declared, or goods that arrived damaged, are the more serious finding.
 */
fun outcomeOf(items: List<PackingItem>): ReceiptOutcome = when {
    items.isEmpty() -> ReceiptOutcome.PENDING
    items.any { it.status == ItemStatus.UNLISTED || it.status == ItemStatus.DAMAGED } -> ReceiptOutcome.MISMATCH
    items.any { it.status == ItemStatus.SHORT } -> ReceiptOutcome.SHORT
    items.any { it.status == ItemStatus.OVER } -> ReceiptOutcome.OVER
    else -> ReceiptOutcome.OK
}
