package com.veritransit.dashboard

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * Evidence frames on the wire: base64 text, empty string for "none" (rather
 * than JSON null, so a client that omits the field and one that sends ""
 * land in the same place). The bytes themselves are never transformed — the
 * PDF embeds exactly what the field camera produced, which is what keeps the
 * report deterministic.
 */
object EvidenceSerializer : KSerializer<ByteArray?> {
    override val descriptor = PrimitiveSerialDescriptor("veritransit.EvidenceJpeg", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ByteArray? {
        val text = decoder.decodeString()
        return if (text.isEmpty()) null else Base64.getDecoder().decode(text)
    }

    override fun serialize(encoder: Encoder, value: ByteArray?) {
        encoder.encodeString(if (value == null) "" else Base64.getEncoder().encodeToString(value))
    }
}

/**
 * Domain model — mirrors the inspector app's data layer and the telegram-bot's
 * copy, so all three surfaces tell the same story for every consignment. Kept
 * dependency-free apart from the JSON annotations used by the ingest API.
 */
enum class Verdict { PASSED, REVIEW, PENDING }

enum class ItemStatus { MATCHED, SHORTAGE, OVERAGE, UNLISTED }

@Serializable
data class CargoItem(
    val name: String,
    val detail: String = "",
    val expected: Int = 0,
    val found: Int = 0,
) {
    val status: ItemStatus
        get() = when {
            expected <= 0 && found > 0 -> ItemStatus.UNLISTED
            found > expected -> ItemStatus.OVERAGE
            found < expected -> ItemStatus.SHORTAGE
            else -> ItemStatus.MATCHED
        }
}

@Serializable
data class InspectionRecord(
    val id: String,
    val ewb: String,
    val vehicle: String,
    @SerialName("vehicle_model") val vehicleModel: String = "",
    val cargo: String = "",
    val route: String = "",
    @SerialName("distance_km") val distanceKm: Int = 0,
    val verdict: Verdict,
    val timestamp: Long = 0L,
    val items: List<CargoItem> = emptyList(),
    val confidence: Float = 0f,
    val note: String = "",
    val inspector: String = INSPECTOR,
    val badge: String = BADGE,
    val station: String = STATION,
    // Proof frames from the field camera, carried as base64 JPEG on the wire
    // and embedded into the PDF report as-is. Absent on seeded records.
    @Serializable(with = EvidenceSerializer::class)
    @SerialName("bill_evidence") val billEvidence: ByteArray? = null,
    @Serializable(with = EvidenceSerializer::class)
    @SerialName("cargo_evidence") val cargoEvidence: ByteArray? = null,
) {
    val flagged: Boolean get() = verdict == Verdict.REVIEW
    val discrepancyCount: Int get() = items.count { it.status != ItemStatus.MATCHED }
    val totalUnits: Int get() = items.filter { it.expected > 0 }.sumOf { it.expected }

    companion object {
        const val STATION = "NH-48 Tollgate"
        const val INSPECTOR = "Insp. S. Jenkins"
        const val BADGE = "#412"
    }
}

/**
 * What a verdict means downstream: released consignments are on the road and
 * their invoices are clear for payment; flagged ones hold the gate and the
 * payment; pending ones are simply waiting.
 */
enum class ShipState(val label: String, val paymentLabel: String, val paymentOk: Boolean) {
    SHIPPED("Shipped", "OK TO PAY", true),
    HELD("Held at gate", "DO NOT PAY", false),
    AWAITING("Awaiting inspection", "PAYMENT ON HOLD", false);

    companion object {
        fun of(verdict: Verdict): ShipState = when (verdict) {
            Verdict.PASSED -> SHIPPED
            Verdict.REVIEW -> HELD
            Verdict.PENDING -> AWAITING
        }
    }
}
