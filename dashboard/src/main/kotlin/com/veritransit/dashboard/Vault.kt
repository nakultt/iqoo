package com.veritransit.dashboard

/**
 * The receipt log the dashboard serves. Seeded with the same demo records the
 * receiving app and the telegram-bot carry, so every surface agrees on the
 * story; [upsert] is what a committed dock receipt hits (POST /api/records),
 * which is how "received at the dock" becomes "accepted / held" on the
 * dashboard and in the PDF reports.
 */
class Vault {

    private val records = linkedMapOf<String, ReceivingRecord>()

    /** Newest first, the same ordering the app's receipts tab uses. */
    val all: List<ReceivingRecord>
        get() = records.values.sortedByDescending { it.timestamp }

    fun find(id: String): ReceivingRecord? = records[id]

    fun upsert(record: ReceivingRecord) {
        records[record.id] = record
    }

    val shipped: Int get() = all.count { ShipState.of(it.outcome) == ShipState.SHIPPED }
    val held: Int get() = all.count { ShipState.of(it.outcome) == ShipState.HELD }
    val awaiting: Int get() = all.count { ShipState.of(it.outcome) == ShipState.AWAITING }

    companion object {
        /**
         * Seed log — the same deliveries the app's `Repo` and the bot's
         * `BotData` open with, trimmed to the ones that make the dashboard
         * legible: accepted (OK to pay), flagged (do not pay), and one still
         * awaiting its dock count.
         */
        fun seeded(): Vault = Vault().apply {
            upsert(
                ReceivingRecord(
                    id = "GRN-2025-8838", purchaseOrderId = "PO-2025-4488",
                    packingListId = "PL-2025-4488-A", supplier = "Deccan Fasteners & Steel",
                    goods = "Industrial Hardware (Fasteners/Plates)",
                    dock = "Dock 1 · Plant Store", carrier = "TCI Freight",
                    outcome = ReceiptOutcome.OK, timestamp = 1_000, confidence = 0.987f,
                    items = listOf(
                        PackingItem("HDW-4412", "MS Hex Bolts M12", "Sealed Crate A", 12, 12),
                        PackingItem("HDW-6608", "GI Plates 6mm", "Sealed Crate B", 8, 8),
                    ),
                ),
            )
            upsert(
                ReceivingRecord(
                    id = "GRN-2025-8831", purchaseOrderId = "PO-2025-4502",
                    packingListId = "PL-2025-4502-A", supplier = "Sahyadri Foods LLP",
                    goods = "Packaged Foodstuffs (Dry Cereals)",
                    dock = "Dock 2 · FMCG Bay", carrier = "Safexpress",
                    outcome = ReceiptOutcome.OK, timestamp = 2_000, confidence = 0.971f,
                    items = listOf(PackingItem("FMC-5001", "Cereal Cartons 500g", "Food-grade Pallet", 30, 30)),
                ),
            )
            upsert(
                ReceivingRecord(
                    id = "GRN-2025-8824", purchaseOrderId = "PO-2025-4530",
                    packingListId = "PL-2025-4530-A", supplier = "AutoLink Components",
                    goods = "Auto Spare Parts (Assemblies)",
                    dock = "Dock 1 · Plant Store", carrier = "TCI Freight",
                    outcome = ReceiptOutcome.OK, timestamp = 3_000, confidence = 0.963f,
                    items = listOf(PackingItem("ASP-1201", "Clutch Plate Kits", "Rack R-4", 18, 18)),
                ),
            )
            upsert(
                ReceivingRecord(
                    id = "GRN-2025-8835", purchaseOrderId = "PO-2025-4471",
                    packingListId = "PL-2025-4471-B", supplier = "Bright Electronics Pvt Ltd",
                    goods = "Consumer Electronics (Smartphones/Tabs)",
                    dock = "Dock 3 · Central DC", carrier = "VRL Logistics",
                    outcome = ReceiptOutcome.SHORT, timestamp = 4_000, confidence = 0.913f,
                    items = listOf(
                        PackingItem("ELC-3305", "Smartphone Cartons 5\"", "Retail Pallet", 24, 22),
                        PackingItem("ELC-4410", "Tablet Units 10\"", "Retail Pallet", 6, 6),
                    ),
                    note = "Smartphone cartons short by 2 — dock asked to re-count.",
                ),
            )
            upsert(
                ReceivingRecord(
                    id = "GRN-2025-8829", purchaseOrderId = "PO-2025-4519",
                    packingListId = "PL-2025-4519-A", supplier = "Lakshmi Textile Works",
                    goods = "Textile Machinery & Spares",
                    dock = "Dock 4 · Spares Store", carrier = "Gati Kausar",
                    outcome = ReceiptOutcome.PENDING, timestamp = 5_000,
                    items = listOf(
                        PackingItem("TXT-2201", "Loom Head Assembly", "Crate L-1", 2, 2),
                        PackingItem("TXT-7730", "Bobbin Spindle Set", "Crate L-2", 10, 10),
                    ),
                ),
            )
        }
    }
}
