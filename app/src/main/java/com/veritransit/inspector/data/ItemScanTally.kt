package com.veritransit.inspector.data

/**
 * The receiver's live dock count: item codes decoded by the camera, plus
 * corrections by hand. Pure logic, so the JVM tests exercise exactly what the
 * item scanner does on device.
 *
 * A camera decodes the same code on every frame it stays in view, so a code
 * counts once per presentation: it counts again only after it has been out of
 * sight for [rearmMs]. Different codes in the same frame each count once.
 */
class ItemScanTally(
    packed: List<PackingItem>,
    private val rearmMs: Long = REARM_MS,
) {
    sealed interface Outcome {
        /** One more unit of [line]'s SKU received; [line] carries the new count. */
        data class Counted(val line: PackingItem) : Outcome

        /** Already counted, and still in front of the camera. */
        data object StillInView : Outcome

        /** The code names no SKU on this packing list. */
        data class NotOnList(val code: String) : Outcome
    }

    /** The packed lines with what has been received so far — nothing, to start. */
    var lines: List<PackingItem> = packed.map { it.copy(received = 0, damaged = 0) }
        private set

    val unitsReceived: Int get() = lines.sumOf { it.received }
    val unitsExpected: Int get() = lines.filter { it.expected > 0 }.sumOf { it.expected }

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
        val upper = code.uppercase()
        val sku = skuPatterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(upper) }?.first
            ?: return Outcome.NotOnList(code)
        if (stillInView) return Outcome.StillInView
        return Outcome.Counted(bump(sku, 1))
    }

    /** A hand correction of [delta] units; null when [sku] is not on the list. */
    fun adjust(sku: String, delta: Int): PackingItem? =
        if (lines.any { it.sku == sku }) bump(sku, delta) else null

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
    }
}
