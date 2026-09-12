package com.veritransit.core

import kotlinx.serialization.Serializable

/**
 * The field/gate inspection vocabulary that already existed in the app's
 * `data/Models.kt`, moved here per §9 Phase 0 so the app, the bot and the
 * backend share one definition instead of three drifting copies.
 *
 * These remain distinct from the package-scanning types above: an inspection is
 * a human judgement about a whole vehicle, while a scan is a machine verdict
 * about one box.
 */

@Serializable
enum class Verdict { PASSED, REVIEW, PENDING }

@Serializable
enum class ItemStatus { MATCHED, SHORTAGE, UNLISTED }

@Serializable
data class CargoItem(
    val name: String,
    val detail: String,
    val expected: Int,
    val found: Int,
) {
    val status: ItemStatus
        get() = when {
            found > expected -> ItemStatus.UNLISTED
            found < expected -> ItemStatus.SHORTAGE
            else -> ItemStatus.MATCHED
        }
}

@Serializable
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

@Serializable
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
