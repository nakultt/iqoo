package com.veritransit.inspector.data

/**
 * Catalog of packing-list presets the dock can receive against (demo data).
 *
 * Each preset is one purchase order + the supplier's packing list for it:
 * a PO number, the supplier, where it is being received, and the declared
 * lines keyed by SKU. Carrier and dock are context for the receiver, not the
 * identity of the shipment.
 */
data class PackingListPreset(
    val goods: String,
    val short: String,
    val purchaseOrderId: String,
    val packingListId: String,
    val supplier: String,
    val dock: String,
    val carrier: String,
    val items: List<PackingItem>,
) {
    val totalUnits: Int get() = items.sumOf { it.expected }
}

object Presets {

    val ALL = listOf(
        PackingListPreset(
            goods = "Consumer Electronics (Smartphones/Tabs)", short = "Electronics",
            purchaseOrderId = "PO-2025-4471", packingListId = "PL-2025-4471-A",
            supplier = "Bright Electronics Pvt Ltd", dock = "Dock 3 · Central DC",
            carrier = "BlueDart Surface",
            items = listOf(
                PackingItem("ELC-2710", "Dell UltraSharp 27\" Monitor", "Factory Boxed • Pallet Lot", 3, 3),
                PackingItem("ELC-1180", "Logitech Mechanical Keyboard", "Bulk Carton Pack", 4, 4),
            ),
        ),
        PackingListPreset(
            goods = "Industrial Hardware (Fasteners/Plates)", short = "Hardware",
            purchaseOrderId = "PO-2025-4488", packingListId = "PL-2025-4488-A",
            supplier = "Deccan Fasteners & Steel", dock = "Dock 1 · Plant Store",
            carrier = "TCI Freight",
            items = listOf(
                PackingItem("HDW-4412", "MS Hex Bolts M12", "Sealed Crate A", 12, 12),
                PackingItem("HDW-6608", "GI Plates 6mm", "Sealed Crate B", 8, 8),
            ),
        ),
        PackingListPreset(
            goods = "Packaged Foodstuffs (Dry Cereals)", short = "Foodstuffs",
            purchaseOrderId = "PO-2025-4502", packingListId = "PL-2025-4502-A",
            supplier = "Sahyadri Foods LLP", dock = "Dock 2 · FMCG Bay",
            carrier = "Safexpress",
            items = listOf(
                PackingItem("FMC-5001", "Cereal Cartons 500g", "Food-grade Pallet", 20, 20),
                PackingItem("FMC-5510", "Health Drink Tins", "Shrink-wrapped Lot", 10, 10),
            ),
        ),
        PackingListPreset(
            goods = "Textile Machinery & Spares", short = "Textile",
            purchaseOrderId = "PO-2025-4519", packingListId = "PL-2025-4519-A",
            supplier = "Lakshmi Textile Works", dock = "Dock 4 · Spares Store",
            carrier = "Gati Kausar",
            items = listOf(
                PackingItem("TXT-2201", "Loom Head Assembly", "Crate L-1", 2, 2),
                PackingItem("TXT-7730", "Bobbin Spindle Set", "Crate L-2", 10, 10),
            ),
        ),
        PackingListPreset(
            goods = "Fresh Apples", short = "Apples",
            purchaseOrderId = "PO-2025-4600", packingListId = "PL-2025-4600-A",
            supplier = "Himachal Orchard Farms", dock = "Dock 2 · FMCG Bay",
            carrier = "Safexpress",
            items = listOf(
                PackingItem("APL-1001", "Royal Gala Apples, loose", "Loose count", 6, 6),
                PackingItem("APL-1002", "Shimla Green Apples, loose", "Loose count", 4, 4),
            ),
        ),
    )

    val DEFAULT = ALL.first()
}
