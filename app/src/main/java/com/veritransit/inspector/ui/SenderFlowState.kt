package com.veritransit.inspector.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.veritransit.inspector.data.BoxLabel
import com.veritransit.inspector.data.BoxLine
import com.veritransit.inspector.data.BoxPacking
import com.veritransit.inspector.data.MasterBox
import com.veritransit.inspector.data.PackingListPreset
import com.veritransit.inspector.data.Presets
import com.veritransit.inspector.data.Repo

/**
 * Which side of a delivery the handset is working. Receiving counts a delivery
 * in against its packing list; sending packs one out — a master box, the boxes
 * inside it, and a QR label for every one of them.
 */
enum class AppMode(val label: String) {
    RECEIVING("Receiving"),
    SENDING("Sending"),
}

/** Mutable state for one sender walkthrough: pick the list, plan the boxes, print their labels. */
class SenderFlowState {

    /** The packing list being packed, as an index into [Presets.ALL]. */
    var presetIndex by mutableStateOf(0)
        private set

    var masterId by mutableStateOf("")
        private set

    var boxCount by mutableStateOf(1)
        private set

    /** What each box holds, box by box; planned when the contents step opens. */
    var contents by mutableStateOf<List<List<BoxLine>>>(emptyList())
        private set

    /** True once the sender typed an ID of their own — picking another list then keeps it. */
    private var masterIdTyped = false

    /** The (list, box count) [contents] was planned for; a change to either re-plans the boxes. */
    private var plannedFor: Pair<Int, Int>? = null

    /**
     * The ID this walkthrough last saved to the packed list. Saving again
     * replaces that entry, so the sender's own ID never reads as taken.
     */
    private var savedId: String? = null

    val preset: PackingListPreset get() = Presets.ALL[presetIndex]

    /** Every box has to hold something, so there are never more boxes than units to pack. */
    val maxBoxes: Int
        get() = BoxPacking.packable(preset.items).sumOf { it.qty }.coerceIn(1, BoxLabel.MAX_BOXES)

    /** Why [masterId] cannot go on a label, or null when it can. */
    val masterIdProblem: String?
        get() = when {
            masterId.isEmpty() -> "Enter the master box ID"
            !BoxPacking.isValidMasterId(masterId) ->
                "Letters, digits and hyphens only — starting and ending with a letter or digit"
            masterId in taken() -> "$masterId is already on a packed master box"
            else -> null
        }

    fun startNew() {
        presetIndex = 0
        masterIdTyped = false
        savedId = null
        contents = emptyList()
        plannedFor = null
        boxCount = BoxPacking.packable(preset.items).size.coerceIn(1, maxBoxes)
        masterId = suggestId()
    }

    fun selectPreset(index: Int) {
        presetIndex = index.coerceIn(Presets.ALL.indices)
        boxCount = boxCount.coerceIn(1, maxBoxes)
        if (!masterIdTyped) masterId = suggestId()
    }

    fun editMasterId(raw: String) {
        masterId = raw.uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            .take(BoxPacking.MAX_MASTER_ID)
        masterIdTyped = true
    }

    fun changeBoxCount(count: Int) {
        boxCount = count.coerceIn(1, maxBoxes)
    }

    /** Plans the boxes, unless they are already planned for this list and this count. */
    fun planIfNeeded() {
        if (plannedFor != presetIndex to boxCount) replan()
    }

    /** Drops any hand edits and splits the packing list across the boxes afresh. */
    fun replan() {
        contents = BoxPacking.distribute(preset.items, boxCount)
        plannedFor = presetIndex to boxCount
    }

    fun adjust(box: Int, sku: String, delta: Int) {
        contents = BoxPacking.adjust(preset.items, contents, box, sku, delta)
    }

    /** The master box as planned — throws if the ID or a box could not go on a label. */
    fun build(now: Long): MasterBox = BoxPacking.build(masterId, preset, contents, now)

    /** Records [box] in the packed list; saving it again replaces the earlier entry. */
    fun save(box: MasterBox) {
        Repo.saveMasterBox(box)
        savedId = box.id
    }

    private fun taken(): List<String> = Repo.masterBoxes.map { it.id }.filter { it != savedId }

    private fun suggestId() = BoxPacking.suggestMasterId(preset.purchaseOrderId, taken())
}
