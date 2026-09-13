package com.veritransit.dashboard.documents

import com.veritransit.dashboard.ReceivingRecord

/**
 * The consignment facts the web paperwork page resolves against, pre-filled
 * with whatever the dock receipt vouches for — the goods family its own words
 * name, whether the count found a discrepancy, and the named carrier — and
 * completed from the page's own form.
 *
 * Mirrors the app's `PaperworkFacts` semantics: an unanswered fact stays
 * unknown and the resolver reports "cannot tell yet", never "not required".
 * The [Consignment] built here is only fed the registry; the registry has no
 * trigger that reads the goods category, so [toConsignment] may hold a
 * placeholder category for claim evaluation while the category question is
 * still open. Nothing here reads a model, the network or the wall clock.
 */
data class ConsignmentFacts(
    val category: GoodsCategory?,
    val interState: Boolean?,
    /** r.138(1) value parts in paise; null until the page supplies them. */
    val declaredPaise: Long?,
    val taxPaise: Long?,
    val exemptPaise: Long?,
    val reason: MovementReason = MovementReason.SUPPLY,
    val mode: TransportMode = TransportMode.ROAD,
    val supplierRegistered: Boolean = true,
    val recipientRegistered: Boolean = true,
    val imported: Boolean = false,
    val inLots: Boolean = false,
    val lotIndex: Int = 1,
    val lotCount: Int = 1,
    val capitalGoods: Boolean = false,
    /** Supplier notified for e-invoicing; null when not known. */
    val supplierEInvoicing: Boolean? = null,
    val byCommonCarrier: Boolean = false,
    /** What the dock actually found — the receipt decides this, not the form. */
    val discrepancyAtReceipt: Boolean,
) {

    val value: ConsignmentValue?
        get() = declaredPaise?.let { ConsignmentValue(it, taxPaise ?: 0L, exemptPaise ?: 0L) }

    /**
     * The engine's consignment. The category is only ever a placeholder while
     * unanswered — no trigger reads it — but the full paperwork resolution on
     * the page stays gated until the receiver's office picks the family.
     */
    fun toConsignment(placeholder: GoodsCategory = GoodsCategory.ELECTRONICS): Consignment {
        val count = lotCount.coerceAtLeast(1)
        return Consignment(
            category = category ?: placeholder,
            interState = interState ?: false,
            value = value,
            reason = reason,
            mode = mode,
            supplierRegistered = supplierRegistered,
            recipientRegistered = recipientRegistered,
            imported = imported,
            lot = if (inLots) Lot(lotIndex.coerceIn(1, count), count) else null,
            capitalGoods = capitalGoods,
            supplierEInvoicing = supplierEInvoicing,
            byCommonCarrier = byCommonCarrier,
            discrepancyAtReceipt = discrepancyAtReceipt,
        )
    }

    /** Can the page render a full resolution, or only the gate and the claim card? */
    val resolvable: Boolean get() = category != null && interState != null

    companion object {

        /** What the receipt vouches for; the office answers the rest on the page. */
        fun fromRecord(record: ReceivingRecord): ConsignmentFacts = ConsignmentFacts(
            category = guessCategory(record.supplier + " " + record.goods),
            interState = null,
            declaredPaise = null,
            taxPaise = null,
            exemptPaise = null,
            // A carrier is named on the receipt — goods handed to a carrier, so
            // the Carriage-by-Road claim documents are worth having on the page.
            byCommonCarrier = record.carrier.isNotBlank(),
            discrepancyAtReceipt = record.flagged || record.discrepancyCount > 0,
        )

        /**
         * The goods family a receipt's own words point at, or null when they
         * name neither family — a guess would pick the wrong profile notes.
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

        /** `₹` text to paise: digits with optional comma grouping and ≤2 decimals; anything else is unknown. */
        fun parseRupees(raw: String?): Long? {
            if (raw == null) return null
            val cleaned = raw.replace("₹", "").replace(",", "").replace(" ", "").trim()
            if (cleaned.isEmpty()) return null
            val whole = cleaned.substringBefore('.').ifEmpty { "0" }
            val frac = cleaned.substringAfter('.', "")
            if (!whole.all(Char::isDigit) || (!frac.isEmpty() && !frac.all(Char::isDigit))) return null
            if (frac.length > 2) return null
            val paise = whole.toLongOrNull() ?: return null
            return paise * 100 + when (frac.length) {
                1 -> frac.toLong() * 10
                2 -> frac.toLong()
                else -> 0L
            }
        }

        /** Back the other way, for pre-filling the form: null → blank, whole rupees stay whole. */
        fun paiseToRupeesText(paise: Long?): String = when {
            paise == null -> ""
            paise % 100 == 0L -> (paise / 100).toString()
            else -> String.format(java.util.Locale.ROOT, "%.2f", paise / 100.0)
        }

        /** Form overrides on top of the receipt-derived defaults; unknown names keep the default. */
        fun fromRecord(record: ReceivingRecord, query: Map<String, String>): ConsignmentFacts {
            val base = fromRecord(record)
            // A GET form cannot distinguish "first view" from "submitted with the
            // boxes unchecked" — the hidden `submitted` field can. Without it,
            // every absent name means "use what the receipt implied".
            val submitted = query.containsKey("submitted")
            fun flag(name: String, current: Boolean): Boolean = when {
                !submitted -> current
                query[name] == null -> false
                else -> query[name] == "on" || query[name] == "true" || query[name] == "1"
            }
            fun choice(name: String, on: (String) -> Boolean?, current: Boolean?): Boolean? = when {
                !submitted -> current
                query[name] != null && on(query[name]!!) != null -> on(query[name]!!)
                else -> null
            }
            return base.copy(
                category = when {
                    !submitted -> base.category
                    query["category"] == "electronics" -> GoodsCategory.ELECTRONICS
                    query["category"] == "fabric" -> GoodsCategory.FABRIC
                    // The office explicitly declines the receipt's guess…
                    query["category"] == "" -> null
                    // …but an absent answer (a direct link, no form) keeps it.
                    else -> base.category
                },
                interState = choice("inter", { it -> when (it) { "true" -> true; "false" -> false; else -> null } }, base.interState),
                declaredPaise = if (submitted) parseRupees(query["declared"]) else base.declaredPaise,
                taxPaise = if (submitted) parseRupees(query["tax"]) else base.taxPaise,
                exemptPaise = if (submitted) parseRupees(query["exempt"]) else base.exemptPaise,
                reason = MovementReason.entries.firstOrNull { it.name.equals(query["reason"], ignoreCase = true) }
                    ?: if (submitted) MovementReason.SUPPLY else base.reason,
                mode = TransportMode.entries.firstOrNull { it.name.equals(query["mode"], ignoreCase = true) }
                    ?: if (submitted) TransportMode.ROAD else base.mode,
                supplierRegistered = flag("supplier_reg", base.supplierRegistered),
                recipientRegistered = flag("recipient_reg", base.recipientRegistered),
                imported = flag("imported", base.imported),
                inLots = flag("in_lots", base.inLots),
                lotIndex = query["lot_index"]?.toIntOrNull() ?: base.lotIndex,
                lotCount = (query["lot_count"]?.toIntOrNull() ?: base.lotCount).coerceAtLeast(1),
                capitalGoods = flag("capital_goods", base.capitalGoods),
                supplierEInvoicing = choice(
                    "einvoice",
                    { it -> when (it) { "true" -> true; "false" -> false; else -> null } },
                    base.supplierEInvoicing,
                ),
                byCommonCarrier = flag("common_carrier", base.byCommonCarrier),
            )
        }
    }
}
