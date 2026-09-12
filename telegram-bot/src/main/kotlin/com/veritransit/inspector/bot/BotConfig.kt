package com.veritransit.inspector.bot

import java.io.File
import java.util.Properties

/**
 * Resolves the bot token without ever storing it in source control:
 * 1. system property `telegram.bot.token`
 * 2. env `VERITRANSIT_BOT_TOKEN` (or `TELEGRAM_BOT_TOKEN`)
 * 3. gitignored `telegram-bot/secrets.properties` → `telegram.bot.token=…`
 */
object BotConfig {

    const val SECRETS_FILE = "secrets.properties"

    fun resolveToken(): String? = sequenceOf(
        System.getProperty("telegram.bot.token"),
        System.getenv("VERITRANSIT_BOT_TOKEN"),
        System.getenv("TELEGRAM_BOT_TOKEN"),
        secretsFileToken(File(SECRETS_FILE)),
        secretsFileToken(File("..").resolve(SECRETS_FILE)),
    ).firstOrNull { !it.isNullOrBlank() }

    /** Base URL of the VeriTransit backend (§6.4). Absent = standalone mode. */
    fun resolvePlatformUrl(): String? = sequenceOf(
        System.getProperty("veritransit.api.url"),
        System.getenv("VERITRANSIT_API_URL"),
        secretsFileValue(File(SECRETS_FILE), "veritransit.api.url"),
        secretsFileValue(File("..").resolve(SECRETS_FILE), "veritransit.api.url"),
    ).firstOrNull { !it.isNullOrBlank() }?.trimEnd('/')

    /** Optional session token, for commands the backend requires auth for. */
    fun resolvePlatformToken(): String? = sequenceOf(
        System.getProperty("veritransit.api.token"),
        System.getenv("VERITRANSIT_API_TOKEN"),
        secretsFileValue(File(SECRETS_FILE), "veritransit.api.token"),
    ).firstOrNull { !it.isNullOrBlank() }

    /**
     * Supervisor chat for tamper voice alerts: the bot forwards each dock
     * voice note there as a Telegram voice message. A supervisor gets it by
     * messaging the bot once; the chat id comes from any incoming message id
     * only when this is unset — explicit config wins.
     */
    fun resolveSupervisorChat(): Long? = sequenceOf(
        System.getProperty("telegram.supervisor.chat"),
        System.getenv("VERITRANSIT_SUPERVISOR_CHAT"),
        secretsFileValue(File(SECRETS_FILE), "telegram.supervisor.chat"),
    ).firstOrNull { !it.isNullOrBlank() }?.toLongOrNull()

    private fun secretsFileValue(file: File, key: String): String? = runCatching {
        if (!file.isFile) return null
        Properties().apply { file.inputStream().use { load(it) } }
            .getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun secretsFileToken(file: File): String? = runCatching {
        if (!file.isFile) return null
        Properties().apply { file.inputStream().use { load(it) } }
            .getProperty("telegram.bot.token")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
