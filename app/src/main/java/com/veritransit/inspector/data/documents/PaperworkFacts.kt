package com.veritransit.inspector.data.documents

import com.veritransit.inspector.data.ReceivingRecord

/**
 * The facts the paperwork screen asks about, pre-filled with whatever the
 * filed receipt already knows.
 *
 * Everything the receipt cannot know stays unset — a blank value field, an
 * unanswered inter-State question — and [toConsignment] carries it into the
 * engine as null or a gate, so `ConsignmentPaperwork` reports "cannot tell
 * yet" instead of quietly guessing. [discrepancyAtReceipt] is the one fact the
 * receipt decides on its own: it is what the dock actually found.
 */
data class PaperworkFacts(
    /** Which goods profile applies; null until the receiver picks one. */
    val category: GoodsCategory?,
    /** Crossed a State border? The one fact the engine cannot default. */
    val interState: Boolean?,
    /** r.138(1) value fields, as typed — blank means unknown, never zero. */
    val declaredRupees: String = "",
    val taxRupees: String = "",
    val exemptRupees: String = "",
    val reason: MovementReason = MovementReason.SUPPLY,
    val mode: TransportMode = TransportMode.ROAD,
    val supplierRegistered: Boolean = true,
    val recipientRegistered: Boolean = true,
    val imported: Boolean = false,
    val inLots: Boolean = false,
    val lotIndex: Int = 1,
    val lotCount: Int = 1,
    /** Job work only: capital goods rather than inputs. */
    val capitalGoods: Boolean = false,
    /** Supplier notified for e-invoicing; null when not known. */
    val supplierEInvoicing: Boolean? = null,
    val byCommonCarrier: Boolean = false,
    /** Derived from the receipt, not asked: goods found short or damaged. */
    val discrepancyAtReceipt: Boolean,
) {

    /** The r.138(1) value, or null while the declared field is blank or malformed. */
    fun toValue(): ConsignmentValue? {
        val declared = parseRupees(declaredRupees) ?: return null
        return ConsignmentValue(
            declaredPaise = declared,
            taxPaise = parseRupees(taxRupees) ?: 0L,
            exemptPaise = parseRupees(exemptRupees) ?: 0L,
        )
    }

    /**
     * The engine's consignment, or null while a fact the engine cannot default
     * is missing (the goods category, or whether the movement crossed a State
     * border). Everything else has an honest unset: blank value, unknown
     * e-invoicing status.
     */
    fun toConsignment(): Consignment? {
        val cat = category ?: return null
        val inter = interState ?: return null
        return Consignment(
            category = cat,
            interState = inter,
            value = toValue(),
            reason = reason,
            mode = mode,
            supplierRegistered = supplierRegistered,
            recipientRegistered = recipientRegistered,
            imported = imported,
            lot = if (inLots) {
                val count = lotCount.coerceAtLeast(1)
                Lot(index = lotIndex.coerceIn(1, count), count = count)
            } else {
                null
            },
            capitalGoods = capitalGoods,
            supplierEInvoicing = supplierEInvoicing,
            byCommonCarrier = byCommonCarrier,
            discrepancyAtReceipt = discrepancyAtReceipt,
        )
    }

    companion object {

        /** What the receipt vouches for; everything else starts unset. */
        fun from(record: ReceivingRecord): PaperworkFacts = PaperworkFacts(
            category = guessCategory(record.supplier + " " + record.goods),
            interState = null,
            discrepancyAtReceipt = record.flagged || record.discrepancyCount > 0,
        )

        /**
         * The goods profile a receipt's own words point at, or null when the
         * receipt names neither family — a guess would pick the wrong notes.
         */
        fun guessCategory(text: String): GoodsCategory? {
            val t = text.lowercase()
            val electronics = listOf(
                "electronic", "smartphone", "tablet", "monitor", "laptop", "computer",
                "device", "appliance", "circuit", "component", "semiconductor",
            )
            val fabric = listOf(
                "fabric", "textile", "weav", "apparel", "garment", "clothing",
                "yarn", "loom", "dyeing", "fabric processing",
            )
            return when {
                electronics.any { it in t } -> GoodsCategory.ELECTRONICS
                fabric.any { it in t } -> GoodsCategory.FABRIC
                else -> null
            }
        }

        /**
         * `₹` text to paise: digits with optional comma grouping and up to two
         * decimals. Anything else — negative, blank, words — is unknown.
         */
        fun parseRupees(raw: String): Long? {
            val cleaned = raw.replace("₹", "").replace(",", "").replace(" ", "").trim()
            if (cleaned.isEmpty()) return null
            val whole = cleaned.substringBefore('.').ifEmpty { "0" }
            val frac = cleaned.substringAfter('.', "")
            if (!whole.all(Char::isDigit) || !frac.all(Char::isDigit)) return null
            if (frac.length > 2) return null
            val paise = whole.toLongOrNull() ?: return null
            return paise * 100 + when (frac.length) {
                1 -> frac.toLong() * 10
                2 -> frac.toLong()
                else -> 0L
            }
        }
    }
}
