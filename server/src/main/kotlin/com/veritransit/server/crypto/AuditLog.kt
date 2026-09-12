package com.veritransit.server.crypto

import com.veritransit.core.AuditChainStatus
import com.veritransit.core.AuditEntry
import com.veritransit.server.db.*
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.time.Instant

/**
 * The append-only chain of §3 check 9.
 *
 * Sealing happens inside the database, not here: a `BEFORE INSERT` trigger
 * computes each hash from the previous row under an advisory lock. That
 * placement is deliberate — if the chaining lived in this class, anything that
 * wrote to the table by another path (a migration, psql, a second service)
 * would silently break the chain. The application role is not even granted
 * UPDATE or DELETE on the table.
 */
class AuditLog(private val db: Database) {

    /** Appends one entry and returns its sequence number. */
    fun append(actor: String, action: String, subject: String, payload: JsonObject, at: Instant? = null): Long =
        db.connection { append(it, actor, action, subject, payload, at) }

    /** Same, inside a caller's transaction — so an event and its audit row commit together. */
    fun append(
        conn: Connection, actor: String, action: String, subject: String,
        payload: JsonObject, at: Instant? = null,
    ): Long = conn.queryOne(
        "SELECT audit_append(?, ?, ?, ?, COALESCE(?, now())) AS seq",
        actor, action, subject, Jsonb(payload.toString()), at,
    ) { it.getLong("seq") } ?: error("audit_append returned no sequence")

    fun recent(limit: Int = 100, subject: String? = null): List<AuditEntry> {
        val sql = buildString {
            append("SELECT seq, at, actor, action, subject, prev_hash, hash FROM audit_log")
            if (subject != null) append(" WHERE subject = ?")
            append(" ORDER BY seq DESC LIMIT ?")
        }
        val params = listOfNotNull(subject, limit).toTypedArray()
        return db.query(sql, *params) {
            AuditEntry(
                seq = it.getLong("seq"), at = it.iso("at"), actor = it.str("actor"),
                action = it.str("action"), subject = it.str("subject"),
                prevHash = it.strOrNull("prev_hash"), hash = it.str("hash"),
            )
        }
    }

    fun range(from: Long?, to: Long?, limit: Int = 1000): List<AuditEntry> = db.query(
        """SELECT seq, at, actor, action, subject, prev_hash, hash FROM audit_log
           WHERE (?::bigint IS NULL OR seq >= ?::bigint)
             AND (?::bigint IS NULL OR seq <= ?::bigint)
           ORDER BY seq LIMIT ?""",
        from, from, to, to, limit,
    ) {
        AuditEntry(
            seq = it.getLong("seq"), at = it.iso("at"), actor = it.str("actor"),
            action = it.str("action"), subject = it.str("subject"),
            prevHash = it.strOrNull("prev_hash"), hash = it.str("hash"),
        )
    }

    /** What the admin UI's tamper indicator calls (§6.3 Audit page). */
    fun verifyChain(from: Long? = null, to: Long? = null): AuditChainStatus = db.queryOne(
        "SELECT ok, checked, first_bad_seq, detail FROM audit_verify_chain(?, ?)", from, to,
    ) {
        AuditChainStatus(
            ok = it.bool("ok"), checked = it.getLong("checked"),
            firstBadSeq = it.getObject("first_bad_seq")?.let { _ -> it.getLong("first_bad_seq") },
            detail = it.str("detail"),
        )
    } ?: AuditChainStatus(false, 0, null, "chain verification returned no rows")
}
