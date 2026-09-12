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
    fun parseContextSize(genieConfigJson: String, fallback: Int = DEFAULT_CONTEXT_TOKENS): Int =
        parseContextSizeOrNull(genieConfigJson) ?: fallback

    /**
     * The declared window, or null when the config is missing, malformed, or
     * out of range. Null is distinguishable from [DEFAULT_CONTEXT_TOKENS] —
     * callers that report *where* a number came from need that difference
     * (a fallback is an assumption, not a bundle declaration).
     */
    fun parseContextSizeOrNull(genieConfigJson: String): Int? {
        val size = runCatching {
            Json.parseToJsonElement(genieConfigJson)
                .jsonObject["dialog"]
                ?.jsonObject?.get("context")
                ?.jsonObject?.get("size")
                ?.jsonPrimitive?.intOrNull
        }.getOrNull()
        return size?.takeIf { it in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS }
    }

    /**
     * The provenance decision behind `contextFromBundle`: a valid declaration
     * is adopted and attributed to the bundle; anything unreadable reverts to
     * the default assumption with the flag down — never the *previous*
     * bundle's window, which the bundle now on disk no longer backs. Pure so
     * the engine-side decision is pinned by JVM tests.
     */
    fun applyDeclaration(declared: Int?): Pair<Int, Boolean> =
        if (declared != null) declared to true else DEFAULT_CONTEXT_TOKENS to false

    /** Prompt tokens usable when [replyTokens] must still fit the window. */
    fun promptBudget(contextTokens: Int, replyTokens: Int, margin: Int = PROMPT_MARGIN): Int =
        (contextTokens - replyTokens - margin).coerceAtLeast(MIN_PROMPT_BUDGET)
}
