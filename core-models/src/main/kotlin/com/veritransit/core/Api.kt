package com.veritransit.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire contracts for the §8.3 API. One definition, used by server, app and bot. */

// ------------------------------------------------------- device lifecycle

@Serializable
data class ActivateRequest(@SerialName("activation_code") val activationCode: String, val label: String? = null)

@Serializable
data class ActivateResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("api_key") val apiKey: String,
    // Defaulted, not merely nullable: the server serialises with
    // `explicitNulls = false`, so a null field is *absent* from the JSON rather
    // than present as null — and kotlinx.serialization rejects a missing field
    // that has no default, however nullable its type.
    val site: String? = null,
)

/**
 * §8.3 `GET /v1/sync/bootstrap` — everything a phone needs to scan a whole
 * shift with no network: the shipments, every package and its signed label, the
 * public key to verify them, and the risk bands that set verification depth.
 */
@Serializable
data class BootstrapResponse(
    @SerialName("server_time") val serverTime: String,
    @SerialName("public_keys") val publicKeys: List<PublicKeyEntry>,
    val shipments: List<Shipment>,
    val packages: List<PackageRecord>,
    val documents: List<ShipmentDocument> = emptyList(),
    val risk: List<RiskScore> = emptyList(),
    val finance: List<FinanceState> = emptyList(),
    /** §5.3 friction per band — the device reads this to decide scan depth. */
    @SerialName("friction_bands") val frictionBands: Map<String, FrictionRule> = emptyMap(),
)

@Serializable
data class PublicKeyEntry(
    @SerialName("key_id") val keyId: String,
    val algorithm: String = "Ed25519",
    @SerialName("public_key") val publicKey: String,
)

@Serializable
data class FrictionRule(
    @SerialName("photo_evidence") val photoEvidence: String = "spot",
    @SerialName("supervisor_signoff") val supervisorSignoff: Boolean = false,
    /** `master_scan_only` or `open_and_verify` (§4.4 verification depth). */
    @SerialName("inner_verification") val innerVerification: String = "master_scan_only",
)

@Serializable
data class ShipmentDocument(
    val id: String? = null,
    @SerialName("shipment_ref") val shipmentRef: String,
    val kind: DocumentKind,
    @SerialName("doc_no") val docNo: String,
    @SerialName("doc_date") val docDate: String? = null,
    val fact: DocumentFact,
    @SerialName("source_uri") val sourceUri: String? = null,
    @SerialName("read_by") val readBy: DocumentReader = DocumentReader.MANUAL,
    val confidence: Double? = null,
)

// ------------------------------------------------------- scans

@Serializable
data class ScanBatchRequest(val events: List<ScanEvent>)

/**
 * The server's own verdict per event. It is returned rather than applied
 * silently: §6 "the device decides in real time; the backend decides
 * authoritatively — conflicts raise discrepancies, never silently overwrite".
 */
@Serializable
data class ScanAck(
    @SerialName("client_event_id") val clientEventId: String,
    val accepted: Boolean,
    @SerialName("server_result") val serverResult: ScanResult,
    @SerialName("server_reasons") val serverReasons: List<ReasonCode> = emptyList(),
    /** True when the device's verdict and the server's disagreed. */
    val conflict: Boolean = false,
    val duplicate: Boolean = false,
)

@Serializable
data class ScanBatchResponse(val acks: List<ScanAck>, val accepted: Int, val rejected: Int)

// ------------------------------------------------------- shipments & labels

@Serializable
data class CreateShipmentRequest(
    val ref: String? = null,
    val vehicle: String? = null,
    @SerialName("origin_site") val originSite: String? = null,
    @SerialName("dest_site") val destSite: String? = null,
    val supplier: String? = null,
    val buyer: String? = null,
    val transporter: String? = null,
    @SerialName("po_no") val poNo: String? = null,
)

@Serializable
data class IssueLabelsRequest(
    val lines: List<LabelLine>,
)

