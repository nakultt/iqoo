package com.veritransit.inspector.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §8.2 — the device mirror and the outbox.
 *
 * The split of authority matters: the server owns shipment, package, document
 * and finance rows and the device only caches them; the device owns its own
 * scan and PoD events until they are acked. That is why [ScanOutboxEntity]
 * carries a sync state and the cached tables do not — losing a cached row costs
 * a re-sync, losing a scan costs evidence.
 */

@Entity(tableName = "shipment")
data class ShipmentEntity(
    @PrimaryKey val ref: String,
    val vehicle: String?,
    val origin: String?,
    val destination: String?,
    val expectedCount: Int,
    val status: String,
    val supplier: String?,
    val buyer: String?,
    val riskScore: Int?,
    val riskBand: String?,
    val financeStatus: String?,
    val heldValue: Double?,
    val cachedAt: Long,
)

@Entity(
    tableName = "package_record",
    indices = [Index("shipmentRef"), Index("parentCode")],
)
data class PackageEntity(
    @PrimaryKey val packageCode: String,
    val shipmentRef: String,
    val kind: String,
    val parentCode: String?,
    val contents: String?,
    val sku: String?,
    val qty: Int,
    val poLineNo: Int?,
    val status: String,
    val labelPayload: String?,
    val copyNo: Int,
    /** Set locally the moment a box is scanned, before the server has seen it. */
    val localStatus: String?,
)

/**
 * The outbox. `clientEventId` is the idempotency key the server dedupes on, so
 * a retry storm over flaky dock Wi-Fi can never create duplicate scan history.
 */
@Entity(
    tableName = "scan_outbox",
    indices = [Index(value = ["clientEventId"], unique = true), Index("shipmentRef")],
)
data class ScanOutboxEntity(
    @PrimaryKey val clientEventId: String,
    val packageCode: String,
    val shipmentRef: String?,
    val kind: String,
    val result: String,
    val reasons: String,          // comma-separated ReasonCode names
    val aiFlags: String,          // JSON
    val evidenceUri: String?,
    val evidenceSha256: String?,
    val lat: Double?,
    val lng: Double?,
    val clientTs: String,
    val officer: String?,
    /** PENDING → SENT. A row is only deleted once the server has acked it. */
    val syncState: String = "PENDING",
    val attempts: Int = 0,
    val serverResult: String? = null,
    val serverReasons: String? = null,
    val lastError: String? = null,
)

@Entity(tableName = "document_fact", primaryKeys = ["shipmentRef", "kind", "docNo"])
data class DocumentFactEntity(
    val shipmentRef: String,
    val kind: String,
    val docNo: String,
    val docDate: String?,
    val factJson: String,
    val confidence: Double?,
    val readBy: String,
    val confirmed: Boolean,
)

@Entity(tableName = "pod_draft")
data class PodDraftEntity(
    @PrimaryKey val shipmentRef: String,
    val receiverName: String?,
    val signatureUri: String?,
    val evidenceHashes: String,   // JSON map
    val lat: Double?,
    val lng: Double?,
    val capturedAt: String?,
    val submitted: Boolean = false,
)

/** The existing gate-inspection record, now persisted rather than in memory. */
@Entity(tableName = "inspection_record")
data class InspectionRecordEntity(
    @PrimaryKey val id: String,
    val ewb: String,
    val vehicle: String,
    val vehicleModel: String,
    val cargo: String,
    val route: String,
    val distanceKm: Int,
    val verdict: String,
    val timestamp: Long,
    val itemsJson: String,
    val confidence: Float,
    val note: String,
    val noticeIssued: Boolean,
)

/** §4.2 — the pinned public keys, refreshed at bootstrap and rotatable. */
@Entity(tableName = "key_config")
data class KeyConfigEntity(
    @PrimaryKey val keyId: String,
    val algorithm: String,
    val publicKey: String,
    val cachedAt: Long,
)

/** Friction per risk band (§5.3), so verification depth works offline. */
@Entity(tableName = "friction_band")
data class FrictionBandEntity(
    @PrimaryKey val band: String,
    val photoEvidence: String,
    val supervisorSignoff: Boolean,
    val innerVerification: String,
)
