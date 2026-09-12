package com.veritransit.core

/**
 * Shared wording for spoken + Telegram voice alerts.
 *
 * Lives in core-models so the phone's speech, the Telegram voice caption and
 * the bot's fallback text all say the same thing: one builder, used by the
 * app ([VoiceAnnouncer]), the server and the bot. Pure Kotlin, unit-tested.
 *
 * The speech is deliberately short and front-loaded: a supervisor half-hearing
 * a dock announcement must get verdict + code + reason in the first sentence.
 */
object VoiceAlertText {

    /** What the phone speaks the moment a scan verdict lands. Null = stay silent. */
    fun forScan(
        result: ScanResult,
        packageCode: String?,
        reasons: List<ReasonCode>,
        announcePasses: Boolean = false,
    ): String? = when (result) {
        ScanResult.VERIFIED ->
            if (announcePasses) "Verified${packageCode?.let { ", $it" } ?: ""}." else null
        ScanResult.SUSPECT_REVIEW -> buildString {
            append("Suspect package")
            if (!packageCode.isNullOrBlank()) append(", $packageCode")
            append(". ")
            append(reasonSentence(reasons))
        }
        ScanResult.REJECTED -> buildString {
            append("Rejected")
            if (!packageCode.isNullOrBlank()) append(", $packageCode")
            append(". ")
            append(reasonSentence(reasons))
            append(" Do not load this carton.")
        }
    }

    /** What the phone speaks for a gate inspection result. Null = stay silent. */
    fun forGate(
        ewb: String,
        flagged: List<String>,
        unlisted: Int,
    ): String? {
        if (flagged.isEmpty() && unlisted == 0) return null
        return buildString {
            append("Inspection attention. E-Way Bill $ewb. ")
            if (flagged.isNotEmpty()) {
                append("${flagged.size} ${if (flagged.size == 1) "line" else "lines"} discrepant: ")
                append(flagged.take(3).joinToString("; "))
                append(". ")
            }
            if (unlisted > 0) append("$unlisted unlisted ${if (unlisted == 1) "parcel" else "parcels"} observed. ")
            append("Hold for review.")
        }
    }

    /**
     * Counted variant for the neural (Kokoro) path: item names are free text
     * no fixed phoneme table can cover, so the neural voice speaks counts
     * while the system-TTS path keeps the full names from [forGate].
     */
    fun forGateCounted(
        ewbDigits: String,
        lineCount: Int,
        unlistedCount: Int,
    ): String? {
        if (lineCount == 0 && unlistedCount == 0) return null
        return buildString {
            append("Inspection attention. E-Way Bill $ewbDigits. ")
            if (lineCount > 0) {
                append("$lineCount ${if (lineCount == 1) "line" else "lines"} discrepant. ")
            }
            if (unlistedCount > 0) {
                append("$unlistedCount unlisted ${if (unlistedCount == 1) "parcel" else "parcels"} observed. ")
            }
            append("Hold for review.")
        }
    }

    /** Caption that travels with the Telegram voice message. */    fun telegramCaption(
        shipmentRef: String?,
        packageCode: String?,
        result: ScanResult,
        reasons: List<ReasonCode>,
    ): String = buildString {
        append("🔊 Voice alert")
        if (!shipmentRef.isNullOrBlank()) append(" · $shipmentRef")
        appendLine()
        append("$result")
        if (!packageCode.isNullOrBlank()) append(" · $packageCode")
        if (reasons.isNotEmpty()) {
            appendLine()
            append(reasons.joinToString(" · ") { it.message })
        }
    }

    private fun reasonSentence(reasons: List<ReasonCode>): String {
        if (reasons.isEmpty()) return "Held for review."
        return reasons.take(2).joinToString(" ") { it.spoken } + " Held for review."
    }
}

/** Short spoken form of each reason — written to be heard, not read. */
val ReasonCode.spoken: String
    get() = when (this) {
        ReasonCode.SIGNATURE_INVALID -> "Label signature does not verify. Not issued by this platform."
        ReasonCode.WRONG_SHIPMENT -> "Label belongs to a different shipment."
        ReasonCode.QR_BARCODE_MISMATCH -> "QR and barcode disagree. Label may have been moved."
        ReasonCode.DUPLICATE_LABEL -> "This package code was already scanned."
        ReasonCode.REPRINT_SUPERSEDED -> "An older copy of a reprinted label."
        ReasonCode.VISUAL_TAMPER -> "Photo shows tampering."
        ReasonCode.NOT_IN_MANIFEST -> "Package is not on this shipment's list."
        ReasonCode.INNER_SHORTAGE -> "Master declares more inner boxes than were found."
        ReasonCode.INNER_MISMATCH -> "Inner box belongs to a different master."
        ReasonCode.INNER_UNLISTED -> "Extra box found inside a sealed master."
        ReasonCode.CONTENTS_MISMATCH -> "Contents do not match the declaration."
    }
