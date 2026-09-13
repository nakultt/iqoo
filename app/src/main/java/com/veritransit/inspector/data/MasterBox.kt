package com.veritransit.inspector.data

/** Box [seq] of a master box's boxes, holding [lines]. */
data class InnerBox(val id: String, val seq: Int, val lines: List<BoxLine>) {
    val units: Int get() = lines.sumOf { it.qty }
}

/**
 * A packed master box — the big outer box, the [boxes] packed inside it, and
 * the packing list they were packed against. Sender mode builds one, and every
 * box gets a QR label, the master included ([labels]).
 */
data class MasterBox(
    val id: String,
    val purchaseOrderId: String,
    val packingListId: String,
    val supplier: String,
    val goods: String,
    val shipTo: String,
    val boxes: List<InnerBox>,
    val packedAt: Long,
    /** SKU → item name from the packing list, for the human-readable side of a label. */
    val itemNames: Map<String, String> = emptyMap(),
) {
    val units: Int get() = boxes.sumOf { it.units }

    fun masterLabel() = BoxLabel.Master(purchaseOrderId, packingListId, id, boxes.size, units)

    fun boxLabel(box: InnerBox) =
        BoxLabel.Inner(purchaseOrderId, packingListId, box.id, id, box.seq, boxes.size, box.lines)

    /** Every label the master box needs: its own first, then each box inside, in order. */
    fun labels(): List<BoxLabel> = listOf(masterLabel()) + boxes.map(::boxLabel)
}

/**
 * Sender mode's planning, kept pure for the JVM tests: which packing-list units
 * go in which box inside a master box, and how that plan compares with the list.
 */
object BoxPacking {

    /** Long enough for a sender's own carton numbers, short enough to leave room for `-B99`. */
    const val MAX_MASTER_ID = 24

    fun isValidMasterId(id: String) = id.length <= MAX_MASTER_ID && BoxLabel.isToken(id)

    /** `MB-4471-01`: the PO's serial, then the first sequence number not already [taken]. */
    fun suggestMasterId(purchaseOrderId: String, taken: Collection<String>): String {
        val po = purchaseOrderId.uppercase()
        val serial = Regex("[0-9]+").findAll(po).lastOrNull()?.value?.takeLast(6)
            ?: po.filter { it in 'A'..'Z' || it in '0'..'9' }.takeLast(6)
        val stem = if (serial.isEmpty()) "MB" else "MB-$serial"
        val used = taken.mapTo(HashSet()) { it.uppercase() }
        return generateSequence(1) { it + 1 }
            .map { "$stem-" + it.toString().padStart(2, '0') }
            .first { it !in used }
    }

    /** `MB-4471-01-B03` — box 3 inside `MB-4471-01`. */
    fun innerBoxId(masterId: String, seq: Int) = "$masterId-B" + seq.toString().padStart(2, '0')

    /**
     * The packing list's lines that can go in a box: one per SKU, and only
     * lines with units to pack — an unlisted find has no SKU to label.
     */
    fun packable(items: List<PackingItem>): List<BoxLine> {
        val units = LinkedHashMap<String, Int>()
        for (item in items) {
            val sku = item.sku.trim()
            if (sku.isNotEmpty() && item.expected > 0) units.merge(sku, item.expected, Int::plus)
        }
        return units.map { (sku, qty) -> BoxLine(sku, qty) }
    }

    /**
     * A starting plan for [boxCount] boxes: every unit on the list packed
     * exactly once, and no box left empty while there are units to fill it.
     *
     * Each box holds a single SKU whenever there are at least as many boxes as
     * SKUs — that is the box a dock can check at a glance and put away without
     * unpacking. Every SKU gets one box; each extra box goes to whichever SKU
     * has the most units per box (the D'Hondt rule), and a SKU's units split
     * evenly across its boxes. With fewer boxes than SKUs some box has to mix,
     * so the units fill boxes of near-equal size in packing-list order.
     */
    fun distribute(items: List<PackingItem>, boxCount: Int): List<List<BoxLine>> {
        require(boxCount in 1..BoxLabel.MAX_BOXES) { "$boxCount boxes" }
        val lines = packable(items)
        val total = lines.sumOf { it.qty }
        if (boxCount < lines.size || boxCount > total) return fillInOrder(lines, boxCount)
        val boxesPerLine = IntArray(lines.size) { 1 }
        repeat(boxCount - lines.size) {
            // boxCount <= total, so some line always has more units than boxes.
            val next = lines.indices
                .filter { boxesPerLine[it] < lines[it].qty }
                .maxBy { lines[it].qty.toDouble() / boxesPerLine[it] }
            boxesPerLine[next]++
        }
        return lines.flatMapIndexed { i, line ->
            evenSplit(line.qty, boxesPerLine[i]).map { qty -> listOf(BoxLine(line.sku, qty)) }
        }
    }

