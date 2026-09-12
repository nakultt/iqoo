package com.veritransit.inspector.data.documents

/**
 * The registry evaluated against one consignment: what the law requires, what
 * commerce expects, what cannot be decided until a missing fact is supplied,
 * and which open research gaps sit under the answer.
 */
data class Paperwork(
    val consignment: Consignment,
    val required: List<Requirement>,
    val customary: List<Requirement>,
    /** Requirements whose trigger needs a fact this consignment does not carry yet. */
    val undetermined: List<Requirement>,
    val profile: GoodsProfile,
    val gaps: List<OpenGap>,
) {
    /** Legal requirements resting on anything less than the government's own text. */
    val weaklySourced: List<Requirement>
        get() = required.filter { it.provision?.verification != Verification.PRIMARY }

    fun has(id: String): Boolean = required.any { it.id == id } || customary.any { it.id == id }
}

object ConsignmentPaperwork {

    fun resolve(
        consignment: Consignment,
        registry: List<Requirement> = DocumentRegistry.ALL,
        gaps: List<OpenGap> = DocumentRegistry.GAPS,
    ): Paperwork {
        val applies = mutableListOf<Requirement>()
        val undetermined = mutableListOf<Requirement>()
        for (requirement in registry) {
            when (requirement.trigger.test(consignment)) {
                true -> applies += requirement
                null -> undetermined += requirement
                false -> Unit
            }
        }

        // A gap matters only where it could change an answer this consignment
        // actually gets — including an undetermined one.
        val touched = (applies + undetermined).mapTo(HashSet()) { it.id }

        return Paperwork(
            consignment = consignment,
            required = applies.filter { it.obligation == Obligation.LEGALLY_REQUIRED },
            customary = applies.filter { it.obligation == Obligation.CUSTOMARY },
            undetermined = undetermined,
            profile = GoodsProfiles.of(consignment.category),
            gaps = gaps.filter { gap ->
                gap.affects.any { it in touched } && !(gap.intraStateOnly && consignment.interState)
            },
        )
    }
}
