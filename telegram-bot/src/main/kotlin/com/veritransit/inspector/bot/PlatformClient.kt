package com.veritransit.inspector.bot

import com.veritransit.core.AgentAction
import com.veritransit.core.Discrepancy
import com.veritransit.core.FinanceState
import com.veritransit.core.RiskScore
import com.veritransit.core.Shipment
import com.veritransit.core.ShipmentReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * §6.4 — the backend REST client that replaces `BotData`'s in-memory vault.
 *
 * The bot deliberately holds no state and no authority of its own. Every
 * approval it relays is re-checked server-side against maker-checker and policy
 * (§5.5), so a compromised chat is still bounded by the same rules as the web
 * UI. The bot's job is to carry a question in and an answer out.
 */
class PlatformClient(private val baseUrl: String, private val token: String?) {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
        }
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000 }
    }

    private fun auth(builder: io.ktor.client.request.HttpRequestBuilder, actor: String?) {
        token?.let { builder.header("Authorization", "Bearer $it") }
        actor?.let { builder.header("X-Officer", it) }
    }

    suspend fun shipments(): List<Shipment> =
        client.get("$baseUrl/v1/shipments").body()

    suspend fun report(ref: String): ShipmentReport? = runCatching {
        client.get("$baseUrl/v1/shipments/$ref/report").body<ShipmentReport>()
    }.getOrNull()

    suspend fun finance(ref: String): FinanceState? = runCatching {
        client.get("$baseUrl/v1/shipments/$ref/finance").body<FinanceState>()
    }.getOrNull()

    suspend fun risk(ref: String): RiskScore? = runCatching {
        client.get("$baseUrl/v1/risk/shipments/$ref").body<RiskScore>()
    }.getOrNull()

    suspend fun heldQueue(): List<FinanceState> =
        client.get("$baseUrl/v1/finance/queue?status=HELD").body()

    suspend fun pendingQueue(): List<FinanceState> =
        client.get("$baseUrl/v1/finance/queue?status=RELEASE_PENDING").body()

    suspend fun discrepancies(): List<Discrepancy> =
        client.get("$baseUrl/v1/discrepancies").body()

    /** The agent's own plain-language answer to "why was it held?". */
    suspend fun explain(ref: String): String =
        client.get("$baseUrl/v1/agent/explain/$ref").bodyAsText()

    suspend fun agentActions(ref: String?): List<AgentAction> =
        client.get("$baseUrl/v1/agent/actions${ref?.let { "?shipment=$it" } ?: ""}").body()

    // ------------------------------------------------- tamper voice alerts
    // The dock speaks first (Kokoro voice on the phone); the bot carries the
    // exact audio to the supervisor chat as a voice message.

    suspend fun pendingVoiceAlerts(limit: Int = 10): List<com.veritransit.core.VoiceAlertItem> =
        runCatching {
            client.get("$baseUrl/v1/alerts/voice/pending?limit=$limit")
                .body<List<com.veritransit.core.VoiceAlertItem>>()
        }.getOrDefault(emptyList())

    suspend fun ackVoiceAlert(id: String, tgFileId: String, actor: String = "telegram-bot"): Boolean =
        runCatching {
            client.post("$baseUrl/v1/alerts/voice/$id/delivered?tg_file_id=$tgFileId") {
                auth(this, actor)
            }.status.value == 200
        }.getOrDefault(false)

    /**
     * Relays an approval. The server enforces that the approver is not the
     * maker — the bot does not and must not decide that itself.
     */
    suspend fun approveRelease(ref: String, actor: String): String = runCatching {
        val res = client.post("$baseUrl/v1/finance/$ref:approve") { auth(this, actor) }
        res.bodyAsText()
    }.getOrElse { "Could not reach the platform: ${it.message}" }

    suspend fun requestRelease(ref: String, actor: String): String = runCatching {
        client.post("$baseUrl/v1/finance/$ref:release") { auth(this, actor) }.bodyAsText()
    }.getOrElse { "Could not reach the platform: ${it.message}" }

    suspend fun health(): Boolean = runCatching {
        client.get("$baseUrl/health").status.value == 200
    }.getOrDefault(false)

    fun close() = client.close()
}
