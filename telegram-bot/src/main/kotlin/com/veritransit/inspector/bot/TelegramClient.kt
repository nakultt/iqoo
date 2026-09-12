package com.veritransit.inspector.bot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class TelegramApiException(message: String) : Exception(message)

data class BotIdentity(val id: Long, val username: String, val name: String)

data class TgMessage(
    val chatId: Long,
    val fromName: String,
    /**
     * The sender's @handle, when they have one. This is what the backend maps to
     * a user and a finance role, so an in-chat approval can be attributed and
     * maker-checker enforced (§5.5). Null for users with no username set —
     * those senders can read, but cannot approve.
     */
    val fromUsername: String? = null,
    val text: String?,
    val caption: String?,
    val hasPhoto: Boolean,
    val documentFileId: String?,
    val documentName: String?,
    val documentMime: String?,
) {
    val isTextualDocument: Boolean
        get() = documentMime?.startsWith("text/") == true ||
            documentName?.endsWith(".txt", ignoreCase = true) == true ||
            documentName?.endsWith(".csv", ignoreCase = true) == true ||
            documentName?.endsWith(".json", ignoreCase = true) == true ||
            documentName?.endsWith(".log", ignoreCase = true) == true
}

data class TgUpdate(val updateId: Long, val message: TgMessage?)

/**
 * Minimal Telegram Bot API client: long-polling [getUpdates], [sendMessage] and
 * [getFile]-backed document download. Uses only the JDK HTTP stack plus
 * kotlinx-serialization for JSON parsing.
 */
class TelegramClient(private val token: String) {

    private val json = Json { ignoreUnknownKeys = true }
    private var offset: Long = 0L

    fun getMe(): BotIdentity {
        val result = call("getMe", emptyList()).jsonObject
        return BotIdentity(
            id = result["id"]!!.jsonPrimitive.long,
            username = result["username"]!!.jsonPrimitive.content,
            name = result["first_name"]?.jsonPrimitive?.content ?: "VeriTransit Bot",
        )
    }

    /** Server-side long poll; blocks up to ~25s, returns newly arrived messages. */
    fun pollUpdates(): List<TgUpdate> {
        val result = call(
            "getUpdates",
            listOf(
                "timeout" to "25",
                "offset" to offset.toString(),
                "allowed_updates" to """["message"]""",
            ),
        ).jsonArray
        val updates = result.mapNotNull { element ->
            val obj = element.jsonObject
            val updateId = obj["update_id"]?.jsonPrimitive?.long ?: return@mapNotNull null
            TgUpdate(updateId, parseMessage(obj["message"]?.jsonObject))
        }
        updates.maxOfOrNull { it.updateId }?.let { offset = it + 1 }
        return updates
    }

    fun sendMessage(chatId: Long, text: String) {
        call(
            "sendMessage",
            listOf(
                "chat_id" to chatId.toString(),
                "text" to text.take(MAX_MESSAGE_LENGTH),
                "parse_mode" to "HTML",
                "disable_web_page_preview" to "true",
            ),
        )
    }

    /** Downloads a document's content; intended for text-based receipts. */
    fun downloadDocumentText(fileId: String, maxBytes: Int = 128 * 1024): String {
        val info = call("getFile", listOf("file_id" to fileId)).jsonObject
        val path = info["file_path"]?.jsonPrimitive?.content
            ?: throw TelegramApiException("getFile returned no file_path")
        val url = URI("https://api.telegram.org/file/bot$token/$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = DOWNLOAD_TIMEOUT_MS
        try {
            val bytes = conn.inputStream.use { input -> input.readNBytes(maxBytes) }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseMessage(obj: JsonObject?): TgMessage? {
        obj ?: return null
        val chatId = obj["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.long ?: return null
        val fromName = obj["from"]?.jsonObject?.get("first_name")?.jsonPrimitive?.content ?: "there"
        return TgMessage(
            chatId = chatId,
            fromName = fromName,
            fromUsername = obj["from"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull,
            text = obj["text"]?.jsonPrimitive?.contentOrNull,
            caption = obj["caption"]?.jsonPrimitive?.contentOrNull,
            hasPhoto = obj["photo"]?.jsonArray?.isNotEmpty() == true,
            documentFileId = obj["document"]?.jsonObject?.get("file_id")?.jsonPrimitive?.contentOrNull,
            documentName = obj["document"]?.jsonObject?.get("file_name")?.jsonPrimitive?.contentOrNull,
            documentMime = obj["document"]?.jsonObject?.get("mime_type")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun call(method: String, params: List<Pair<String, String>>): JsonElement {
        val form = params.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
        val conn = (URI("https://api.telegram.org/bot$token/$method").toURL().openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = POLL_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val parsed = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw TelegramApiException("HTTP ${conn.responseCode} from $method") }
            if (parsed["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                throw TelegramApiException(
                    parsed["description"]?.jsonPrimitive?.contentOrNull ?: "HTTP ${conn.responseCode} from $method",
                )
            }
            return parsed["result"] ?: JsonNull
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val POLL_TIMEOUT_MS = 40_000
        const val DOWNLOAD_TIMEOUT_MS = 30_000
        const val MAX_MESSAGE_LENGTH = 4_000
    }
}
