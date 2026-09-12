package com.veritransit.server.services

import com.veritransit.server.db.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * **Signing secrets never come from the database.** `webhooks.secret_hash`
 * stores a digest for audit; a digest cannot be un-hashed, so signing with it
 * (as an earlier revision did) would have produced HMACs no receiver could
 * verify. The real secrets are supplied out of band via `VT_WEBHOOK_SECRETS`
 * (`name=secret` pairs, or a vault-backed env in a real deployment) and looked
 * up per webhook by name. A webhook without a configured secret is never sent
 * unsigned — its deliveries fail visibly with instructions instead.
 */
class WebhookService(
    private val db: Database,
    private val http: HttpClient,
    private val secrets: Map<String, String> = emptyMap(),
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One flush at a time — the timer and a manual flush must not interleave. */
    private val flushing = Mutex()

    /** Queues a delivery for every active webhook subscribed to [event]. */
    fun enqueue(conn: Connection, event: String, shipmentRef: String, payload: Map<String, String>) {
        val body = Rows.json.encodeToString(payload + mapOf("event" to event, "shipment_ref" to shipmentRef))
        conn.update(
            """INSERT INTO webhook_deliveries (webhook_id, event, payload)
               SELECT id, ?, ? FROM webhooks WHERE active AND ? = ANY (events)""",
            event, Jsonb(body), event,
        )
    }

    /**
     * Sends everything pending and due. Called after commit and on a timer.
     *
     * Each delivery is **claimed atomically** — `attempts` is bumped under a
     * compare-and-set before the HTTP call — so two flushes (or two server
     * processes) that select the same pending row can never both send it: only
     * the one whose CAS wins pays the attempt. Backoff is computed from
     * `attempts` against the queue time, so the flush never sleeps and never
     * holds anything up behind a retrying row.
     */
    fun flush() {
        scope.launch {
            flushing.withLock {
                val pending = db.query(
                    """SELECT d.id::text AS id, d.event, d.payload::text AS payload, d.attempts,
                              w.url, w.name
                         FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id
                        WHERE d.delivered_at IS NULL AND d.attempts < 6
                          AND (d.attempts = 0 OR d.created_at < now()
                               - (LEAST(power(2::numeric, d.attempts), 60) * interval '1 second'))
                        ORDER BY d.created_at LIMIT 25""",
                ) {
                    mapOf(
                        "id" to it.str("id"), "event" to it.str("event"), "payload" to it.str("payload"),
                        "attempts" to it.int("attempts").toString(), "url" to it.str("url"),
                        "name" to it.str("name"),
                    )
                }

                for (d in pending) {
                    runCatching { deliver(d) }.onFailure { t ->
                        log.warn("webhook {} delivery crashed: {}", d["name"], t.message)
                    }
                }
            }
        }
    }

    private suspend fun deliver(d: Map<String, String>) {
        // A missing secret is a configuration error, not a reason to send a
        // payload the receiver cannot authenticate — or worse, send it plain.
        val secret = secrets[d["name"]!!]
        if (secret == null) {
            db.update(
                """UPDATE webhook_deliveries SET attempts = attempts + 1, last_error = ?
                    WHERE id = ?::uuid""",
                "no signing secret configured for webhook '${d["name"]}' — set VT_WEBHOOK_SECRETS " +
                    "(${d["name"]}=<secret>) and flush again",
                d["id"],
            )
            log.warn("webhook {} has no signing secret configured — delivery withheld", d["name"])
            return
        }

        // Claim the delivery: the CAS on `attempts` is the lease. A concurrent
        // flush reading the same attempts value loses here and moves on.
        val claimed = db.update(
            """UPDATE webhook_deliveries SET attempts = attempts + 1
                WHERE id = ?::uuid AND attempts = ?""",
            d["id"], d["attempts"]!!.toInt(),
        )
        if (claimed == 0) return

        val body = d["payload"]!!
        val signature = hmacSha256(secret, body)
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
                      SET status_code = ?,
                          delivered_at = CASE WHEN ? THEN now() ELSE NULL END,
                          last_error = CASE WHEN ? THEN NULL ELSE ? END
                    WHERE id = ?::uuid""",
                response.status.value, ok, ok,
                "HTTP ${response.status.value} from ${d["name"]}", d["id"],
            )
            if (!ok) log.warn("webhook {} returned {}", d["name"], response.status)
        }.onFailure { t ->
            db.update(
                """UPDATE webhook_deliveries SET last_error = ? WHERE id = ?::uuid""",
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
