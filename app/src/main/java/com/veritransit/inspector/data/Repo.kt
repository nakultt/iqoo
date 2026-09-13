package com.veritransit.inspector.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/** App-wide observable store. Demo data only — everything lives in memory. */
object Repo {

    /**
     * Receiver identity shown on the home header, the settings profile, the
     * booked stamp and the dashboard handoff. Editable in Settings →
     * Receiver — the neutral defaults keep no personal name or employer in
     * source.
     */
    var WAREHOUSE by mutableStateOf("Central Warehouse")
    var RECEIVER by mutableStateOf("Goods-In Clerk")
    var DOCK by mutableStateOf("—")

    var runCount by mutableStateOf(0)
        private set

    val records = mutableStateListOf<ReceivingRecord>()

    /**
     * Master boxes packed in sender mode, newest first. No seed data: nothing
     * is listed until the sender has actually packed and labelled it.
     */
    val masterBoxes = mutableStateListOf<MasterBox>()

    fun init(now: Long) {
        if (records.isNotEmpty()) return
        fun t(minsAgo: Int) = now - minsAgo * 60_000L
        records.addAll(
            listOf(
                ReceivingRecord(
                    id = "GRN-2025-8841", purchaseOrderId = "PO-2025-4471",
                    packingListId = "PL-2025-4471-A", supplier = "Bright Electronics Pvt Ltd",
                    goods = "Consumer Electronics (Smartphones/Tabs)",
                    dock = "Dock 3 · Central DC", carrier = "BlueDart Surface",
                    outcome = ReceiptOutcome.MISMATCH,
                    timestamp = t(96), confidence = 0.942f,
                    items = listOf(
                        PackingItem("ELC-2710", "Dell UltraSharp 27\" Monitor", "Factory Boxed • Pallet Lot", 3, 3),
                        PackingItem("ELC-1180", "Logitech Mechanical Keyboard", "Bulk Carton Pack", 4, 3),
                        PackingItem("", "Thermal POS Printer", "Not on packing list", 0, 1),
                    ),
                    note = "Thermal label printer found in the pallet, not on the packing list.",
                ),
                ReceivingRecord(
                    id = "GRN-2025-8838", purchaseOrderId = "PO-2025-4488",
                    packingListId = "PL-2025-4488-A", supplier = "Deccan Fasteners & Steel",
                    goods = "Industrial Hardware (Fasteners/Plates)",
                    dock = "Dock 1 · Plant Store", carrier = "TCI Freight",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(12), confidence = 0.987f,
                    items = listOf(
                        PackingItem("HDW-4412", "MS Hex Bolts M12", "Sealed Crate A", 12, 12),
                        PackingItem("HDW-6608", "GI Plates 6mm", "Sealed Crate B", 8, 8),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8835", purchaseOrderId = "PO-2025-4471",
                    packingListId = "PL-2025-4471-B", supplier = "Bright Electronics Pvt Ltd",
                    goods = "Consumer Electronics (Smartphones/Tabs)",
                    dock = "Dock 3 · Central DC", carrier = "VRL Logistics",
                    outcome = ReceiptOutcome.SHORT,
                    timestamp = t(38), confidence = 0.913f,
                    items = listOf(
                        PackingItem("ELC-3305", "Smartphone Cartons 5\"", "Retail Pallet", 24, 22),
                        PackingItem("ELC-4410", "Tablet Units 10\"", "Retail Pallet", 6, 6),
                    ),
                    note = "Two smartphone cartons short of the packing list; dock asked to re-count.",
                ),
                ReceivingRecord(
                    id = "GRN-2025-8831", purchaseOrderId = "PO-2025-4502",
                    packingListId = "PL-2025-4502-A", supplier = "Sahyadri Foods LLP",
                    goods = "Packaged Foodstuffs (Dry Cereals)",
                    dock = "Dock 2 · FMCG Bay", carrier = "Safexpress",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(65), confidence = 0.971f,
                    items = listOf(
                        PackingItem("FMC-5001", "Cereal Cartons 500g", "Food-grade Pallet", 30, 30),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8829", purchaseOrderId = "PO-2025-4519",
                    packingListId = "PL-2025-4519-A", supplier = "Lakshmi Textile Works",
                    goods = "Textile Machinery & Spares",
                    dock = "Dock 4 · Spares Store", carrier = "Gati Kausar",
                    outcome = ReceiptOutcome.PENDING,
                    timestamp = t(122),
                    items = listOf(
                        PackingItem("TXT-2201", "Loom Head Assembly", "Crate L-1", 2, 2),
                        PackingItem("TXT-7730", "Bobbin Spindle Set", "Crate L-2", 10, 10),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8824", purchaseOrderId = "PO-2025-4530",
                    packingListId = "PL-2025-4530-A", supplier = "AutoLink Components",
                    goods = "Auto Spare Parts (Assemblies)",
                    dock = "Dock 1 · Plant Store", carrier = "TCI Freight",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(190), confidence = 0.963f,
                    items = listOf(
                        PackingItem("ASP-1201", "Clutch Plate Kits", "Rack R-4", 18, 18),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8818", purchaseOrderId = "PO-2025-4544",
                    packingListId = "PL-2025-4544-A", supplier = "Vidarbha Chemical Works",
                    goods = "Pharma Intermediates (Non-cool)",
                    dock = "Dock 5 · Hazmat Store", carrier = "Gati Kausar",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(255), confidence = 0.991f,
                    items = listOf(
                        PackingItem("PHM-8802", "Fiber Drums 50L", "Secured Bay 2", 14, 14),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8812", purchaseOrderId = "PO-2025-4502",
                    packingListId = "PL-2025-4502-B", supplier = "Sahyadri Foods LLP",
                    goods = "Packaged Foodstuffs (Dry Cereals)",
                    dock = "Dock 2 · FMCG Bay", carrier = "Safexpress",
                    outcome = ReceiptOutcome.MISMATCH,
                    timestamp = t(310), confidence = 0.902f,
                    items = listOf(
                        PackingItem("FMC-6102", "Instant Mix Cartons", "Pallet F-2", 16, 16),
                        PackingItem("", "Health Drink Tins", "Not on packing list", 0, 2),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8806", purchaseOrderId = "PO-2025-4488",
                    packingListId = "PL-2025-4488-B", supplier = "Deccan Fasteners & Steel",
                    goods = "Industrial Hardware (Fasteners/Plates)",
                    dock = "Dock 1 · Plant Store", carrier = "TCI Freight",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(375), confidence = 0.978f,
                    items = listOf(
                        PackingItem("HDW-9014", "Anchor Fasteners", "Crate H-1", 40, 40),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8799", purchaseOrderId = "PO-2025-4471",
                    packingListId = "PL-2025-4471-C", supplier = "Bright Electronics Pvt Ltd",
                    goods = "Consumer Electronics (Smartphones/Tabs)",
                    dock = "Dock 3 · Central DC", carrier = "BlueDart Surface",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(460), confidence = 0.984f,
                    items = listOf(
                        PackingItem("ELC-5520", "Bluetooth Headset Boxes", "Carton E-3", 22, 22),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8791", purchaseOrderId = "PO-2025-4519",
                    packingListId = "PL-2025-4519-B", supplier = "Lakshmi Textile Works",
                    goods = "Textile Machinery & Spares",
                    dock = "Dock 4 · Spares Store", carrier = "Gati Kausar",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(540), confidence = 0.969f,
                    items = listOf(
                        PackingItem("TXT-3312", "Sewing Head Units", "Foam-lined Crate", 6, 6),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8784", purchaseOrderId = "PO-2025-4530",
                    packingListId = "PL-2025-4530-B", supplier = "AutoLink Components",
                    goods = "Auto Spare Parts (Assemblies)",
                    dock = "Dock 1 · Plant Store", carrier = "TCI Freight",
                    outcome = ReceiptOutcome.OVER,
                    timestamp = t(610), confidence = 0.957f,
                    items = listOf(
                        PackingItem("ASP-4407", "Alternator Units", "Rack R-1", 9, 11),
                    ),
                    note = "Two alternator units above the packed quantity; supplier notified.",
                ),
                ReceivingRecord(
                    id = "GRN-2025-8776", purchaseOrderId = "PO-2025-4502",
                    packingListId = "PL-2025-4502-C", supplier = "Sahyadri Foods LLP",
                    goods = "Packaged Foodstuffs (Dry Cereals)",
                    dock = "Dock 2 · FMCG Bay", carrier = "Safexpress",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(700), confidence = 0.974f,
                    items = listOf(
                        PackingItem("FMC-7208", "Rice Flour Bags 10kg", "Staged Pallet", 25, 25),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8768", purchaseOrderId = "PO-2025-4488",
                    packingListId = "PL-2025-4488-C", supplier = "Deccan Fasteners & Steel",
                    goods = "Industrial Hardware (Fasteners/Plates)",
                    dock = "Dock 1 · Plant Store", carrier = "TCI Freight",
                    outcome = ReceiptOutcome.MISMATCH,
                    timestamp = t(790), confidence = 0.988f,
                    items = listOf(
                        PackingItem("HDW-1120", "Tool Steel Rods", "Bundled Lot", 32, 32, damaged = 3),
                    ),
                    note = "Three rods bent in transit; booked the remainder and raised a damage claim.",
                ),
                ReceivingRecord(
                    id = "GRN-2025-8759", purchaseOrderId = "PO-2025-4471",
                    packingListId = "PL-2025-4471-D", supplier = "Bright Electronics Pvt Ltd",
                    goods = "Consumer Electronics (Smartphones/Tabs)",
                    dock = "Dock 3 · Central DC", carrier = "BlueDart Surface",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(880), confidence = 0.961f,
                    items = listOf(
                        PackingItem("ELC-8801", "Power Bank Cartons", "Shrink-wrapped", 28, 28),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8751", purchaseOrderId = "PO-2025-4519",
                    packingListId = "PL-2025-4519-C", supplier = "Lakshmi Textile Works",
                    goods = "Textile Machinery & Spares",
                    dock = "Dock 4 · Spares Store", carrier = "Gati Kausar",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(975), confidence = 0.979f,
                    items = listOf(
                        PackingItem("TXT-9905", "Spinning Bobbins", "Crate T-5", 44, 44),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8744", purchaseOrderId = "PO-2025-4544",
                    packingListId = "PL-2025-4544-B", supplier = "Vidarbha Chemical Works",
                    goods = "Pharma Intermediates (Non-cool)",
                    dock = "Dock 5 · Hazmat Store", carrier = "Gati Kausar",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(1080), confidence = 0.993f,
                    items = listOf(
                        PackingItem("PHM-2204", "HDPE Canisters 20L", "Sealed Bay 1", 20, 20),
                    ),
                ),
                ReceivingRecord(
                    id = "GRN-2025-8736", purchaseOrderId = "PO-2025-4530",
                    packingListId = "PL-2025-4530-C", supplier = "AutoLink Components",
                    goods = "Auto Spare Parts (Assemblies)",
                    dock = "Dock 1 · Plant Store", carrier = "TCI Freight",
                    outcome = ReceiptOutcome.OK,
                    timestamp = t(1200), confidence = 0.972f,
                    items = listOf(
                        PackingItem("ASP-6612", "Radiator Assemblies", "Pallet P-7", 11, 11),
                    ),
                ),
            )
        )
    }

    val total: Int get() = records.size
    val accepted: Int get() = records.count { it.outcome == ReceiptOutcome.OK }
    val flagged: Int get() = records.count { it.outcome != ReceiptOutcome.OK && it.outcome != ReceiptOutcome.PENDING }

    /**
     * Receiving scenarios for the simulated dock count, starting with the
     * canonical demo discrepancy: OK, short, over, unlisted, damaged.
     */
    fun nextReceivingScenario(packingList: PackingList): List<PackingItem> {
        runCount++
        val expected = packingList.items
        return when (if (runCount == 1) 0 else Random.nextInt(0, 4)) {
            // Canonical demo: one line short, plus goods nobody packed.
            0 -> expected.mapIndexed { i, it ->
                when (i) {
                    1 -> it.copy(received = (it.expected - 1).coerceAtLeast(0))
                    else -> it.copy(received = it.expected)
                }
            } + PackingItem("", "Thermal POS Printer", "Not on packing list", 0, 1)
            // Everything as packed.
            1 -> expected.map { it.copy(received = it.expected) }
            // An over-count on the first line.
            2 -> expected.mapIndexed { i, it ->
                if (i == 0) it.copy(received = it.expected + 2) else it.copy(received = it.expected)
            }
            // Damage on the last line — right count, not fit to book.
            else -> expected.mapIndexed { i, it ->
                if (i == expected.lastIndex) it.copy(received = it.expected, damaged = 1)
                else it.copy(received = it.expected)
            }
        }
    }

    fun commit(record: ReceivingRecord) {
        records.add(0, record)
    }

    /**
     * Saves a packed master box at the top of the list. A box saved again
     * under the same ID — labels re-printed after an edit — replaces its
     * earlier entry instead of listing twice.
     */
    fun saveMasterBox(box: MasterBox) {
        masterBoxes.removeAll { it.id == box.id }
        masterBoxes.add(0, box)
    }
}

/** Relative-time label used across lists and cards. */
fun relativeLabel(now: Long, timestamp: Long): String {
    val mins = ((now - timestamp) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins mins ago"
        mins < 24 * 60L -> {
            val h = mins / 60
            if (h == 1L) "1 hr ago" else "$h hrs ago"
        }
        else -> "${mins / (24 * 60L)} days ago"
    }
}
