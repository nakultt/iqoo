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

    var action by mutableStateOf(OfficerAction.RECOUNT)
    var note by mutableStateOf("")

    private var confidenceVal by mutableStateOf(0f)
    private var draftId by mutableStateOf("")

    fun confidence() = confidenceVal

    /** Called once the cargo scan finishes — fixes the confidence figure and record id. */
    fun setConfidence() {
        val flagged = scannedItems.any { it.status != ItemStatus.MATCHED }
        confidenceVal = if (flagged) 0.88f + Random.nextFloat() * 0.07f else 0.955f + Random.nextFloat() * 0.04f
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
    }

    fun startNew(startInManual: Boolean) {
        detected = false
        manifest = if (startInManual) null else defaultManifest()
        ewbInput = ""
        vehicleInput = ""
        presetIndex = 0
        sealOk = false
        driverOk = false
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