@Serializable
data class LabelLine(
    @SerialName("po_line_no") val poLineNo: Int,
    val sku: String,
    val contents: String,
    val hsn: String? = null,
    /** How many master cartons to create for this line. */
    val masters: Int,
    /** Inner units per master; 1 means the carton is itself the saleable unit. */
    @SerialName("units_per_master") val unitsPerMaster: Int = 1,
    /** False when inners are supplier-preprinted and only the master is labelled (§4.4). */
    @SerialName("label_inners") val labelInners: Boolean = true,
)

@Serializable
data class IssuedLabel(
    @SerialName("package_code") val packageCode: String,
    val kind: PackageKind,
    @SerialName("parent_code") val parentCode: String? = null,
    val payload: String,
    val contents: String? = null,
    val qty: Int = 1,
)

@Serializable
data class IssueLabelsResponse(val shipmentRef: String, val labels: List<IssuedLabel>, val issued: Int)

/** §8.3 `POST /v1/packages:close-master` — the pack-station linchpin (§4.5). */
@Serializable
data class CloseMasterRequest(
    @SerialName("shipment_ref") val shipmentRef: String,
    @SerialName("child_codes") val childCodes: List<String>,
    val contents: String? = null,
    @SerialName("po_line_no") val poLineNo: Int? = null,
)

@Serializable
data class ShipmentReport(
    val shipment: Shipment,
    @SerialName("expected_count") val expectedCount: Int,
    val accounted: Int,
    val missing: List<String> = emptyList(),
    val extra: List<String> = emptyList(),
    val flagged: List<String> = emptyList(),
    @SerialName("inner_units") val innerUnits: Int = 0,
    @SerialName("inner_verified") val innerVerified: Int = 0,
    val discrepancies: List<Discrepancy> = emptyList(),
    val risk: RiskScore? = null,
    val finance: FinanceState? = null,
    val complete: Boolean = false,
)

// ------------------------------------------------------- finance

@Serializable
data class ReleaseRequest(
    val amount: Double? = null,
    val note: String? = null,
    /** Maker-checker: the checker must be a different user than the maker (§5.2). */
    val actor: String? = null,
)

@Serializable
data class FinanceActionResponse(
    val shipmentRef: String,
    val status: FinanceStatus,
    @SerialName("released_value") val releasedValue: Double,
    @SerialName("held_value") val heldValue: Double,
    @SerialName("certificate_id") val certificateId: String? = null,
    val message: String,
)

// ------------------------------------------------------- proof of delivery

@Serializable
data class PodSubmission(
    @SerialName("shipment_ref") val shipmentRef: String,
    val scans: List<ScanEvent> = emptyList(),
    @SerialName("evidence_hashes") val evidenceHashes: Map<String, String> = emptyMap(),
    @SerialName("receiver_name") val receiverName: String,
    @SerialName("signature_uri") val signatureUri: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("delivered_at") val deliveredAt: String,
)

@Serializable
data class PodCertificate(
    val id: String,
    @SerialName("shipment_ref") val shipmentRef: String,
    val signature: String,
    @SerialName("key_id") val keyId: String,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("evidence_hashes") val evidenceHashes: Map<String, String> = emptyMap(),
    @SerialName("match_result") val matchResult: Map<String, String> = emptyMap(),
    @SerialName("pdf_uri") val pdfUri: String? = null,
)

// ------------------------------------------------------- agent & audit

@Serializable
data class AgentStep(val step: String, val detail: String)

@Serializable
data class AgentAction(
    val id: String,
    @SerialName("shipment_ref") val shipmentRef: String? = null,
    @SerialName("trigger_event") val triggerEvent: String,
    val steps: List<AgentStep> = emptyList(),
    val outcome: String,
    @SerialName("reasoning_summary") val reasoningSummary: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class AuditEntry(
    val seq: Long,
    val at: String,
    val actor: String,
    val action: String,
    val subject: String,
    @SerialName("prev_hash") val prevHash: String? = null,
    val hash: String,
)

@Serializable
data class AuditChainStatus(val ok: Boolean, val checked: Long, val firstBadSeq: Long? = null, val detail: String)

@Serializable
data class ApiError(val error: String, val detail: String? = null)
