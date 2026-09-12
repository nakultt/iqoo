package com.veritransit.inspector.data

import com.veritransit.inspector.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Pushes a committed receipt to the back-office web dashboard (the
 * `:dashboard` module). That call is what flips the delivery's state
 * downstream: the dashboard marks an OK receipt "Accepted · OK TO PAY", a
 * flagged receipt "Held · DO NOT PAY", and publishes the record's
 * deterministic PDF report either way.
 *
 * Fire-and-forget by design: the record of truth is the one in [Repo]'s
 * receipt log, committed before this runs. A dashboard that cannot be reached
 * is reported back for the toast and otherwise changes nothing — the dock
 * record never depends on the office being online.
 *
 * The URL comes from `local.properties` (`dashboard.url`, default
 * `http://127.0.0.1:8080`); with `adb reverse tcp:8080 tcp:8080` that points
 * at a dashboard running on the host machine over USB.
 */
object DashboardSync {

    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 5_000

    private val json = Json { explicitNulls = false }

    @Serializable
    private data class ItemDto(
        val sku: String,
        val name: String,
        val detail: String,
        val expected: Int,
        val received: Int,
        val damaged: Int,
    )

    /**
     * The wire contract `:dashboard` parses (`POST /api/records`). Field names
     * use snake_case there, hence the explicit serial names.
     */
    @Serializable
    private data class RecordDto(
        val id: String,
        @kotlinx.serialization.SerialName("purchase_order") val purchaseOrderId: String,
        @kotlinx.serialization.SerialName("packing_list") val packingListId: String,
        val supplier: String,
        val goods: String,
        val dock: String,
        val carrier: String,
        val outcome: String,
        val timestamp: Long,
        val items: List<ItemDto>,
        val confidence: Float,
        val note: String,
        val receiver: String,
        val warehouse: String,
        // Proof frames (the exact JPEGs shown on the receipt screen), base64 —
        // the dashboard embeds them into the PDF report as-is.
        @kotlinx.serialization.SerialName("list_evidence") val listEvidence: String? = null,
        @kotlinx.serialization.SerialName("dock_evidence") val dockEvidence: String? = null,
    )

    /** Frames are 512px JPEGs (~100 KB); anything wildly larger is skipped. */
    private const val MAX_FRAME_BYTES = 512 * 1024

    private fun frame(path: String?): String? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists() || file.length() !in 1..MAX_FRAME_BYTES.toLong()) return null
        return Base64.getEncoder().encodeToString(file.readBytes())
    }

    suspend fun push(
        record: ReceivingRecord,
        listEvidencePath: String? = null,
        dockEvidencePath: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = RecordDto(
                id = record.id,
                purchaseOrderId = record.purchaseOrderId,
                packingListId = record.packingListId,
                supplier = record.supplier,
                goods = record.goods,
                dock = record.dock,
                carrier = record.carrier,
                outcome = record.outcome.name,
                timestamp = record.timestamp,
                items = record.items.map {
                    ItemDto(it.sku, it.name, it.detail, it.expected, it.received, it.damaged)
                },
                confidence = record.confidence,
                note = record.note,
                receiver = Repo.RECEIVER,
                warehouse = Repo.WAREHOUSE,
                listEvidence = frame(listEvidencePath),
                dockEvidence = frame(dockEvidencePath),
            )
            val conn = URL(BuildConfig.DASHBOARD_URL.trimEnd('/') + "/api/records")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.encodeToString(RecordDto.serializer(), dto).toByteArray()) }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("dashboard HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }.mapCatching { }
    }
}
