package com.veritransit.inspector.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/** App-wide observable store. Demo data only — everything lives in memory. */
object Repo {

    /**
     * Officer identity shown on the home header, the settings profile, the
     * signed stamp and the dashboard handoff. Editable in Settings → Officer —
     * the neutral defaults keep no personal name, badge or post in source.
     */
    var STATION by mutableStateOf("Field Station")
    var INSPECTOR by mutableStateOf("Duty Officer")
    var BADGE by mutableStateOf("—")

    var runCount by mutableStateOf(0)
        private set

    val records = mutableStateListOf<InspectionRecord>()

    fun init(now: Long) {
        if (records.isNotEmpty()) return
        fun t(minsAgo: Int) = now - minsAgo * 60_000L
        records.addAll(
            listOf(
                InspectionRecord(
                    id = "VT-2024-8841", ewb = "EWB-7819-2044", vehicle = "TN 38 BX 4491",
                    vehicleModel = "Tata 407 LCV", cargo = "Consumer Electronics (Smartphones/Tabs)",
                    route = "Chennai → Coimbatore", distanceKm = 498, verdict = Verdict.REVIEW,
                    timestamp = t(96), confidence = 0.942f,
                    items = listOf(
                        CargoItem("Dell UltraSharp 27\" Monitor", "Factory Boxed • Pallet Lot", 3, 3),
                        CargoItem("Logitech Mechanical Keyboard", "Bulk Carton Pack", 4, 3),
                        CargoItem("Thermal POS Printer", "Not on manifest", 0, 1),
                    ),
                    note = "Thermal label printer observed during weighbridge check.",
                ),
                InspectionRecord(
                    id = "VT-2024-8838", ewb = "EWB-9048-2810", vehicle = "TN 38 BX 4491",
                    vehicleModel = "Eicher Pro 2049", cargo = "Industrial Hardware (Fasteners/Plates)",
                    route = "Chennai → Bengaluru", distanceKm = 346, verdict = Verdict.PASSED,
                    timestamp = t(12), confidence = 0.987f,
                    items = listOf(
                        CargoItem("MS Hex Bolts M12", "Sealed Crate A", 12, 12),
                        CargoItem("GI Plates 6mm", "Sealed Crate B", 8, 8),
                    ),
                ),
                InspectionRecord(
                    id = "VT-2024-8835", ewb = "EWB-8831-7290", vehicle = "KA 04 ME 8021",
                    vehicleModel = "Ashok Leyland Dost", cargo = "Consumer Electronics (Smartphones/Tabs)",
                    route = "Hosur → Chennai", distanceKm = 306, verdict = Verdict.REVIEW,
                    timestamp = t(38), confidence = 0.913f,
                    items = listOf(
                        CargoItem("Smartphone Cartons 5\"", "Retail Pallet", 24, 22),
                        CargoItem("Tablet Units 10\"", "Retail Pallet", 6, 6),
                    ),
                ),
                InspectionRecord(
                    id = "VT-2024-8831", ewb = "EWB-7719-0144", vehicle = "MH 12 QP 5519",
                    vehicleModel = "Tata 407 LCV", cargo = "Packaged Foodstuffs (Dry Cereals)",
                    route = "Pune → Hyderabad", distanceKm = 535, verdict = Verdict.PASSED,
                    timestamp = t(65), confidence = 0.971f,
                    items = listOf(CargoItem("Cereal Cartons 500g", "Food-grade Pallet", 30, 30)),
                ),
                InspectionRecord(
                    id = "VT-2024-8829", ewb = "EWB-6620-9942", vehicle = "DL 01 AB 3311",
                    vehicleModel = "Mahindra Bolero Maxx", cargo = "Textile Machinery & Spares",
                    route = "Delhi → Jaipur", distanceKm = 281, verdict = Verdict.PENDING,
                    timestamp = t(122),
                    items = listOf(
                        CargoItem("Loom Head Assembly", "Crate L-1", 2, 2),
                        CargoItem("Bobbin Spindle Set", "Crate L-2", 10, 10),
                    ),
                ),
                InspectionRecord(
                    id = "VT-2024-8824", ewb = "EWB-5512-3387", vehicle = "GJ 06 KT 1177",
                    vehicleModel = "Eicher Pro 3015", cargo = "Auto Spare Parts (Assemblies)",
                    route = "Ahmedabad → Surat", distanceKm = 264, verdict = Verdict.PASSED,
                    timestamp = t(190), confidence = 0.963f,
                    items = listOf(CargoItem("Clutch Plate Kits", "Rack R-4", 18, 18)),
                ),
                InspectionRecord(
                    id = "VT-2024-8818", ewb = "EWB-4408-1276", vehicle = "TS 09 UB 6420",
                    vehicleModel = "BharatBenz 1217C", cargo = "Pharma Intermediates (Non-cool)",
                    route = "Hyderabad → Vijayawada", distanceKm = 273, verdict = Verdict.PASSED,
                    timestamp = t(255), confidence = 0.991f,
                    items = listOf(CargoItem("Fiber Drums 50L", "Secured Bay 2", 14, 14)),
                ),
                InspectionRecord(
                    id = "VT-2024-8812", ewb = "EWB-3315-8890", vehicle = "KA 51 AC 2298",
                    vehicleModel = "Tata Ace Gold", cargo = "Packaged Foodstuffs (Dry Cereals)",
                    route = "Bengaluru → Salem", distanceKm = 170, verdict = Verdict.REVIEW,
                    timestamp = t(310), confidence = 0.902f,
                    items = listOf(
                        CargoItem("Instant Mix Cartons", "Pallet F-2", 16, 16),
                        CargoItem("Health Drink Tins", "Not on manifest", 0, 2),
                    ),
                ),
                InspectionRecord(
                    id = "VT-2024-8806", ewb = "EWB-2244-5501", vehicle = "TN 10 CD 7751",
                    vehicleModel = "Ashok Leyland Partner", cargo = "Industrial Hardware (Fasteners/Plates)",
                    route = "Chennai → Trichy", distanceKm = 336, verdict = Verdict.PASSED,
                    timestamp = t(375), confidence = 0.978f,
                    items = listOf(CargoItem("Anchor Fasteners", "Crate H-1", 40, 40)),
                ),
                InspectionRecord(
                    id = "VT-2024-8799", ewb = "EWB-1188-6233", vehicle = "KL 07 BJ 4419",
                    vehicleModel = "Mahindra Furio 7", cargo = "Consumer Electronics (Smartphones/Tabs)",
                    route = "Kochi → Coimbatore", distanceKm = 193, verdict = Verdict.PASSED,
                    timestamp = t(460), confidence = 0.984f,
                    items = listOf(CargoItem("Bluetooth Headset Boxes", "Carton E-3", 22, 22)),
                ),
                InspectionRecord(
                    id = "VT-2024-8791", ewb = "EWB-0971-3348", vehicle = "AP 28 DX 9012",
                    vehicleModel = "Tata Intra V30", cargo = "Textile Machinery & Spares",
                    route = "Tirupur → Chennai", distanceKm = 431, verdict = Verdict.PASSED,
                    timestamp = t(540), confidence = 0.969f,
                    items = listOf(CargoItem("Sewing Head Units", "Foam-lined Crate", 6, 6)),
                ),
                InspectionRecord(
                    id = "VT-2024-8784", ewb = "EWB-8842-0031", vehicle = "MH 14 HK 3366",
                    vehicleModel = "Eicher Pro 2049", cargo = "Auto Spare Parts (Assemblies)",
                    route = "Pune → Nashik", distanceKm = 210, verdict = Verdict.PASSED,
                    timestamp = t(610), confidence = 0.957f,
                    items = listOf(CargoItem("Alternator Units", "Rack R-1", 9, 9)),
                ),
                InspectionRecord(
                    id = "VT-2024-8776", ewb = "EWB-7730-9128", vehicle = "TN 22 BQ 5188",
                    vehicleModel = "Tata 407 LCV", cargo = "Packaged Foodstuffs (Dry Cereals)",
                    route = "Madurai → Chennai", distanceKm = 462, verdict = Verdict.PASSED,
                    timestamp = t(700), confidence = 0.974f,
                    items = listOf(CargoItem("Rice Flour Bags 10kg", "Staged Pallet", 25, 25)),
                ),
                InspectionRecord(
                    id = "VT-2024-8768", ewb = "EWB-6611-7204", vehicle = "HR 26 DK 8823",
                    vehicleModel = "Mahindra Bolero Maxx", cargo = "Industrial Hardware (Fasteners/Plates)",
                    route = "Gurugram → Delhi", distanceKm = 47, verdict = Verdict.PASSED,
                    timestamp = t(790), confidence = 0.988f,
                    items = listOf(CargoItem("Tool Steel Rods", "Bundled Lot", 32, 32)),
                ),
                InspectionRecord(
                    id = "VT-2024-8759", ewb = "EWB-5503-4419", vehicle = "RJ 14 GT 6612",
                    vehicleModel = "Ashok Leyland Dost", cargo = "Consumer Electronics (Smartphones/Tabs)",
                    route = "Jaipur → Delhi", distanceKm = 281, verdict = Verdict.PASSED,
                    timestamp = t(880), confidence = 0.961f,
                    items = listOf(CargoItem("Power Bank Cartons", "Shrink-wrapped", 28, 28)),
                ),
                InspectionRecord(
                    id = "VT-2024-8751", ewb = "EWB-4421-0027", vehicle = "WB 20 AC 3317",
                    vehicleModel = "Tata Ace Gold", cargo = "Textile Machinery & Spares",
                    route = "Kolkata → Durgapur", distanceKm = 178, verdict = Verdict.PASSED,
                    timestamp = t(975), confidence = 0.979f,
                    items = listOf(CargoItem("Spinning Bobbins", "Crate T-5", 44, 44)),
                ),
                InspectionRecord(
                    id = "VT-2024-8744", ewb = "EWB-3319-8806", vehicle = "UP 16 FT 7754",
                    vehicleModel = "BharatBenz 1217C", cargo = "Pharma Intermediates (Non-cool)",
                    route = "Noida → Agra", distanceKm = 233, verdict = Verdict.PASSED,
                    timestamp = t(1080), confidence = 0.993f,
                    items = listOf(CargoItem("HDPE Canisters 20L", "Sealed Bay 1", 20, 20)),
                ),
                InspectionRecord(
                    id = "VT-2024-8736", ewb = "EWB-2207-1199", vehicle = "GJ 01 RV 5508",
                    vehicleModel = "Eicher Pro 3015", cargo = "Auto Spare Parts (Assemblies)",
                    route = "Vadodara → Ahmedabad", distanceKm = 153, verdict = Verdict.PASSED,
                    timestamp = t(1200), confidence = 0.972f,
                    items = listOf(CargoItem("Radiator Assemblies", "Pallet P-7", 11, 11)),
                ),
            )
        )
    }

    val total: Int get() = records.size
    val passed: Int get() = records.count { it.verdict == Verdict.PASSED }
    val flagged: Int get() = records.count { it.verdict == Verdict.REVIEW }

    /** Result scenarios for the simulated cargo scan, starting with the canonical demo discrepancy. */
    fun nextScenario(manifest: Manifest): List<CargoItem> {
        runCount++
        val expected = manifest.items
        return when (if (runCount == 1) 0 else Random.nextInt(0, 3)) {
            0 -> expected.mapIndexed { i, it ->
                when (i) {
                    1 -> it.copy(found = (it.expected - 1).coerceAtLeast(0))
                    else -> it.copy(found = it.expected)
                }
            } + CargoItem("Thermal POS Printer", "Not on manifest", 0, 1)
            1 -> expected.map { it.copy(found = it.expected) }
            else -> expected.map { it.copy(found = it.expected) } +
                CargoItem("Carton — Unmarked Spare", "Not on manifest", 0, Random.nextInt(1, 3))
        }
    }

    fun commit(record: InspectionRecord) {
        records.add(0, record)
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
