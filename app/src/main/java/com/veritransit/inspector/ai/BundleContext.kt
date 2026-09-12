package com.veritransit.inspector.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure helpers for sizing prompts against the model bundle's context window.
 *
 * The window is fixed when Qualcomm compiles the chipset bundle
 * (`genie_config.json` → `dialog.context.size`; 2048 on the bundles we pull
 * today, while the export recipe allows up to 4096) and the runtime cannot
 * widen it — QAIRT rejects a non-zero `nCtx` outright. So the most context
 * the app can ever use is whatever the downloaded bundle declares, and every
 * budget here derives from that number rather than a hardcoded constant.
 *
 * Kept free of Android/Compose dependencies so the parsing and the budget
 * maths are pinned by JVM unit tests.
 */
object BundleContext {

    /** Assumed until the downloaded bundle's own config has been read. */
    const val DEFAULT_CONTEXT_TOKENS = 2048

    /** Values outside this range are a corrupt config, never a real window. */
    const val MIN_CONTEXT_TOKENS = 512
    const val MAX_CONTEXT_TOKENS = 32768

    /** Tokens held back from the prompt so a reply (and template overhead) fits. */
    const val PROMPT_MARGIN = 128

    /** Lowest prompt budget worth keeping history for. */
    const val MIN_PROMPT_BUDGET = 256

    const val GENIE_CONFIG_NAME = "genie_config.json"

    /**
     * Reads `dialog.context.size` out of a bundle `genie_config.json`.
     * Anything unreadable — missing file text, wrong shape, non-integer, or a
     * size outside [MIN_CONTEXT_TOKENS]..[MAX_CONTEXT_TOKENS] — yields
     * [fallback], because a missing signal must never become a budget the
     * runtime cannot honour.
     */
    fun parseContextSize(genieConfigJson: String, fallback: Int = DEFAULT_CONTEXT_TOKENS): Int {
        val size = runCatching {
            Json.parseToJsonElement(genieConfigJson)
                .jsonObject["dialog"]
                ?.jsonObject?.get("context")
                ?.jsonObject?.get("size")
                ?.jsonPrimitive?.intOrNull
        }.getOrNull()
        return if (size != null && size in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS) size else fallback
    }

    /** Prompt tokens usable when [replyTokens] must still fit the window. */
    fun promptBudget(contextTokens: Int, replyTokens: Int, margin: Int = PROMPT_MARGIN): Int =
        (contextTokens - replyTokens - margin).coerceAtLeast(MIN_PROMPT_BUDGET)
}
