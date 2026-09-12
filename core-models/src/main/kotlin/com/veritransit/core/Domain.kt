package com.veritransit.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The platform vocabulary, shared by device, backend, bot and agent.
 *
 * These names are also the database's — the Postgres enums in
 * `db/migrations/V1__core_schema.sql` use the same spellings — so a value that
 * round-trips through JSON and SQL never needs translating on the way.
 */

// ----------------------------------------------------------- shipments

@Serializable
enum class ShipmentStatus { OPEN, LOADING, DISPATCHED, RECEIVED, FLAGGED }

/** §4.4 — a package is a UNIT inside a MASTER, optionally stacked on a PALLET. */
@Serializable
enum class PackageKind { UNIT, MASTER, PALLET }

@Serializable
enum class PackageStatus { CREATED, PRINTED, LOADED, RECEIVED, MISSING, FLAGGED }

@Serializable
enum class ScanKind { LOAD, RECEIVE, FIELD, POD }

/** §4.3 — the AI may raise SUSPECT_REVIEW but can never produce VERIFIED alone. */
@Serializable
enum class ScanResult { VERIFIED, SUSPECT_REVIEW, REJECTED }

@Serializable
enum class ScanSource { DEVICE, SERVER, WEB }

@Serializable
enum class SiteKind { WAREHOUSE, GATE, DELIVERY }

@Serializable
enum class UserRole { ADMIN, SUPERVISOR, OFFICER, PACKER, FINANCE_MAKER, FINANCE_CHECKER }

/**
 * §4.3 / §4.4 reason codes. A verdict is never just a colour — it always names
 * why, because an officer under time pressure will otherwise treat SUSPECT as
 * PASS (§12 risk register).
 */
@Serializable
enum class ReasonCode(val message: String) {
    SIGNATURE_INVALID("Label signature does not verify — not issued by this platform"),
    WRONG_SHIPMENT("Label belongs to a different shipment"),
    QR_BARCODE_MISMATCH("QR and barcode disagree — label may have been moved"),
    DUPLICATE_LABEL("This package code was already scanned"),
    REPRINT_SUPERSEDED("An older copy of a reprinted label"),
    VISUAL_TAMPER("Photo shows tampering — reseal or torn label"),
    NOT_IN_MANIFEST("Package is not on this shipment's list"),
    INNER_SHORTAGE("Master declares more inner boxes than were found"),
    INNER_MISMATCH("Inner box belongs to a different master"),
    INNER_UNLISTED("Extra box found inside a sealed master"),
    CONTENTS_MISMATCH("Contents do not match the declaration"),
    ;

    /** Whether this code alone is enough to refuse the package outright. */
    val rejects: Boolean get() = this == SIGNATURE_INVALID
}

// ----------------------------------------------------------- entities

@Serializable
data class Shipment(
    val ref: String,
    val vehicle: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val expectedCount: Int = 0,
    val status: ShipmentStatus = ShipmentStatus.OPEN,
    val supplier: String? = null,
    val buyer: String? = null,
    val dispatchedAt: String? = null,
    val receivedAt: String? = null,
)

@Serializable
data class PackageRecord(
    val packageCode: String,
    val shipmentRef: String,
    val kind: PackageKind = PackageKind.UNIT,
    val parentCode: String? = null,
    val contents: String? = null,
    val sku: String? = null,
    val hsn: String? = null,
    val qty: Int = 1,
    val poLineNo: Int? = null,
    val status: PackageStatus = PackageStatus.CREATED,
    /** The signed token printed in the QR (§4.2); absent until a label is issued. */
    val labelPayload: String? = null,
    val copyNo: Int = 1,
)

@Serializable
data class ScanEvent(
    /** Device-generated; makes ingest idempotent however many times the outbox retries. */
    @SerialName("client_event_id") val clientEventId: String,
    @SerialName("package_code") val packageCode: String,
    @SerialName("shipment_ref") val shipmentRef: String? = null,
    val kind: ScanKind,
    val result: ScanResult,
    val reasons: List<ReasonCode> = emptyList(),
    @SerialName("ai_flags") val aiFlags: Map<String, String> = emptyMap(),
    @SerialName("evidence_uri") val evidenceUri: String? = null,
    @SerialName("evidence_sha256") val evidenceSha256: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("client_ts") val clientTs: String,
    /** The raw §4.2 token as scanned (`VT1|P=…|SIG=…`), when the device read a
     *  QR. The server re-verifies the signature against its own keys — the
     *  device's verdict is an observation, never the authority (§6). When
     *  absent the server falls back to the label it issued itself. */
    @SerialName("label_token") val labelToken: String? = null,
)

@Serializable
data class Discrepancy(
    val id: String,
    val shipmentRef: String,
    val kind: String,
    val packageCode: String? = null,
    val severity: String = "MEDIUM",
    val detail: Map<String, String> = emptyMap(),
    val detectedAt: String,
    val resolvedAt: String? = null,
    val resolution: String? = null,
)

// ----------------------------------------------------------- commerce

@Serializable
enum class FinanceStatus { AWAITING, VERIFIED, HELD, RELEASE_PENDING, RELEASED }

@Serializable
enum class RiskBand { LOW, MEDIUM, HIGH }

/** §5.3 — bands drive friction, and the thresholds live in exactly one place. */
object RiskBands {
    fun of(score: Int): RiskBand = when {
        score <= 30 -> RiskBand.LOW
        score <= 60 -> RiskBand.MEDIUM
        else -> RiskBand.HIGH
    }
}

@Serializable
data class RiskFactor(val factor: String, val weight: Int, val detail: String)

@Serializable
data class RiskScore(
    val subjectKind: String,
    val subjectId: String,
    val score: Int,
    val band: RiskBand,
    /** §5.3: explainability is mandatory — a score without factors is not shippable. */
    val factors: List<RiskFactor>,
    val computedAt: String? = null,
)

@Serializable
data class FinanceState(
    val shipmentRef: String,
    val currency: String = "INR",
    val orderValue: Double,
    val status: FinanceStatus,
    val releasedValue: Double = 0.0,
    val heldValue: Double = 0.0,
    val terms: Map<String, String> = emptyMap(),
)
