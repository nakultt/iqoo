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

    private fun secretsFileToken(file: File): String? = runCatching {
        if (!file.isFile) return null
        Properties().apply { file.inputStream().use { load(it) } }
            .getProperty("telegram.bot.token")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
