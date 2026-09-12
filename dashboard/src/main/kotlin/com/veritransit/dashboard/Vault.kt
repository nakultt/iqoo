package com.veritransit.dashboard

/**
 * The consignment vault the dashboard serves. Seeded with the same demo
 * records the inspector app and the telegram-bot carry, so every surface
 * agrees on the story; [upsert] is what a committed field inspection hits
 * (POST /api/records), which is how "inspected in the field" becomes
 * "shipped / held" on the dashboard and in the PDF reports.
 */
class Vault {

    private val records = linkedMapOf<String, InspectionRecord>()

    /** Newest first, the same ordering the app's records tab uses. */
    val all: List<InspectionRecord>
        get() = records.values.sortedByDescending { it.timestamp }

    fun find(id: String): InspectionRecord? = records[id]

    fun upsert(record: InspectionRecord) {
        records[record.id] = record
    }

    val shipped: Int get() = all.count { ShipState.of(it.verdict) == ShipState.SHIPPED }
    val held: Int get() = all.count { ShipState.of(it.verdict) == ShipState.HELD }
    val awaiting: Int get() = all.count { ShipState.of(it.verdict) == ShipState.AWAITING }

    companion object {
        /**
         * Seed vault — the same consignments the app's `Repo` and the bot's
         * `BotData` open with, trimmed to the ones that make the dashboard
         * legible: cleared (OK to pay), flagged (do not pay), and one still
         * awaiting its inspection.
         */
        fun seeded(): Vault = Vault().apply {
            upsert(
                InspectionRecord(
                    id = "VT-2024-8838", ewb = "EWB-9048-2810", vehicle = "TN 38 BX 4491",
                    vehicleModel = "Eicher Pro 2049", cargo = "Industrial Hardware (Fasteners/Plates)",
                    route = "Chennai → Bengaluru", distanceKm = 346, verdict = Verdict.PASSED,
                    timestamp = 1_000, confidence = 0.987f,
                    items = listOf(
                        CargoItem("MS Hex Bolts M12", "Sealed Crate A", 12, 12),
                        CargoItem("GI Plates 6mm", "Sealed Crate B", 8, 8),
                    ),
                ),
            )
            upsert(
                InspectionRecord(
                    id = "VT-2024-8831", ewb = "EWB-7719-0144", vehicle = "MH 12 QP 5519",
                    vehicleModel = "Tata 407 LCV", cargo = "Packaged Foodstuffs (Dry Cereals)",
                    route = "Pune → Hyderabad", distanceKm = 535, verdict = Verdict.PASSED,
                    timestamp = 2_000, confidence = 0.971f,
                    items = listOf(CargoItem("Cereal Cartons 500g", "Food-grade Pallet", 30, 30)),
                ),
            )
            upsert(
                InspectionRecord(
                    id = "VT-2024-8824", ewb = "EWB-5512-3387", vehicle = "GJ 06 KT 1177",
                    vehicleModel = "Eicher Pro 3015", cargo = "Auto Spare Parts (Assemblies)",
                    route = "Ahmedabad → Surat", distanceKm = 264, verdict = Verdict.PASSED,
                    timestamp = 3_000, confidence = 0.963f,
                    items = listOf(CargoItem("Clutch Plate Kits", "Rack R-4", 18, 18)),
                ),
            )
            upsert(
                InspectionRecord(
                    id = "VT-2024-8835", ewb = "EWB-8831-7290", vehicle = "KA 04 ME 8021",
                    vehicleModel = "Ashok Leyland Dost", cargo = "Consumer Electronics (Smartphones/Tabs)",
                    route = "Hosur → Chennai", distanceKm = 306, verdict = Verdict.REVIEW,
                    timestamp = 4_000, confidence = 0.913f,
                    items = listOf(
                        CargoItem("Smartphone Cartons 5\"", "Retail Pallet", 24, 22),
                        CargoItem("Tablet Units 10\"", "Retail Pallet", 6, 6),
                    ),
                    note = "Smartphone cartons short by 2 — physical re-count issued.",
                ),
            )
            upsert(
                InspectionRecord(
                    id = "VT-2024-8829", ewb = "EWB-6620-9942", vehicle = "DL 01 AB 3311",
                    vehicleModel = "Mahindra Bolero Maxx", cargo = "Textile Machinery & Spares",
                    route = "Delhi → Jaipur", distanceKm = 281, verdict = Verdict.PENDING,
                    timestamp = 5_000,
                    items = listOf(
                        CargoItem("Loom Head Assembly", "Crate L-1", 2, 2),
                        CargoItem("Bobbin Spindle Set", "Crate L-2", 10, 10),
                    ),
                ),
            )
        }
    }
}
