package com.veritransit.server.auth

import com.veritransit.core.UserRole
import com.veritransit.server.crypto.ApiKeys
import com.veritransit.server.db.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * §6.2 `auth`.
 *
 * Two kinds of caller, authenticated differently because they are different
 * risks:
 *
 *  * **Devices** present an API key issued at activation. Only its SHA-256 is
 *    stored, and the comparison is constant-time.
 *  * **People** present a session token this server signed (HMAC-SHA256 over
 *    subject, role and expiry).
 *
 * **Known gap:** the `users` table carries no credential column, so
 * [Sessions.login] authenticates against a single shared operator secret rather
 * than per-user passwords or SSO. That is enough for a pilot on a trusted
 * network and is deliberately not enough for production — it is called out in
 * `server/README.md` rather than hidden behind a JWT that looks stronger than
 * it is. Roles and maker-checker *are* enforced once identity is established.
 */
class Sessions(private val db: Database, private val secret: String, private val operatorSecret: String) {

    data class Principal(val subject: String, val role: UserRole, val isDevice: Boolean = false)

    class AuthError(message: String) : RuntimeException(message)

    fun login(name: String, password: String): String {
        if (!constantTimeEquals(password, operatorSecret)) throw AuthError("invalid credentials")
        val role = db.queryOne(
            "SELECT role::text AS role FROM users WHERE name = ? AND active", name,
        ) { it.str("role") } ?: throw AuthError("unknown user $name")
        return issue(name, UserRole.valueOf(role))
    }

    fun issue(subject: String, role: UserRole, ttlSeconds: Long = 12 * 3600): String {
        val expiry = Instant.now().epochSecond + ttlSeconds
        val body = "$subject|${role.name}|$expiry"
        return b64(body.toByteArray()) + "." + b64(hmac(body))
    }

    fun verify(token: String): Principal? {
        val parts = token.split('.')
        if (parts.size != 2) return null
        val body = runCatching { String(unb64(parts[0])) }.getOrNull() ?: return null
        val expected = b64(hmac(body))
        if (!constantTimeEquals(parts[1], expected)) return null

        val fields = body.split('|')
        if (fields.size != 3) return null
        val expiry = fields[2].toLongOrNull() ?: return null
        if (Instant.now().epochSecond > expiry) return null
        return runCatching { Principal(fields[0], UserRole.valueOf(fields[1])) }.getOrNull()
    }

    /** Resolves a device API key to its principal, and stamps last-seen. */
    fun device(apiKey: String): Principal? {
        // Every active device is compared in constant time rather than the hash
        // being looked up directly: a keyed lookup would turn a timing or
        // error-shape difference into an oracle for which keys exist.
        val match = db.query(
            """SELECT id::text AS id, api_key_hash FROM devices
                WHERE retired_at IS NULL AND activated_at IS NOT NULL""",
        ) { it.str("id") to it.str("api_key_hash") }
            .firstOrNull { ApiKeys.matches(apiKey, it.second) } ?: return null

        db.update("UPDATE devices SET last_seen_at = now() WHERE id = ?::uuid", match.first)
        return Principal(match.first, UserRole.OFFICER, isDevice = true)
    }

    private fun hmac(body: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        }.doFinal(body.toByteArray())

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String) = Base64.getUrlDecoder().decode(s.padEnd((s.length + 3) / 4 * 4, '='))

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(); val y = b.toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }
}

/** Pulls the caller's identity off the request, device key or session token. */
fun ApplicationCall.principal(sessions: Sessions): Sessions.Principal? {
    request.headers["X-API-Key"]?.let { return sessions.device(it) }
    val auth = request.headers["Authorization"] ?: return null
    if (!auth.startsWith("Bearer ", ignoreCase = true)) return null
    return sessions.verify(auth.substring(7).trim())
}

/** The acting human, for audit and maker-checker. Devices carry their officer explicitly. */
fun ApplicationCall.actor(sessions: Sessions): String =
    principal(sessions)?.let { if (it.isDevice) request.headers["X-Officer"] ?: it.subject else it.subject }
        ?: request.headers["X-Officer"] ?: "anonymous"
