package com.veritransit.inspector.bot

/** Domain model shared with the inspector app's data layer (kept dependency-free for the JVM bot). */

enum class Verdict { PASSED, REVIEW, PENDING }

enum class ItemStatus { MATCHED, SHORTAGE, OVERAGE, UNLISTED }

data class CargoItem(
    val name: String,
    val detail: String,
    val expected: Int,
    val found: Int,
) {
    val status: ItemStatus
        get() = when {
            // Mirrors the app's data layer: an extra with no declared line is
            // unlisted; a declared item found in greater number is an overage.
            expected <= 0 && found > 0 -> ItemStatus.UNLISTED
            found > expected -> ItemStatus.OVERAGE
            found < expected -> ItemStatus.SHORTAGE
            else -> ItemStatus.MATCHED
        }
}

data class InspectionRecord(
    val id: String,
    val ewb: String,
    val vehicle: String,
    val vehicleModel: String,
    val cargo: String,
    val route: String,
    val distanceKm: Int,
    val verdict: Verdict,
    val timestamp: Long,
    val items: List<CargoItem> = emptyList(),
    val confidence: Float = 0f,
    val note: String = "",
) {
    val flagged: Boolean get() = verdict == Verdict.REVIEW
    val discrepancyCount: Int get() = items.count { it.status != ItemStatus.MATCHED }
}

enum class OfficerAction(val title: String, val detail: String) {
    CLEAR("Clear & Release", "Goods comply with statutory trade thresholds."),
    RECOUNT("Issue Physical Re-count Notice", "Direct truck to physical ramp for manual unboxing."),
    DETAIN("Detain Cargo", "Hold consignment under custody for valuation check."),
}
