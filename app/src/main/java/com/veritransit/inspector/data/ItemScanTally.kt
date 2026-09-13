package com.veritransit.inspector.data

/**
 * The receiver's live dock count: item codes decoded by the camera, plus
 * corrections by hand. Pure logic, so the JVM tests exercise exactly what the
 * item scanner does on device.
 *
 * A camera decodes the same code on every frame it stays in view, so a code
 * counts once per presentation: it counts again only after it has been out of
 * sight for [rearmMs]. Different codes in the same frame each count once.
 *
 * A sender-mode box label ([BoxLabel]) counts differently: one scan books
 * every line the box declares, and each box counts once in all — its ID is
 * unique, so seeing it again is the same box, not another one.
 */
class ItemScanTally(
    packed: List<PackingItem>,
    private val rearmMs: Long = REARM_MS,
    /** The delivery's PO: a box label naming another PO counts nothing. Null accepts any. */
    purchaseOrderId: String? = null,
    /** Boxes already in this count, restored when the receiver comes back to it. */
    countedBoxes: Set<String> = emptySet(),
) {
    sealed interface Outcome {
        /** One more unit of [line]'s SKU received; [line] carries the new count. */
        data class Counted(val line: PackingItem) : Outcome

        /** Already counted, and still in front of the camera. */
        data object StillInView : Outcome

        /** The code names no SKU on this packing list. */
        data class NotOnList(val code: String) : Outcome

        /**
         * A box label: every line [box] declares booked at once, as labelled.
         * [lines] are the packing-list lines it moved, with their new counts;
         * [notOnList] are declared lines this packing list has no SKU for.
         */
        data class BoxCounted(
            val box: BoxLabel.Inner,
            val lines: List<PackingItem>,
            val notOnList: List<BoxLine>,
        ) : Outcome

        /** [box] is already in the count. */
        data class BoxAlreadyCounted(val box: BoxLabel.Inner) : Outcome

        /** A master box's own label: it names the boxes inside and counts nothing itself. */
        data class MasterLabel(val master: BoxLabel.Master) : Outcome

        /** A box label for another purchase order. */
        data class OtherDelivery(val label: BoxLabel) : Outcome
    }

    /** The packed lines with what has been received so far — nothing, to start. */
    var lines: List<PackingItem> = packed.map { it.copy(received = 0, damaged = 0) }
        private set

    val unitsReceived: Int get() = lines.sumOf { it.received }
    val unitsExpected: Int get() = lines.filter { it.expected > 0 }.sumOf { it.expected }

    private val deliveryPo = purchaseOrderId?.let { reference(it) }?.takeIf { it.isNotEmpty() }
    private val boxesCounted = countedBoxes.toMutableSet()

    /** IDs of the boxes counted so far. */
    val countedBoxIds: Set<String> get() = boxesCounted.toSet()

    /** Longest SKU first, so a short SKU never claims a scan of a longer one. */
    private val skuPatterns: List<Pair<String, Regex>> = lines
        .map { it.sku }
        .filter { sku -> sku.any { it.isLetterOrDigit() } }
        .distinct()
        .sortedByDescending { sku -> sku.count { it.isLetterOrDigit() } }
        .map { it to skuPattern(it) }

    /** When each code was last in view; entries lapse after [rearmMs]. */
    private val lastSeen = HashMap<String, Long>()

    fun onCode(raw: String, nowMs: Long): Outcome {
        val code = raw.trim()
        lastSeen.entries.removeAll { nowMs - it.value >= rearmMs }
        val stillInView = code in lastSeen
        lastSeen[code] = nowMs
        BoxLabel.parse(code)?.let { label ->
            return if (stillInView) Outcome.StillInView else onBoxLabel(label)
        }
        val upper = code.uppercase()
        val sku = skuPatterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(upper) }?.first
            ?: return Outcome.NotOnList(code)
        if (stillInView) return Outcome.StillInView
        return Outcome.Counted(bump(sku, 1))
    }

    /** A hand correction of [delta] units; null when [sku] is not on the list. */
    fun adjust(sku: String, delta: Int): PackingItem? =
        if (lines.any { it.sku == sku }) bump(sku, delta) else null

    private fun onBoxLabel(label: BoxLabel): Outcome {
        if (deliveryPo != null && reference(label.purchaseOrderId) != deliveryPo) {
            return Outcome.OtherDelivery(label)
        }
        return when (label) {
            is BoxLabel.Master -> Outcome.MasterLabel(label)
            is BoxLabel.Inner -> {
                if (!boxesCounted.add(label.id)) return Outcome.BoxAlreadyCounted(label)
                val moved = mutableListOf<PackingItem>()
                val notOnList = mutableListOf<BoxLine>()
                for (declared in label.lines) {
                    val sku = lines
                        .firstOrNull { it.sku.isNotBlank() && reference(it.sku) == reference(declared.sku) }
                        ?.sku
                    if (sku == null) notOnList += declared else moved += bump(sku, declared.qty)
                }
                Outcome.BoxCounted(label, moved, notOnList)
            }
        }
    }

    private fun bump(sku: String, delta: Int): PackingItem {
        val i = lines.indexOfFirst { it.sku == sku }
        val line = lines[i].copy(received = (lines[i].received + delta).coerceAtLeast(0))
        lines = lines.toMutableList().also { it[i] = line }
        return line
    }

    companion object {
        /** Long enough to set one carton down and bring the next into view. */
        const val REARM_MS = 1_000L

        /**
         * Labels print a SKU every which way — `ELC-2710`, `elc 2710`,
         * `"sku":"ELC2710"` — so separators inside it are optional, but the SKU
         * must stand alone: `ELC-27105` is not `ELC-2710`.
         */
        private fun skuPattern(sku: String): Regex {
            val body = sku.uppercase()
                .filter { it.isLetterOrDigit() }
                .map { it.toString() }
                .joinToString("[\\s\\-_./]*")
            return Regex("(?<![A-Z0-9])$body(?![A-Z0-9])")
        }

        /** `PO-2025-4471`, `po 2025 4471` and `PO20254471` are one reference. */
        private fun reference(s: String) = s.uppercase().filter { it.isLetterOrDigit() }
    }
}
