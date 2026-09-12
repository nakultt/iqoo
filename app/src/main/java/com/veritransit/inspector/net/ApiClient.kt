package com.veritransit.inspector.net

import com.veritransit.core.ActivateRequest
import com.veritransit.core.ActivateResponse
import com.veritransit.core.BootstrapResponse
import com.veritransit.core.CloseMasterRequest
import com.veritransit.core.CreateShipmentRequest
import com.veritransit.core.IssueLabelsRequest
import com.veritransit.core.IssueLabelsResponse
import com.veritransit.core.IssuedLabel
import com.veritransit.core.PodSubmission
import com.veritransit.core.ScanBatchRequest
import com.veritransit.core.ScanBatchResponse
import com.veritransit.core.ScanEvent
import com.veritransit.core.Shipment
import com.veritransit.core.ShipmentDocument
import com.veritransit.core.ShipmentReport
import com.veritransit.core.VoiceAlertUpload
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

    suspend fun report(ref: String): ShipmentReport =
        client.get("$baseUrl/v1/shipments/$ref/report").body()

    // ------------------------------------------------- mobile pack station
    // The sender flow on the phone (replaces the web pack-station page).
    // Every call degrades to the offline draft path on failure — the caller
    // decides, so these throw and the ViewModel falls back to local QRs.

    suspend fun shipments(): List<Shipment> =
        client.get("$baseUrl/v1/shipments").body()

    suspend fun createShipment(req: CreateShipmentRequest): Shipment =
        client.post("$baseUrl/v1/shipments") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    /** Bulk issue: N masters x M inners, signed server-side, rows before print. */
    suspend fun issueLabels(ref: String, req: IssueLabelsRequest): IssueLabelsResponse =
        client.post("$baseUrl/v1/shipments/$ref/labels:batch") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    /** Close a master over the inner codes actually scanned into the carton. */
    suspend fun closeMaster(req: CloseMasterRequest): IssuedLabel =
        client.post("$baseUrl/v1/packages:close-master") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    suspend fun attachDocument(ref: String, doc: ShipmentDocument): ShipmentDocument =
        client.post("$baseUrl/v1/shipments/$ref/documents") {
            contentType(ContentType.Application.Json)
            setBody(doc)
        }.body()

    suspend fun documents(ref: String): List<ShipmentDocument> =
        client.get("$baseUrl/v1/shipments/$ref/documents").body()

    /**
     * Queues the dock's voice note for the supervisor Telegram chat. Best
     * effort by contract: the WAV stays on the phone either way, so a failed
     * upload degrades to the share-sheet path, never to a lost alert.
     */
    suspend fun uploadVoiceAlert(req: VoiceAlertUpload): String =
        client.post("$baseUrl/v1/alerts/voice") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

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
