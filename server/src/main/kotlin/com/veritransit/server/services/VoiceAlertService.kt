package com.veritransit.server.services

import com.veritransit.core.ScanResult
import com.veritransit.core.VoiceAlertItem
import com.veritransit.core.VoiceAlertUpload
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.db.Database
import com.veritransit.server.db.isoOrNull
import com.veritransit.server.db.queryOne
import com.veritransit.server.db.str
import com.veritransit.server.db.strOrNull
import com.veritransit.server.db.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Tamper voice alerts: the phone uploads the WAV it just spoke on the dock,
 * the Telegram bot forwards it as a voice message to the supervisor chat.
 *
 * Delivery is at-least-once per alert: [pending] lists undelivered rows and
 * [markDelivered] acks them, so a bot restart re-sends nothing twice and loses
 * nothing. Audio is capped — a phone synthesising minutes of speech is either
 * broken or hostile, and neither should fill the database.
 */
class VoiceAlertService(
    private val db: Database,
    private val audit: AuditLog,
) {
    companion object {
        const val MAX_AUDIO_BYTES = 2 * 1024 * 1024
    }

    fun submit(upload: VoiceAlertUpload, actor: String): String {
        val audio = runCatching { Base64.getDecoder().decode(upload.audioB64) }
            .getOrNull() ?: error("audio_b64 is not valid base64")
        require(audio.isNotEmpty()) { "empty voice note" }
        require(audio.size <= MAX_AUDIO_BYTES) { "voice note too large (${audio.size} bytes)" }

        return db.transaction { conn ->
            val id = conn.queryOne(
                """INSERT INTO voice_alerts
                     (shipment_ref, package_code, verdict, caption, mime_type, audio, officer)
                   VALUES (?, ?, ?::text, ?, ?, ?, ?)
                   RETURNING id::text AS id""",
                upload.shipmentRef, upload.packageCode, upload.verdict.name,
                upload.caption.take(1024), upload.mimeType.take(64), audio,
                upload.officer ?: actor,
            ) { it.str("id") } ?: error("voice alert insert failed")

            audit.append(conn, actor, "VOICE_ALERT_QUEUED",
                upload.packageCode ?: upload.shipmentRef ?: id, buildJsonObject {
                    put("alert_id", id)
                    put("verdict", upload.verdict.name)
                    put("bytes", audio.size)
                })
            id
        }
    }

    fun pending(limit: Int = 20): List<VoiceAlertItem> = db.query(
        """SELECT id::text AS id, shipment_ref, package_code, verdict, caption,
                  mime_type, audio, officer, created_at
             FROM voice_alerts WHERE delivered_at IS NULL
             ORDER BY created_at LIMIT ?""",
        limit,
    ) {
        VoiceAlertItem(
            id = it.str("id"),
            shipmentRef = it.strOrNull("shipment_ref"),
            packageCode = it.strOrNull("package_code"),
            verdict = runCatching { ScanResult.valueOf(it.str("verdict")) }
                .getOrDefault(ScanResult.SUSPECT_REVIEW),
            caption = it.strOrNull("caption").orEmpty(),
            mimeType = it.strOrNull("mime_type") ?: "audio/wav",
            audioB64 = Base64.getEncoder().encodeToString(it.getBytes("audio") ?: ByteArray(0)),
            officer = it.strOrNull("officer"),
            createdAt = it.isoOrNull("created_at"),
        )
    }

    fun markDelivered(id: String, tgFileId: String?, actor: String) {
        db.update(
            "UPDATE voice_alerts SET delivered_at = now(), tg_file_id = ? WHERE id = ?::uuid",
            tgFileId, id,
        )
        audit.append(actor, "VOICE_ALERT_DELIVERED", id, buildJsonObject {
            put("tg_file_id", tgFileId ?: "")
        })
    }
}
