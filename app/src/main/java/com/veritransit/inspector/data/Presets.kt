package com.veritransit.inspector.data

/** Catalog of consignment presets the scanner can resolve (demo data). */
data class ConsignmentPreset(
    val name: String,
    val short: String,
    val vehicleModel: String,
    val route: String,
    val distanceKm: Int,
    val items: List<CargoItem>,
) {
    val totalUnits: Int get() = items.sumOf { it.expected }
}

object Presets {

    val ALL = listOf(
        ConsignmentPreset(
            "Consumer Electronics (Smartphones/Tabs)", "Electronics",
            "Tata 407 LCV", "Chennai → Coimbatore", 498,
            listOf(
                CargoItem("Dell UltraSharp 27\" Monitor", "Factory Boxed • Pallet Lot", 3, 3),
                CargoItem("Logitech Mechanical Keyboard", "Bulk Carton Pack", 4, 4),
            ),
        ),
        ConsignmentPreset(
            "Industrial Hardware (Fasteners/Plates)", "Hardware",
            "Eicher Pro 2049", "Chennai → Bengaluru", 346,
            listOf(
                CargoItem("MS Hex Bolts M12", "Sealed Crate A", 12, 12),
                CargoItem("GI Plates 6mm", "Sealed Crate B", 8, 8),
            ),
        ),
        ConsignmentPreset(
            "Packaged Foodstuffs (Dry Cereals)", "Foodstuffs",
            "Ashok Leyland Partner", "Pune → Hyderabad", 535,
            listOf(
                CargoItem("Cereal Cartons 500g", "Food-grade Pallet", 20, 20),
                CargoItem("Health Drink Tins", "Shrink-wrapped Lot", 10, 10),
            ),
        ),
        ConsignmentPreset(
            "Textile Machinery & Spares", "Textile",
            "Mahindra Bolero Maxx", "Delhi → Jaipur", 281,
            listOf(
                CargoItem("Loom Head Assembly", "Crate L-1", 2, 2),
                CargoItem("Bobbin Spindle Set", "Crate L-2", 10, 10),
            ),
        ),
    )

    val DEFAULT = ALL.first()
}
