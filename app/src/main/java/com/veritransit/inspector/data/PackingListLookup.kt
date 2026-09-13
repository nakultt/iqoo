package com.veritransit.inspector.data

/**
 * The packing list a scanned carton label opens, or null when this dock has
 * none for it.
 *
 * A label may print the PO, the packing-list reference, or both. The PO
 * decides when it is there: an unknown PO opens nothing, even beside a known
 * packing-list reference, because a label that disagrees with itself should
 * not quietly pick a side. A packing-list reference printed without its
 * revision letter still finds its list.
 */
fun Presets.forLabel(fields: QrLabelFields): PackingListPreset? {
    fields.purchaseOrderId?.let { po ->
        return ALL.firstOrNull { it.purchaseOrderId.equals(po, ignoreCase = true) }
    }
    val pl = fields.packingListId ?: return null
    return ALL.firstOrNull { it.packingListId.equals(pl, ignoreCase = true) }
        ?: ALL.firstOrNull { it.packingListId.startsWith("$pl-", ignoreCase = true) }
}
