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
 * Pushes a committed inspection to the back-office web dashboard (the
 * `:dashboard` module). That call is what flips the consignment's state
 * downstream: the dashboard marks a PASSED verdict "Shipped · OK TO PAY", a
 * REVIEW verdict "Held · DO NOT PAY", and publishes the record's
 * deterministic PDF report either way.
 *
 * Fire-and-forget by design: the statutory copy of the record is the one in
 * [Repo]'s vault, committed before this runs. A dashboard that cannot be
 * reached is reported back for the toast and otherwise changes nothing — the
 * field record never depends on the office being online.
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
        val name: String,
        val detail: String,
        val expected: Int,
        val found: Int,
    )

    /**
     * The wire contract `:dashboard` parses (`POST /api/records`). Field names
     * use snake_case there, hence the explicit serial names.
     */
    @Serializable
    private data class RecordDto(
        val id: String,
        val ewb: String,
        val vehicle: String,
        @kotlinx.serialization.SerialName("vehicle_model") val vehicleModel: String,
        val cargo: String,
        val route: String,
        @kotlinx.serialization.SerialName("distance_km") val distanceKm: Int,
        val verdict: String,
        val timestamp: Long,
        val items: List<ItemDto>,
        val confidence: Float,
        val note: String,
        val inspector: String,
        val badge: String,
        val station: String,
        // Proof frames (the exact JPEGs shown on the result screen), base64 —
        // the dashboard embeds them into the PDF report as-is.
        @kotlinx.serialization.SerialName("bill_evidence") val billEvidence: String? = null,
        @kotlinx.serialization.SerialName("cargo_evidence") val cargoEvidence: String? = null,
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
        record: InspectionRecord,
        billEvidencePath: String? = null,
        cargoEvidencePath: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = RecordDto(
                id = record.id,
                ewb = record.ewb,
                vehicle = record.vehicle,
                vehicleModel = record.vehicleModel,
                cargo = record.cargo,
                route = record.route,
                distanceKm = record.distanceKm,
                verdict = record.verdict.name,
                timestamp = record.timestamp,
                items = record.items.map { ItemDto(it.name, it.detail, it.expected, it.found) },
                confidence = record.confidence,
                note = record.note,
                inspector = Repo.INSPECTOR,
                badge = Repo.BADGE,
                station = Repo.STATION,
                billEvidence = frame(billEvidencePath),
                cargoEvidence = frame(cargoEvidencePath),
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
