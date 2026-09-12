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
 * Domain model — mirrors the receiving app's data layer and the telegram-bot's
 * copy, so all three surfaces tell the same story for every delivery. Kept
 * dependency-free apart from the JSON annotations used by the ingest API.
 */
enum class ReceiptOutcome { OK, SHORT, OVER, MISMATCH, PENDING }

enum class ItemStatus { MATCHED, SHORT, OVER, UNLISTED, DAMAGED }

@Serializable
data class PackingItem(
    val sku: String = "",
    val name: String,
    val detail: String = "",
    val expected: Int = 0,
    val received: Int = 0,
    val damaged: Int = 0,
) {
    val status: ItemStatus
        get() = when {
            // Goods with no declared line at all: the packing list never
            // mentioned them, so there is no expectation to compare against.
            expected <= 0 && received > 0 -> ItemStatus.UNLISTED
            damaged > 0 -> ItemStatus.DAMAGED
            received > expected -> ItemStatus.OVER
            received < expected -> ItemStatus.SHORT
            else -> ItemStatus.MATCHED
        }

    /** Signed difference between received and packed, for the short/over chips. */
    val delta: Int get() = received - expected
}

/**
 * Goods-received note: a supplier packed against a purchase order and a
 * packing list, the warehouse booked what actually arrived.
 */
@Serializable
data class ReceivingRecord(
    val id: String,
    @SerialName("purchase_order") val purchaseOrderId: String = "",
    @SerialName("packing_list") val packingListId: String = "",
    val supplier: String = "",
    val goods: String = "",
    val dock: String = "",
    val carrier: String = "",
    val outcome: ReceiptOutcome = ReceiptOutcome.PENDING,
    val timestamp: Long = 0L,
    val items: List<PackingItem> = emptyList(),
    val confidence: Float = 0f,
    val note: String = "",
    val receiver: String = RECEIVER,
    val warehouse: String = WAREHOUSE,
    // Proof frames from the dock camera, carried as base64 JPEG on the wire
    // and embedded into the PDF report as-is. Absent on seeded records.
    @Serializable(with = EvidenceSerializer::class)
    @SerialName("list_evidence") val listEvidence: ByteArray? = null,
    @Serializable(with = EvidenceSerializer::class)
    @SerialName("dock_evidence") val dockEvidence: ByteArray? = null,
) {
    val flagged: Boolean get() = outcome != ReceiptOutcome.OK && outcome != ReceiptOutcome.PENDING
    val discrepancyCount: Int get() = items.count { it.status != ItemStatus.MATCHED }
    val totalUnits: Int get() = items.filter { it.expected > 0 }.sumOf { it.expected }

    companion object {
        const val WAREHOUSE = "Central Warehouse"
        const val RECEIVER = "Goods-In Clerk"
    }
}

/**
 * What a receipt outcome means downstream: an accepted delivery is booked into
 * stock and its invoice is clear for payment; a flagged one is held on the dock
 * and the payment is not; a pending one is simply still being counted.
 */
enum class ShipState(val label: String, val paymentLabel: String, val paymentOk: Boolean) {
    SHIPPED("Accepted", "OK TO PAY", true),
    HELD("Held on dock", "DO NOT PAY", false),
    AWAITING("Awaiting count", "PAYMENT ON HOLD", false);

    companion object {
        fun of(outcome: ReceiptOutcome): ShipState = when (outcome) {
            ReceiptOutcome.OK -> SHIPPED
            ReceiptOutcome.PENDING -> AWAITING
            else -> HELD
        }
    }
}
