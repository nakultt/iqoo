package com.veritransit.server.services

import com.veritransit.server.db.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * §6.2 `integrations` — the payer's endpoint registry.
 *
 * Deliveries are queued inside the caller's transaction and sent afterwards, so
 * a certificate is never announced to an ERP for a release that then rolls
 * back. Failures retry with backoff and stay visible in `webhook_deliveries`
 * rather than disappearing into a log.
 */
class WebhookService(private val db: Database, private val http: HttpClient) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Queues a delivery for every active webhook subscribed to [event]. */
    fun enqueue(conn: Connection, event: String, shipmentRef: String, payload: Map<String, String>) {
        val body = Rows.json.encodeToString(payload + mapOf("event" to event, "shipment_ref" to shipmentRef))
        conn.update(
            """INSERT INTO webhook_deliveries (webhook_id, event, payload)
               SELECT id, ?, ? FROM webhooks WHERE active AND ? = ANY (events)""",
            event, Jsonb(body), event,
        )
    }

    /** Sends everything still pending. Called after commit and on a timer. */
    fun flush() {
        scope.launch {
            val pending = db.query(
                """SELECT d.id::text AS id, d.event, d.payload::text AS payload, d.attempts,
                          w.url, w.secret_hash, w.name
                     FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id
                    WHERE d.delivered_at IS NULL AND d.attempts < 6
                    ORDER BY d.created_at LIMIT 25""",
            ) {
                mapOf(
                    "id" to it.str("id"), "event" to it.str("event"), "payload" to it.str("payload"),
                    "attempts" to it.int("attempts").toString(), "url" to it.str("url"),
                    "secret" to it.str("secret_hash"), "name" to it.str("name"),
                )
            }

            for (d in pending) {
                val attempts = d["attempts"]!!.toInt()
                // Exponential backoff, so a payer that is down for an hour is not
                // hammered — and so a retry storm cannot look like an attack.
                if (attempts > 0) delay(minOf(1L shl attempts, 60L) * 1000)
                deliver(d)
            }
        }
    }

    private suspend fun deliver(d: Map<String, String>) {
        val body = d["payload"]!!
        val signature = hmacSha256(d["secret"]!!, body)
        val result = runCatching {
            http.post(d["url"]!!) {
                contentType(ContentType.Application.Json)
                // HMAC over the exact bytes sent: the receiver can prove the
                // payload came from this platform and was not edited in transit.
                header("X-VeriTransit-Signature", "sha256=$signature")
                header("X-VeriTransit-Event", d["event"]!!)
                setBody(body)
            }
        }

        result.onSuccess { response ->
            val ok = response.status.isSuccess()
            db.update(
                """UPDATE webhook_deliveries
                      SET attempts = attempts + 1, status_code = ?,
                          delivered_at = CASE WHEN ? THEN now() ELSE NULL END,
                          last_error = CASE WHEN ? THEN NULL ELSE ? END
                    WHERE id = ?::uuid""",
                response.status.value, ok, ok,
                "HTTP ${response.status.value} from ${d["name"]}", d["id"],
            )
            if (!ok) log.warn("webhook {} returned {}", d["name"], response.status)
        }.onFailure { t ->
            db.update(
                """UPDATE webhook_deliveries SET attempts = attempts + 1, last_error = ?
                    WHERE id = ?::uuid""",
                t.message ?: t.javaClass.simpleName, d["id"],
            )
            log.warn("webhook {} failed: {}", d["name"], t.message)
        }
    }

    fun pending(): List<Map<String, String>> = db.query(
        """SELECT d.id::text AS id, w.name, d.event, d.attempts, d.status_code,
                  d.last_error, d.created_at, d.delivered_at
             FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id
            ORDER BY d.created_at DESC LIMIT 50""",
    ) {
        mapOf(
            "id" to it.str("id"), "webhook" to it.str("name"), "event" to it.str("event"),
            "attempts" to it.int("attempts").toString(),
            "status" to (it.intOrNull("status_code")?.toString() ?: ""),
            "error" to (it.strOrNull("last_error") ?: ""),
            "created_at" to it.iso("created_at"),
            "delivered_at" to (it.isoOrNull("delivered_at") ?: ""),
        )
    }

    private fun hmacSha256(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
