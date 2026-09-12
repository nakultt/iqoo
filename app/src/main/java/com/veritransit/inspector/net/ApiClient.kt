package com.veritransit.inspector.net

import com.veritransit.core.ActivateRequest
import com.veritransit.core.ActivateResponse
import com.veritransit.core.BootstrapResponse
import com.veritransit.core.PodSubmission
import com.veritransit.core.QuickShipRequest
import com.veritransit.core.QuickShipResponse
import com.veritransit.core.ScanBatchRequest
import com.veritransit.core.ScanBatchResponse
import com.veritransit.core.ScanEvent
import com.veritransit.core.ShipmentReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The device's REST client (§8.3).
 *
 * Timeouts are short and deliberate: a phone on dock Wi-Fi that cannot reach the
 * server must fall back to the offline path quickly rather than blocking a
 * scanning session behind a socket that will never answer.
 */
class ApiClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val officer: String?,
) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 20_000
        }
        defaultRequest {
            apiKey?.let { header("X-API-Key", it) }
            officer?.let { header("X-Officer", it) }
        }
    }

    /**
     * §8.3 — one-time enrolment. The returned key is shown to the device once
     * and stored only as a digest on the server, so this call is the only moment
     * the key exists anywhere but this phone.
     */
    suspend fun activate(activationCode: String, label: String): ActivateResponse =
        client.post("$baseUrl/v1/devices/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateRequest(activationCode, label))
        }.body()

    suspend fun bootstrap(site: String?): BootstrapResponse =
        client.get("$baseUrl/v1/sync/bootstrap") {
            site?.let { parameter("site", it) }
        }.body()

    /** Idempotent by `client_event_id`, so this is always safe to retry. */
    suspend fun pushScans(events: List<ScanEvent>): ScanBatchResponse =
        client.post("$baseUrl/v1/scans:batch") {
            contentType(ContentType.Application.Json)
            setBody(ScanBatchRequest(events))
        }.body()

    /** The one-tap send — shipment, signed labels, paperwork and finance in one call. */
    suspend fun quickShip(req: QuickShipRequest): QuickShipResponse =
        client.post("$baseUrl/v1/quick-ship") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    suspend fun report(ref: String): ShipmentReport =
        client.get("$baseUrl/v1/shipments/$ref/report").body()

    suspend fun submitPod(submission: PodSubmission): String =
        client.post("$baseUrl/v1/shipments/${submission.shipmentRef}/pod") {
            contentType(ContentType.Application.Json)
            setBody(submission)
        }.body()

    suspend fun health(): Boolean = runCatching {
        client.get("$baseUrl/health").status.value == 200
    }.getOrDefault(false)

    fun close() = client.close()
}
