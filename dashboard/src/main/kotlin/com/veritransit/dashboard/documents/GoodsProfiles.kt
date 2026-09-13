package com.veritransit.dashboard.documents

/**
 * What differs between an electronics consignment and a fabric one.
 *
 * The law in [DocumentRegistry] is the same for both. What differs is which of
 * its branches a receiving dock actually meets, and which exemptions people
 * assume apply when they do not. Every note carries its provision, so a
 * caution that rests on a secondary or unverified source says so wherever it
 * is shown.
 */
data class ProfileNote(val text: String, val provision: Provision)

data class GoodsProfile(
    val category: GoodsCategory,
    /** Movement shapes that are routine for these goods — the questions worth asking at the gate. */
    val routine: List<String>,
    val notes: List<ProfileNote>,
)

object GoodsProfiles {

    val ELECTRONICS = GoodsProfile(
        GoodsCategory.ELECTRONICS,
        routine = listOf(
            "High value in few cartons — a single carton can cross the e-way bill threshold",
            "Sets and machines shipped SKD/CKD or in lots",
            "Imported components and finished devices",
        ),
        notes = listOf(
            ProfileNote(
                "A consignment in lots gets its original invoice only with the last lot; " +
                    "earlier lots carry a challan and a certified copy of the invoice.",
                Provisions.R55_LOTS,
            ),
            ProfileNote("Imported goods travel with their bill of entry.", Provisions.R138A_BILL_OF_ENTRY),
            ProfileNote(
                "'Hearing aids' were in the Annexure as first notified but not in the one in force. " +
                    "Do not treat them as e-way-bill-free until the r.138(14)(e) Schedule is checked.",
                Provisions.FIRST_ANNEXURE,
            ),
            ProfileNote(
                "Retail boxes inside a master carton carry their own declarations; the bulk " +
                    "consignment should not be flagged for a missing MRP.",
                Provisions.LMPC_RETAIL,
            ),
        ),
    )

    val FABRIC = GoodsProfile(
        GoodsCategory.FABRIC,
        routine = listOf(
            "Job work — weaving, dyeing, printing, processing — out and back under challan",
            "Supplies from unregistered weavers",
            "Rolls and bales rather than retail packs",
        ),
        notes = listOf(
            ProfileNote(
                "Fabric sent to a job worker in another State needs an e-way bill whatever its value.",
                Provisions.R138_JOB_WORK,
            ),
            ProfileNote(
                "Inputs out on job work must be back within a year, or they are deemed supplied " +
                    "on the day they left.",
                Provisions.S143_INPUTS,
            ),
            ProfileNote(
                "Buying from an unregistered weaver makes the registered buyer the one causing " +
                    "the movement — the buyer owes the e-way bill.",
                Provisions.R138_RECIPIENT,
            ),
            ProfileNote(
                "The Annexure as first notified listed 'Khadi yarn', not khadi fabric, and the one " +
                    "in force lists neither. Any exemption would now come through r.138(14)(e), " +
                    "which is not yet read.",
                Provisions.FIRST_ANNEXURE,
            ),
            ProfileNote(
                "Rolls and bales are not packages for retail sale; retail-pack declarations " +
                    "should not be demanded of them.",
                Provisions.LMPC_RETAIL,
            ),
        ),
    )

    fun of(category: GoodsCategory): GoodsProfile = when (category) {
        GoodsCategory.ELECTRONICS -> ELECTRONICS
        GoodsCategory.FABRIC -> FABRIC
    }
}
