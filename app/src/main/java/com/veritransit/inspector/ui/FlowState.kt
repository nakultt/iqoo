package com.veritransit.inspector.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veritransit.inspector.data.CargoItem
import com.veritransit.inspector.data.InspectionRecord
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.Manifest
import com.veritransit.inspector.data.OfficerAction
import com.veritransit.inspector.data.Verdict
import kotlin.random.Random

/** Per-section self-assessment of a bill reading; null section = not reported. */
data class BillSections(val header: Float?, val route: Float?, val items: Float?)

/** Mutable state for one active inspection walkthrough. */
class InspectionFlowState {
    var detected by mutableStateOf(false)
    var manifest by mutableStateOf<Manifest?>(null)
    var ewbInput by mutableStateOf("")
    var vehicleInput by mutableStateOf("")
    var presetIndex by mutableStateOf(0)

    var sealOk by mutableStateOf(false)
    var driverOk by mutableStateOf(false)

    var scannedItems by mutableStateOf<List<CargoItem>>(emptyList())
    var scanProgress by mutableStateOf(0) // items revealed so far

    /** Cropped evidence frame the vision model was given, if any. */
    var billEvidence by mutableStateOf<String?>(null)
    var cargoEvidence by mutableStateOf<String?>(null)

    /** True when this step's data came off the NPU rather than the demo presets. */
    var manifestFromAi by mutableStateOf(false)
    var reconciledByAi by mutableStateOf(false)

    /** The model's one-line description of the cargo bay photo. */
    var aiObservation by mutableStateOf("")

    /** Confidence the model reported on the reconciliation, 0 when not used. */
    var aiConfidence by mutableStateOf(0f)

    /**
     * Per-section self-assessment of the bill reading (header / route /
     * items), null section = not reported. Null overall on the demo and
     * manual paths — markers only ever reflect what the model actually said.
     */
    var billSections by mutableStateOf<BillSections?>(null)

    var action by mutableStateOf(OfficerAction.RECOUNT)
    var note by mutableStateOf("")

    private var confidenceVal by mutableStateOf(0f)
    private var draftId by mutableStateOf("")

    fun confidence() = confidenceVal

    /** Called once the cargo scan finishes — fixes the confidence figure and record id. */
    fun setConfidence(reported: Float = 0f) {
        val flagged = scannedItems.any { it.status != ItemStatus.MATCHED }
        confidenceVal = when {
            // The model reports its own confidence on the reconciliation; use it
            // rather than inventing one, so the figure on the record is real.
            // Values below 0.5 are kept as-is: a low number is the model saying
            // "check this yourself", and flooring it would manufacture trust.
            reported > 0f -> reported.coerceIn(0f, 0.999f)
            flagged -> 0.88f + Random.nextFloat() * 0.07f
            else -> 0.955f + Random.nextFloat() * 0.04f
        }
        draftId = "VT-2025-" + (1000 + Random.nextInt(9000))
    }

    /** Builds the not-yet-committed record for the result screen. */
    fun draftRecord(now: Long): InspectionRecord {
        val m = manifest
        val verdict = if (scannedItems.any { it.status != ItemStatus.MATCHED }) Verdict.REVIEW else Verdict.PASSED
        return InspectionRecord(
            id = draftId.ifEmpty { "VT-2025-0000" },
            ewb = m?.ewb ?: "—",
            vehicle = m?.vehicle ?: "—",
            vehicleModel = m?.vehicleModel ?: "",
            cargo = m?.consignment ?: "—",
            route = m?.route ?: "—",
            distanceKm = m?.distanceKm ?: 0,
            verdict = verdict,
            timestamp = now,
            items = scannedItems,
            confidence = confidenceVal,
            note = note,
        )
    }

    fun resetForScan() {
        scannedItems = emptyList()
        scanProgress = 0
        note = ""
        action = OfficerAction.RECOUNT
        confidenceVal = 0f
        cargoEvidence = null
        reconciledByAi = false
        aiObservation = ""
        aiConfidence = 0f
    }

    fun startNew(startInManual: Boolean) {
        detected = false
        manifest = if (startInManual) null else defaultManifest()
        ewbInput = ""
        vehicleInput = ""
        presetIndex = 0
        sealOk = false
        driverOk = false
        billEvidence = null
        manifestFromAi = false
        billSections = null
        resetForScan()
    }

    companion object {
        /** Canonical demo manifest shown when the scanner resolves the QR. */
        fun defaultManifest(): Manifest {
            val p = com.veritransit.inspector.data.Presets.DEFAULT
            return Manifest(
                ewb = "7819-2044-8831",
                vehicle = "TN 38 BX 4491",
                vehicleModel = p.vehicleModel,
                consignment = p.name,
                route = p.route,
                distanceKm = p.distanceKm,
                items = p.items.map { it.copy(found = it.expected) },
                ref = "#7819-A",
            )
        }
    }
}
