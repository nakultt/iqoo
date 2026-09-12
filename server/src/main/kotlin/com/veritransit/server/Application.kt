package com.veritransit.server

import com.veritransit.core.PublicKeyEntry
import com.veritransit.core.ApiError
import com.veritransit.server.auth.Sessions
import com.veritransit.server.auth.veriTransitAuth
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.crypto.Signer
import com.veritransit.server.crypto.Verifier
import com.veritransit.server.db.Database
import com.veritransit.server.db.str
import com.veritransit.server.routes.apiRoutes
import com.veritransit.server.services.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.fixedRateTimer

/** Everything the routes need, wired once. Constructor injection, no framework. */
class Services(
    val config: Config,
    val db: Database,
    val signer: Signer,
    val audit: AuditLog,
    val sessions: Sessions,
    val shipments: ShipmentService,
    val labels: LabelService,
    val scans: ScanService,
    val documents: DocumentService,
    val matcher: FourWayMatcher,
    val finance: FinanceService,
    val risk: RiskEngine,
    val pod: PodService,
    val agent: AgentService,
    val webhooks: WebhookService,
    val voiceAlerts: VoiceAlertService,
    val operatorSecret: String,
) {
    /** §4.2 — devices pin these and verify labels with no network. */
    fun publicKeys(): List<PublicKeyEntry> = db.query(
        "SELECT key_id, algorithm, public_key FROM signing_keys WHERE active",
    ) { PublicKeyEntry(it.str("key_id"), it.str("algorithm"), it.str("public_key")) }
}

private val serverLog = LoggerFactory.getLogger("VeriTransit")

fun buildServices(config: Config): Services {
    val db = Database(config)
    val audit = AuditLog(db)

    val signerKey = config.signingKeyPem
        ?: error(
            "No signing key. Expected VT_SIGNING_KEY_PRIVATE to point at the PEM from the key " +
                "ceremony (db/keys/signing_key.pem). Run ./db/scripts/setup.sh first."
        )
    val signer = Signer.fromPem(signerKey, config.signingKeyId)

    // The session secret is ephemeral unless supplied: restarting the server
    // invalidates outstanding tokens, which is the safer default for a pilot.
    val sessionSecret = System.getenv("VT_SESSION_SECRET") ?: randomSecret()
    val operatorSecret = System.getenv("VT_OPERATOR_SECRET") ?: "veritransit-pilot"

    val http = HttpClient(CIO)
    val webhooks = WebhookService(db, http, config.webhookSecrets)
    val shipments = ShipmentService(db, audit)
    val labels = LabelService(db, signer, audit)
    val documents = DocumentService(db, audit)
    val matcher = FourWayMatcher(db)

    // The same public keys the devices pin: ingest re-verifies every label
    // server-side, because the device's verdict is evidence, not authority.
    val verifier = Verifier.of(db.query(
        "SELECT key_id, public_key FROM signing_keys WHERE active",
    ) { it.str("key_id") to it.str("public_key") }.toMap())

    val scans = ScanService(db, audit, verifier)
    val finance = FinanceService(db, signer, audit, webhooks, matcher)
    val risk = RiskEngine(db)
    val pod = PodService(db, signer, audit, matcher, scans)

    // The pilot's notifier logs; the Telegram bot subscribes to the same text
    // through the REST surface (§6.4) rather than this process holding a token.
    val notifier = AgentService.Notifier { audience, subject, message ->
        serverLog.info("notify [{}] {}\n{}", audience, subject, message)
    }
    val agent = AgentService(db, audit, matcher, risk, finance, shipments, notifier)
    val voiceAlerts = VoiceAlertService(db, audit)

    return Services(
        config, db, signer, audit, Sessions(db, sessionSecret, operatorSecret),
        shipments, labels, scans, documents, matcher, finance, risk, pod, agent, webhooks,
        voiceAlerts,
        operatorSecret,
    )
}

fun main() {
    val config = Config.load()
    val services = buildServices(config)

    serverLog.info("VeriTransit server starting on port {} against {}", config.port, config.jdbcUrl)
    val chain = services.audit.verifyChain()
    // Refusing to start on a broken chain would be worse than reporting it: the
    // admin UI's tamper indicator is how a human finds out, and it needs the
    // server running to show them.
    if (!chain.ok) serverLog.error("AUDIT CHAIN BROKEN at seq {} — {}", chain.firstBadSeq, chain.detail)
    else serverLog.info("audit chain intact ({} entries)", chain.checked)

    // Webhook deliveries retry on their own so a payer outage heals without an
    // operator noticing (§5.2 "HMAC-signed, retried").
    fixedRateTimer("webhook-flush", daemon = true, initialDelay = 5_000, period = 30_000) {
        runCatching { services.webhooks.flush() }
    }

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(services)
    }.start(wait = true)
}

fun Application.module(services: Services) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
    }
    install(CallLogging) { level = Level.INFO }
    install(WebSockets)
    install(Authentication) {
        veriTransitAuth(services.sessions)
    }
    install(CORS) {
        allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization)
        allowHeader("X-API-Key"); allowHeader("X-Officer"); allowHeader("X-Confirmed-By")
        if (services.config.corsHosts.isEmpty()) anyHost()
        else services.config.corsHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
    }
    install(StatusPages) {
        exception<Sessions.AuthError> { call, e ->
            call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", e.message))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad request", e.message))
        }
        exception<IllegalStateException> { call, e ->
            call.respond(HttpStatusCode.Conflict, ApiError("conflict", e.message))
        }
        exception<Throwable> { call, e ->
            serverLog.error("unhandled", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal error", e.message))
        }
    }

    apiRoutes(services)

    // §6.3 — the admin UI is served by Ktor from the same origin as the API, so
    // the browser needs no CORS grant and no second hostname.
    services.config.adminWebDir?.let { dist ->
        routing {
            staticFiles("/", dist) { default("index.html") }
        }
        serverLog.info("serving admin web UI from {}", dist.absolutePath)
    }
}

private fun randomSecret(): String {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
