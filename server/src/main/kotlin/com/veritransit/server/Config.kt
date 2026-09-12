package com.veritransit.server

import java.io.File

/**
 * Server configuration.
 *
 * Reads `db/.env` when it is present — that file is what `db/scripts/setup.sh`
 * writes, so a developer who has built the database locally can start the
 * server with no further setup. Real environment variables always win, which is
 * how a deployed instance overrides everything without editing files.
 */
data class Config(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val signingKeyPem: File?,
    val signingKeyId: String,
    val publicKeyB64: String,
    /** §5.5 — the agent's hosted LLM. Absent means the agent still runs; it just
     *  reports with deterministic text instead of drafted prose. */
    val llmApiKey: String?,
    val llmModel: String,
    val adminWebDir: File?,
    val corsHosts: List<String>,
    /** §6.2 — HMAC secrets for the payer webhooks, by webhook name. The
     *  database only ever stores their digests, so the secrets arrive here,
     *  out of band: `VT_WEBHOOK_SECRETS="zen-erp=s3cret,metro-erp=s3cret2"`. */
    val webhookSecrets: Map<String, String>,
) {
    companion object {
        fun load(): Config {
            val env = dotenv(File("db/.env")) + dotenv(File("../db/.env"))
            fun v(key: String, default: String? = null): String? =
                System.getenv(key) ?: env[key] ?: default

            val host = v("VT_DB_HOST", "localhost")!!
            val port = v("VT_DB_PORT", "5432")!!
            val name = v("VT_DB_NAME", "veritransit")!!

            val keyPath = v("VT_SIGNING_KEY_PRIVATE")
            return Config(
                port = v("PORT", "8080")!!.toInt(),
                // The server runs on the same host as Postgres in the pilot, so
                // loopback avoids depending on the laptop's DHCP address.
                jdbcUrl = v("VT_JDBC_URL") ?: "jdbc:postgresql://$host:$port/$name",
                dbUser = v("VT_APP_USER", "veritransit_app")!!,
                dbPassword = v("VT_APP_PASSWORD", "")!!,
                signingKeyPem = keyPath?.let(::File)?.takeIf { it.isFile },
                signingKeyId = v("VT_SIGNING_KEY_ID", "vt-key-2026-01")!!,
                publicKeyB64 = v("VT_SIGNING_KEY_PUBLIC", "")!!,
                llmApiKey = v("ANTHROPIC_API_KEY"),
                llmModel = v("VT_AGENT_MODEL", "claude-sonnet-5")!!,
                adminWebDir = listOf(File("admin-web/dist"), File("../admin-web/dist"))
                    .firstOrNull { it.isDirectory },
                corsHosts = (v("VT_CORS_HOSTS") ?: "").split(",").map(String::trim).filter(String::isNotEmpty),
                webhookSecrets = (v("VT_WEBHOOK_SECRETS") ?: "").split(',')
                    .mapNotNull { entry ->
                        val parts = entry.split('=', limit = 2).map(String::trim)
                        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) null
                        else parts[0] to parts[1]
                    }
                    .toMap(),
            )
        }

        /** Minimal .env reader: `KEY="value"` or `KEY=value`, `#` comments. */
        private fun dotenv(file: File): Map<String, String> {
            if (!file.isFile) return emptyMap()
            return file.readLines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || '=' !in t) return@mapNotNull null
                val (k, raw) = t.split('=', limit = 2)
                k.trim() to raw.trim().removeSurrounding("\"").removeSurrounding("'")
            }.toMap()
        }
    }
}
