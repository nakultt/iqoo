package com.veritransit.inspector.data

import android.content.Context
import com.veritransit.core.PodSubmission
import com.veritransit.core.ReasonCode
import com.veritransit.core.ScanEvent
import com.veritransit.core.ScanKind
import com.veritransit.core.ScanResult
import com.veritransit.inspector.crypto.LabelVerifier
import com.veritransit.inspector.data.local.*
import com.veritransit.inspector.net.ApiClient
import com.veritransit.inspector.scan.VerificationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * The device's source of truth (§8.2).
 *
 * Room is authoritative for what this phone has seen; the server is
 * authoritative for shipments, packages, documents and finance. Every scan
 * lands in the outbox **before** any network call, so killing the app
 * mid-session loses nothing — the §9 Phase 1 acceptance test is exactly that.
 */
class VeriTransitRepo private constructor(
    private val db: VeriTransitDatabase,
    private val settings: DeviceSettings,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    val outboxDepth: Flow<Int> get() = db.outbox().observePendingCount()

    fun shipments(): Flow<List<ShipmentEntity>> = db.shipments().observeAll()
    fun packages(ref: String): Flow<List<PackageEntity>> = db.packages().observeForShipment(ref)
    fun inners(master: String): Flow<List<PackageEntity>> = db.packages().observeChildrenOf(master)
    fun accounted(ref: String): Flow<Int> = db.packages().observeAccounted(ref)
    fun recentScans(ref: String): Flow<List<ScanOutboxEntity>> = db.outbox().observeForShipment(ref)

    suspend fun shipment(ref: String) = db.shipments().byRef(ref)
    suspend fun notYetScanned(ref: String) = db.packages().notYetScanned(ref)

    /** Builds the verification engine from whatever keys the last bootstrap pinned. */
    suspend fun engine(): VerificationEngine {
        val keys = db.keys().all().associate { it.keyId to it.publicKey }
        return VerificationEngine(LabelVerifier(keys), db.packages(), db.outbox())
    }

    suspend fun keysConfigured(): Boolean = db.keys().all().isNotEmpty()

    /**
     * Records a scan. Local first, always: the network is a courier for this
     * record, never its custodian.
     */
    suspend fun recordScan(
        packageCode: String,
        shipmentRef: String?,
        kind: ScanKind,
        result: ScanResult,
        reasons: List<ReasonCode>,
        aiFlags: Map<String, Boolean> = emptyMap(),
        evidenceUri: String? = null,
        evidenceSha256: String? = null,
        lat: Double? = null,
        lng: Double? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        db.outbox().enqueue(
            ScanOutboxEntity(
                clientEventId = id,
                packageCode = packageCode,
                shipmentRef = shipmentRef,
                kind = kind.name,
                result = result.name,
                reasons = reasons.joinToString(",") { it.name },
                aiFlags = json.encodeToString(aiFlags),
                evidenceUri = evidenceUri,
                evidenceSha256 = evidenceSha256,
                lat = lat, lng = lng,
                clientTs = Instant.now().toString(),
                officer = settings.officerName,
            )
        )

        if (result != ScanResult.REJECTED) {
            val local = when (kind) {
                ScanKind.LOAD -> "LOADED"
                ScanKind.RECEIVE -> "RECEIVED"
                else -> null
            }
            if (local != null) {
                db.packages().setLocalStatus(packageCode, local)
                // §2.1 — loading a master ticks its inner boxes too.
                val pkg = db.packages().byCode(packageCode)
                if (pkg != null && pkg.kind != "UNIT" && kind == ScanKind.LOAD) {
                    db.packages().setSubtreeStatus(packageCode, local)
                }
            }
        }
        return id
    }

    suspend fun savePodDraft(draft: PodDraftEntity) = db.pod().save(draft)
    suspend fun podDraft(ref: String) = db.pod().draft(ref)

    // ------------------------------------------------------------- sync

    private fun api(): ApiClient? {
        val base = settings.serverUrl ?: return null
        return ApiClient(base, settings.apiKey, settings.officerName)
    }

    /** Pushes pending scans; returns how many the server accepted. */
    suspend fun drainOutbox(): Int {
        val client = api() ?: return 0
        val pending = db.outbox().pending()
        if (pending.isEmpty()) return 0

        return try {
            val events = pending.map { row ->
                ScanEvent(
                    clientEventId = row.clientEventId,
                    packageCode = row.packageCode,
                    shipmentRef = row.shipmentRef,
                    kind = ScanKind.valueOf(row.kind),
                    result = ScanResult.valueOf(row.result),
                    reasons = row.reasons.split(",").filter { it.isNotBlank() }
                        .mapNotNull { runCatching { ReasonCode.valueOf(it) }.getOrNull() },
                    aiFlags = runCatching {
                        json.decodeFromString<Map<String, Boolean>>(row.aiFlags)
                            .mapValues { it.value.toString() }
                    }.getOrDefault(emptyMap()),
                    evidenceUri = row.evidenceUri,
                    evidenceSha256 = row.evidenceSha256,
                    lat = row.lat, lng = row.lng,
                    clientTs = row.clientTs,
                )
            }

            val response = client.pushScans(events)
            // The server's verdict is recorded beside the device's, not over it —
            // a disagreement is a discrepancy, not a correction (§6).
            db.outbox().markAllSent(
                response.acks.map {
                    Triple(
                        it.clientEventId,
                        it.serverResult.name,
                        it.serverReasons.joinToString(",") { r -> r.name },
                    )
                }
            )
            response.accepted
        } catch (t: Throwable) {
            pending.forEach { db.outbox().markFailed(it.clientEventId, t.message) }
            throw t
        } finally {
            client.close()
        }
    }

    /** §8.3 shift bootstrap — everything needed to scan a whole shift offline. */
    suspend fun refreshBootstrap(): Boolean {
        val client = api() ?: return false
        return try {
            val b = client.bootstrap(settings.site)
            val now = System.currentTimeMillis()

            db.keys().upsert(b.publicKeys.map {
                KeyConfigEntity(it.keyId, it.algorithm, it.publicKey, now)
            })
            db.keys().upsertBands(b.frictionBands.map { (band, rule) ->
                FrictionBandEntity(band, rule.photoEvidence, rule.supervisorSignoff, rule.innerVerification)
            })

            val risk = b.risk.associateBy { it.subjectId }
            val finance = b.finance.associateBy { it.shipmentRef }
            db.shipments().upsert(b.shipments.map { s ->
                ShipmentEntity(
                    ref = s.ref, vehicle = s.vehicle, origin = s.origin, destination = s.destination,
                    expectedCount = s.expectedCount, status = s.status.name,
                    supplier = s.supplier, buyer = s.buyer,
                    riskScore = risk[s.ref]?.score, riskBand = risk[s.ref]?.band?.name,
                    financeStatus = finance[s.ref]?.status?.name, heldValue = finance[s.ref]?.heldValue,
                    cachedAt = now,
                )
            })

            // Cached package rows are replaced wholesale, but the device's own
            // localStatus is preserved — a scan made offline must not be erased
            // by a bootstrap that predates it reaching the server.
            val existing = b.packages.map { it.packageCode }
                .mapNotNull { db.packages().byCode(it) }
                .associate { it.packageCode to it.localStatus }

            db.packages().upsert(b.packages.map { p ->
                PackageEntity(
                    packageCode = p.packageCode, shipmentRef = p.shipmentRef, kind = p.kind.name,
                    parentCode = p.parentCode, contents = p.contents, sku = p.sku, qty = p.qty,
                    poLineNo = p.poLineNo, status = p.status.name,
                    labelPayload = p.labelPayload, copyNo = p.copyNo,
                    localStatus = existing[p.packageCode],
                )
            })

            db.documents().upsert(b.documents.map { d ->
                DocumentFactEntity(
                    shipmentRef = d.shipmentRef, kind = d.kind.name, docNo = d.docNo,
                    docDate = d.docDate, factJson = json.encodeToString(d.fact),
                    confidence = d.confidence, readBy = d.readBy.name, confirmed = true,
                )
            })
            true
        } catch (_: Throwable) {
            false
        } finally {
            client.close()
        }
    }

    suspend fun submitPod(ref: String, receiver: String, hashes: Map<String, String>,
                          lat: Double?, lng: Double?): Boolean {
        val client = api() ?: return false
        return try {
            client.submitPod(
                PodSubmission(
                    shipmentRef = ref, scans = emptyList(), evidenceHashes = hashes,
                    receiverName = receiver, lat = lat, lng = lng,
                    deliveredAt = Instant.now().toString(),
                )
            )
            db.pod().save(
                PodDraftEntity(ref, receiver, null, json.encodeToString(hashes), lat, lng,
                    Instant.now().toString(), submitted = true)
            )
            true
        } catch (_: Throwable) {
            false
        } finally {
            client.close()
        }
    }

    /**
     * §12 — a bootstrap older than a day cannot be trusted to gate a release.
     * The scanner keeps working; the *authority* of its reconciliation does not.
     */
    suspend fun cacheIsStale(): Boolean {
        val oldest = db.shipments().oldestCacheAt() ?: return true
        return System.currentTimeMillis() - oldest > 24 * 60 * 60 * 1000
    }

    suspend fun hasCachedData(): Boolean = db.shipments().observeAll().first().isNotEmpty()

    companion object {
        @Volatile private var instance: VeriTransitRepo? = null

        fun get(context: Context): VeriTransitRepo = instance ?: synchronized(this) {
            instance ?: VeriTransitRepo(
                VeriTransitDatabase.get(context),
                DeviceSettings(context),
            ).also { instance = it }
        }
    }
}

/** Device identity and server address, in plain SharedPreferences. */
class DeviceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("veritransit", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(v) = prefs.edit().putString("server_url", v).apply()

    var apiKey: String?
        get() = prefs.getString("api_key", null)
        set(v) = prefs.edit().putString("api_key", v).apply()

    var officerName: String?
        get() = prefs.getString("officer", null)
        set(v) = prefs.edit().putString("officer", v).apply()

    var site: String?
        get() = prefs.getString("site", null)
        set(v) = prefs.edit().putString("site", v).apply()

    /** Keeps the existing simulated scanner available behind a flag (§9 Phase 0). */
    var demoMode: Boolean
        get() = prefs.getBoolean("demo_mode", true)
        set(v) = prefs.edit().putBoolean("demo_mode", v).apply()
}
