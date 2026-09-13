package com.veritransit.inspector.data

/** [qty] units of [sku] packed into one box. */
data class BoxLine(val sku: String, val qty: Int)

/**
 * The text inside the QR code on a master box and on every box packed inside
 * it — written by sender mode, read back by the receiving side.
 *
 * One payload serves both receiving readers:
 * - **Label scan** ([QrLabel]) looks for `PO-…` / `PL-…` tokens. Both
 *   references come first, so a master box *or any box inside it* opens its
 *   packing list, and a typed master-box ID can never be read as the PO.
 * - **Dock count** ([ItemScanTally]) reads a box's declared lines, so one scan
 *   books the whole box as labelled, and the box ID keeps it to one count.
 *
 * The text keeps to the QR alphanumeric set — A–Z, 0–9, space and `$%*+-./:`
 * — which packs 5.5 bits a character instead of 8: the same label fits a
 * smaller symbol with coarser modules, which reads from further off and
 * through a scuffed carton. This is the app's own format; it is not a GS1
 * label and the IDs are not SSCCs.
 *
 * ```
 * VTBOX1 MASTER PO:PO-2025-4471 PL:PL-2025-4471-A ID:MB-4471-01 BOXES:2 UNITS:7
 * VTBOX1 BOX PO:PO-2025-4471 PL:PL-2025-4471-A ID:MB-4471-01-B02 IN:MB-4471-01 NO:2/2 ITEMS:ELC-1180*4
 * ```
 */
sealed interface BoxLabel {
    val purchaseOrderId: String
    val packingListId: String
    val id: String

    /** The text to encode in the QR code. */
    fun payload(): String

    /** The outer box: [boxCount] boxes inside it, [units] units across them. */
    data class Master(
        override val purchaseOrderId: String,
        override val packingListId: String,
        override val id: String,
        val boxCount: Int,
        val units: Int,
    ) : BoxLabel {
        init {
            requireTokens(purchaseOrderId, packingListId, id)
            require(boxCount in 1..MAX_BOXES) { "$boxCount boxes" }
            require(units >= 1) { "$units units" }
        }

        override fun payload() =
            "$PREFIX $MASTER PO:$purchaseOrderId PL:$packingListId ID:$id BOXES:$boxCount UNITS:$units"
    }

    /** Box [seq] of the [of] boxes inside master box [masterId], holding [lines]. */
    data class Inner(
        override val purchaseOrderId: String,
        override val packingListId: String,
        override val id: String,
        val masterId: String,
        val seq: Int,
        val of: Int,
        val lines: List<BoxLine>,
    ) : BoxLabel {
        init {
            requireTokens(purchaseOrderId, packingListId, id, masterId)
            require(of in 1..MAX_BOXES && seq in 1..of) { "box $seq of $of" }
            require(lines.isNotEmpty()) { "box $id declares nothing" }
            lines.forEach { require(isToken(it.sku) && it.qty in 1..MAX_QTY) { "line ${it.sku} × ${it.qty}" } }
            require(lines.distinctBy { it.sku }.size == lines.size) { "box $id lists a SKU twice" }
        }

        val units: Int get() = lines.sumOf { it.qty }

        override fun payload() =
            "$PREFIX $BOX PO:$purchaseOrderId PL:$packingListId ID:$id IN:$masterId NO:$seq/$of ITEMS:" +
                lines.joinToString("+") { "${it.sku}*${it.qty}" }
    }

    companion object {
        const val PREFIX = "VTBOX1"
        private const val MASTER = "MASTER"
        private const val BOX = "BOX"

        /** Most boxes one master box can hold — box IDs number them `B01`…`B99`. */
        const val MAX_BOXES = 99

        /** Most units of one SKU a single box can declare. */
        const val MAX_QTY = 99_999

        private val TOKEN = Regex("[A-Z0-9](?:[A-Z0-9-]{0,30}[A-Z0-9])?")
        private val WHITESPACE = Regex("\\s+")

        /** A reference, ID or SKU a label can carry: capitals, digits and inner hyphens, 32 at most. */
        fun isToken(s: String) = TOKEN.matches(s)

        private fun requireTokens(vararg values: String) =
            values.forEach { require(isToken(it)) { "\"$it\" cannot go on a box label" } }

        /**
         * The label [payload] carries, or null when it is not one of ours —
         * anything malformed included, so a damaged or hand-edited code falls
         * back to being read like any other carton code.
         */
        fun parse(payload: String): BoxLabel? {
            val words = payload.trim().uppercase().split(WHITESPACE)
            if (words.size < 2 || words[0] != PREFIX) return null
            val fields = HashMap<String, String>()
            for (word in words.drop(2)) {
                val colon = word.indexOf(':')
                // A field without a key, or one given twice, is not a label this app printed.
                if (colon <= 0 || fields.put(word.substring(0, colon), word.substring(colon + 1)) != null) return null
            }
            return try {
                when (words[1]) {
                    MASTER -> Master(
                        purchaseOrderId = fields.getValue("PO"),
                        packingListId = fields.getValue("PL"),
                        id = fields.getValue("ID"),
                        boxCount = fields.getValue("BOXES").toInt(),
                        units = fields.getValue("UNITS").toInt(),
                    )
                    BOX -> {
                        val numbers = fields.getValue("NO").split('/')
                        require(numbers.size == 2)
                        Inner(
                            purchaseOrderId = fields.getValue("PO"),
                            packingListId = fields.getValue("PL"),
                            id = fields.getValue("ID"),
                            masterId = fields.getValue("IN"),
                            seq = numbers[0].toInt(),
                            of = numbers[1].toInt(),
                            lines = fields.getValue("ITEMS").split('+').map { item ->
                                val parts = item.split('*')
                                require(parts.size == 2)
                                BoxLine(parts[0], parts[1].toInt())
                            },
                        )
                    }
                    else -> null
                }
            } catch (e: NoSuchElementException) {
                null
            } catch (e: IllegalArgumentException) {
                // Also NumberFormatException, and every check the label's own init makes.
                null
            }
        }
    }
}
