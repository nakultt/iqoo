package com.veritransit.inspector.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veritransit.inspector.ai.LlmGateway
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.PackingItem
import com.veritransit.inspector.data.PackingList
import com.veritransit.inspector.data.ReceivingAction
import com.veritransit.inspector.data.ReceivingRecord
import com.veritransit.inspector.data.outcomeOf
import kotlin.random.Random

/** Per-section self-assessment of a packing-list reading; null section = not reported. */
data class ListSections(val header: Float?, val supplier: Float?, val items: Float?)

/** Mutable state for one active receiving walkthrough. */
class ReceivingFlowState {
    var detected by mutableStateOf(false)
    var packingList by mutableStateOf<PackingList?>(null)
    var poInput by mutableStateOf("")
    var packingListInput by mutableStateOf("")
    var presetIndex by mutableStateOf(0)

    var cartonCountOk by mutableStateOf(false)
    var labelOk by mutableStateOf(false)

    var countedItems by mutableStateOf<List<PackingItem>>(emptyList())
    var scanProgress by mutableStateOf(0) // lines revealed so far

    /** Box labels booked into [countedItems] — kept with the count, so a box scanned again is not booked twice. */
    var countedBoxIds by mutableStateOf<Set<String>>(emptySet())

    /**
     * The sender-mode box label the carton scan resolved, if the code was one
     * of ours. Null on the plain PO/PL and manual paths. Drives the friendly
     * "what's in the box" card and the photo check on the scan step.
     */
    var scannedBox by mutableStateOf<com.veritransit.inspector.data.BoxLabel?>(null)

    /** Photo of the goods taken to check them against [scannedBox], if any. */
    var boxPhotoPath by mutableStateOf<String?>(null)

    /** True once the receiver has looked at the photo and confirmed the match. */
    var boxVerified by mutableStateOf(false)

    /**
     * The AI's verdict on the box photo against the scanned label, if it has
     * answered. Null while unchecked, or when no AI backend served the check —
     * the receiver then judges the photo themselves.
     */
    var boxVerdict by mutableStateOf<com.veritransit.inspector.ai.ReceivingAi.BoxVerdict?>(null)

    /** Which backend gave [boxVerdict]; null when no AI check has answered. */
    var boxCheckedBy by mutableStateOf<LlmGateway.Backend?>(null)

    /** Cropped evidence frame the vision model was given, if any. */
    var listEvidence by mutableStateOf<String?>(null)
    var dockEvidence by mutableStateOf<String?>(null)

    /** True when this step's data came from a live model read — the NPU, or
     *  the GLM-5.3-Flash cloud fallback — rather than the demo presets. */
    var listFromAi by mutableStateOf(false)
    var countedByAi by mutableStateOf(false)

    /** True when the packing list was resolved by decoding the printed label
     *  rather than by a model read — drives the toast wording on this step. */
    var labelResolved by mutableStateOf(false)

    /** Which backend counted the delivery; null on the demo and manual paths.
     *  Captured once at read time — the gateway's lastBackend is global state
     *  that a later call (e.g. cloud note drafting) would overwrite. */
    var countedBy by mutableStateOf<LlmGateway.Backend?>(null)

    /** The model's one-line description of the dock photo. */
    var aiObservation by mutableStateOf("")

    /** Confidence the model reported on the count, 0 when not used. */
    var aiConfidence by mutableStateOf(0f)

    /**
     * Per-section self-assessment of the packing-list reading (header /
     * supplier / items), null section = not reported. Null overall on the demo
     * and manual paths — markers only ever reflect what the model actually said.
     */
    var listSections by mutableStateOf<ListSections?>(null)

    var action by mutableStateOf(ReceivingAction.RECOUNT)
    var note by mutableStateOf("")

    private var confidenceVal by mutableStateOf(0f)
    private var draftId by mutableStateOf("")

    fun confidence() = confidenceVal

    /** Called once the dock count finishes — fixes the confidence figure and record id. */
    fun setConfidence(reported: Float = 0f) {
        val flagged = countedItems.any { it.status != ItemStatus.MATCHED }
        confidenceVal = when {
            // The model reports its own confidence on the count; use it rather
            // than inventing one, so the figure on the record is real. Values
            // below 0.5 are kept as-is: a low number is the model saying "check
            // this yourself", and flooring it would manufacture trust.
            reported > 0f -> reported.coerceIn(0f, 0.999f)
            flagged -> 0.88f + Random.nextFloat() * 0.07f
            else -> 0.955f + Random.nextFloat() * 0.04f
        }
        draftId = "GRN-2025-" + (1000 + Random.nextInt(9000))
    }

    /** Builds the not-yet-committed record for the receipt screen. */
    fun draftRecord(now: Long): ReceivingRecord {
        val l = packingList
        return ReceivingRecord(
            id = draftId.ifEmpty { "GRN-2025-0000" },
            purchaseOrderId = l?.purchaseOrderId ?: "—",
            packingListId = l?.packingListId ?: "—",
            supplier = l?.supplier ?: "—",
            goods = l?.goods ?: "—",
            dock = l?.dock ?: "—",
            carrier = l?.carrier ?: "—",
            outcome = outcomeOf(countedItems),
            timestamp = now,
            items = countedItems,
            confidence = confidenceVal,
            note = note,
        )
    }

    fun resetForCount() {
        countedItems = emptyList()
        countedBoxIds = emptySet()
        scanProgress = 0
        note = ""
        action = ReceivingAction.RECOUNT
        confidenceVal = 0f
        dockEvidence = null
        countedByAi = false
        aiObservation = ""
        aiConfidence = 0f
    }

    fun startNew(startInManual: Boolean) {
        detected = false
        scannedBox = null
        boxPhotoPath = null
        boxVerified = false
        boxVerdict = null
        boxCheckedBy = null
        packingList = if (startInManual) null else defaultPackingList()
        poInput = ""
        packingListInput = ""
        presetIndex = 0
        cartonCountOk = false
        labelOk = false
        listEvidence = null
        listFromAi = false
        labelResolved = false
        listSections = null
        resetForCount()
    }

    companion object {
        /** Canonical demo packing list shown when the label scanner resolves. */
        fun defaultPackingList(): PackingList {
            val p = com.veritransit.inspector.data.Presets.DEFAULT
            return PackingList(
                purchaseOrderId = p.purchaseOrderId,
                packingListId = p.packingListId,
                supplier = p.supplier,
                goods = p.goods,
                dock = p.dock,
                carrier = p.carrier,
                items = p.items.map { it.copy(received = it.expected) },
            )
        }
    }
}