    /**
     * Box [box] (from 0) with [delta] more units of [sku], never below none.
     * Lines stay in packing-list order, so a label reads like the list.
     */
    fun adjust(
        items: List<PackingItem>,
        contents: List<List<BoxLine>>,
        box: Int,
        sku: String,
        delta: Int,
    ): List<List<BoxLine>> {
        if (box !in contents.indices) return contents
        val order = packable(items).map { it.sku }
        val units = contents[box].associateTo(LinkedHashMap()) { it.sku to it.qty }
        val qty = ((units[sku] ?: 0) + delta).coerceIn(0, BoxLabel.MAX_QTY)
        if (qty == 0) units.remove(sku) else units[sku] = qty
        val rank = { s: String -> order.indexOf(s).let { if (it < 0) order.size else it } }
        return contents.toMutableList().also { all ->
            all[box] = units.map { (s, q) -> BoxLine(s, q) }.sortedBy { rank(it.sku) }
        }
    }

    /**
     * The plan against the packing list, one line per SKU. Each line's
     * `received` is what the boxes hold, so it reads matched / short / over
     * exactly as a dock count of the same boxes would.
     */
    fun reconcile(items: List<PackingItem>, contents: List<List<BoxLine>>): List<PackingItem> {
        val packed = LinkedHashMap<String, Int>()
        contents.flatten().forEach { packed.merge(it.sku, it.qty, Int::plus) }
        val listed = packable(items).map { line ->
            val item = items.first { it.sku.trim() == line.sku }
            PackingItem(line.sku, item.name, item.detail, expected = line.qty, received = packed.remove(line.sku) ?: 0)
        }
        val unlisted = packed.map { (sku, qty) -> PackingItem(sku, sku, "Not on packing list", expected = 0, received = qty) }
        return listed + unlisted
    }

    /** Boxes (numbered from 1) that hold nothing — their label would book nothing at the dock. */
    fun emptyBoxes(contents: List<List<BoxLine>>): List<Int> =
        contents.indices.filter { i -> contents[i].none { it.qty > 0 } }.map { it + 1 }

    /** The master box [contents] describes, with every box numbered inside [masterId]. */
    fun build(masterId: String, list: PackingListPreset, contents: List<List<BoxLine>>, packedAt: Long): MasterBox {
        require(isValidMasterId(masterId)) { "\"$masterId\" is not a master box ID" }
        require(contents.size in 1..BoxLabel.MAX_BOXES) { "${contents.size} boxes" }
        require(emptyBoxes(contents).isEmpty()) { "boxes ${emptyBoxes(contents)} are empty" }
        return MasterBox(
            id = masterId,
            purchaseOrderId = list.purchaseOrderId,
            packingListId = list.packingListId,
            supplier = list.supplier,
            goods = list.goods,
            shipTo = list.dock,
            boxes = contents.mapIndexed { i, lines -> InnerBox(innerBoxId(masterId, i + 1), i + 1, lines) },
            packedAt = packedAt,
            itemNames = list.items.filter { it.sku.isNotBlank() }.associate { it.sku.trim() to it.name },
        ).also {
            // Makes every label once: a value no label can carry fails here,
            // when the sender taps Generate, not later at the printer.
            it.labels()
        }
    }

    /** [total] in [parts] sizes that differ by at most one, larger first. */
    private fun evenSplit(total: Int, parts: Int): List<Int> =
        List(parts) { total / parts + if (it < total % parts) 1 else 0 }

    private fun fillInOrder(lines: List<BoxLine>, boxCount: Int): List<List<BoxLine>> {
        var line = 0
        var left = lines.firstOrNull()?.qty ?: 0
        return evenSplit(lines.sumOf { it.qty }, boxCount).map { size ->
            val box = mutableListOf<BoxLine>()
            var room = size
            while (room > 0 && line < lines.size) {
                val take = minOf(room, left)
                box += BoxLine(lines[line].sku, take)
                room -= take
                left -= take
                if (left == 0) {
                    line++
                    left = lines.getOrNull(line)?.qty ?: 0
                }
            }
            box
        }
    }
}
