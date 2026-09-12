package com.veritransit.inspector.data

/**
 * What a scanned packing-list / carton-label code actually yielded.
 *
 * [purchaseOrderId] is the PO number printed on the label (`PO-2025-4471`);
 * [packingListId] the packing-list reference if the payload carried one. Both
 * are null when the code was not shipment-shaped — scanning a random QR should
 * still lock the frame honestly rather than invent fields, and the packing-list
 * step exists to correct whatever the code did say.
 */
data class QrLabelFields(
    val purchaseOrderId: String?,
    val packingListId: String?,
    val raw: String,
)

/**
 * Pure extraction of shipment references out of a decoded QR/barcode payload.
 *
 * Supplier labels are not one fixed format: the printout carries anything from
 * a bare PO number to JSON with packing-list and SKU fields. Rather than parse
 * one vendor shape, this pulls out the two references the packing-list step
 * needs — a `PO-…`-shaped token and a `PL-…`-shaped one — and leaves everything
 * else to the receiver.
 *
 * Internal so the JVM test suite can exercise the exact path the scanner takes
 * on device.
 */
object QrLabel {

    /**
     * What a scanned code cannot tell the app (#27). [parse] takes a PO and a
     * packing-list reference and nothing else, and even a signed e-invoice QR
     * carries header fields only — GSTINs, document number and date, total
     * value, the *count* of lines, the main HSN, the IRN — never the goods
     * lines. Shown wherever a label scan resolved the packing list, so the
     * preset lines beside it are never mistaken for something the code said.
     */
    const val HEADERS_ONLY =
        "The label gave references only — never the goods lines. Confirm each packed line against the paperwork."

    /**
     * Purchase-order reference: `PO-2025-4471`, `PO20254471`, or a bare
     * `PO` followed by at least four digits. Boundaries keep it from matching
     * inside a longer alphanumeric blob.
     */
    private val PO = Regex("(?<![A-Z0-9])PO[\\s\\-_]?([0-9]{4})[\\s\\-_]?([0-9]{2,6})(?![0-9])")

    /** Packing-list reference printed beside the PO: `PL-2025-4471-A`. */
    private val PL = Regex("(?<![A-Z0-9])PL[\\s\\-_]?([0-9]{4})[\\s\\-_]?([0-9]{2,6})(?:[\\s\\-_]?([A-Z]))?(?![0-9A-Z])")

    fun parse(payload: String): QrLabelFields {
        val upper = payload.uppercase()
        return QrLabelFields(
            purchaseOrderId = PO.find(upper)?.let { format("PO", it) },
            packingListId = PL.find(upper)?.let { format("PL", it) },
            raw = payload,
        )
    }

    /**
     * `PO-2025-4471` / `PL-2025-4471-A` — the grouping the rest of the app
     * prints. The revision suffix is optional and only the PL pattern captures
     * it, so the tail group is read defensively: the PO pattern has no third
     * group, and reaching for it unconditionally threw on every PO-shaped
     * label.
     */
    private fun format(prefix: String, m: MatchResult): String {
        val tail = m.groupValues.getOrNull(3)
            ?.takeIf { it.isNotBlank() }
            ?.let { "-$it" }
            ?: ""
        return "$prefix-${m.groupValues[1]}-${m.groupValues[2]}$tail"
    }
}
