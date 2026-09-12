package com.veritransit.inspector.data

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
            // An extra with no declared line carries no expectation to exceed:
            // it is goods the manifest never mentioned at all.
            expected <= 0 && found > 0 -> ItemStatus.UNLISTED
            // Declared but found in greater number — an overage is a manifest
            // mismatch exactly like a shortage, and must survive to the record.
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
    val noticeIssued: Boolean = false,
) {
    val flagged: Boolean get() = verdict == Verdict.REVIEW
    val discrepancyCount: Int get() = items.count { it.status != ItemStatus.MATCHED }
}

data class Manifest(
    val ewb: String,
    val vehicle: String,
    val vehicleModel: String,
    val consignment: String,
    val route: String,
    val distanceKm: Int,
    val items: List<CargoItem>,
    val ref: String,
) {
    val totalUnits: Int get() = items.filter { it.expected > 0 }.sumOf { it.expected }
}

enum class OfficerAction(val title: String, val detail: String) {
    CLEAR("Clear & Release", "Goods comply with statutory trade thresholds."),
    RECOUNT("Issue Physical Re-count Notice", "Direct truck to physical ramp for manual unboxing."),
    DETAIN("Detain Cargo", "Hold consignment under custody for valuation check."),
}
