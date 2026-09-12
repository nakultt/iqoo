package com.veritransit.inspector.ai

import com.veritransit.core.ReasonCode
import com.veritransit.core.ScanResult
import com.veritransit.core.spoken

/**
 * Assembles espeak phoneme strings for the neural (Kokoro) path.
 *
 * Mirrors [com.veritransit.core.VoiceAlertText] sentence-for-sentence so both
 * voices say the same thing — except dynamic free text (item names), which no
 * fixed table can cover: codes are spelled letter-by-letter, EWB numbers read
 * digit-by-digit, and gate lines are counts (see `forGateCounted`).
 *
 * Returns null when any part has no transcription: the caller must fall back
 * to system TTS rather than speak half an alert.
 */
object KokoroG2P {

    private fun s(key: String): String? = KokoroPhrases.SENTENCE[key]

    /** Full scan announcement as phonemes, or null → use system TTS. */
    fun scan(
        result: ScanResult,
        code: String?,
        reasons: List<ReasonCode>,
        announcePasses: Boolean = false,
    ): String? {
        val parts = ArrayList<String>()
        when (result) {
            ScanResult.VERIFIED -> {
                if (!announcePasses) return null
                parts += s("Verified.") ?: return null
                if (!code.isNullOrBlank()) parts += spellCode(code)
            }
            ScanResult.SUSPECT_REVIEW -> {
                parts += s("Suspect package.") ?: return null
                if (!code.isNullOrBlank()) parts += spellCode(code)
                parts += reasonPart(reasons) ?: return null
            }
            ScanResult.REJECTED -> {
                parts += s("Rejected.") ?: return null
                if (!code.isNullOrBlank()) parts += spellCode(code)
                parts += reasonPart(reasons) ?: return null
                parts += s("Do not load this carton.") ?: return null
            }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun reasonPart(reasons: List<ReasonCode>): String? {
        if (reasons.isEmpty()) return s("Held for review.")
        val parts = ArrayList<String>()
        reasons.take(2).forEach { parts += s(it.spoken) ?: return null }
        parts += s("Held for review.") ?: return null
        return parts.joinToString(" ")
    }

    /** Counted gate announcement as phonemes, or null → use system TTS. */
    fun gate(ewbDigits: String, lineCount: Int, unlistedCount: Int): String? {
        if (lineCount == 0 && unlistedCount == 0) return null
        val parts = ArrayList<String>()
        parts += s("Inspection attention.") ?: return null
        parts += s("E-Way Bill.") ?: return null
        parts += digits(ewbDigits)
        if (lineCount > 0) {
            parts += countWord(lineCount) ?: return null
            parts += s(if (lineCount == 1) "line discrepant." else "lines discrepant.") ?: return null
        }
        if (unlistedCount > 0) {
            parts += countWord(unlistedCount) ?: return null
            parts += s(if (unlistedCount == 1) "unlisted parcel observed." else "unlisted parcels observed.")
                ?: return null
        }
        parts += s("Hold for review.") ?: return null
        return parts.joinToString(" ")
    }

    /** "VT-P-03B..." → letter names + digit words ("viː tiː piː ..."). */
    fun spellCode(code: String): String {
        val parts = ArrayList<String>()
        for (ch in code.uppercase()) {
            when {
                ch == '-' || ch == ' ' -> parts += " " // pause, don't say "dash"
                ch.isLetter() -> parts += KokoroPhrases.LETTER[ch] ?: ch.toString()
                ch.isDigit() -> {
                    val w = KokoroPhrases.DIGIT_WORD[ch] ?: continue
                    parts += s(w) ?: continue
                }
            }
        }
        return parts.joinToString(" ")
    }

    /** "7819..." → digit words. */
    fun digits(digits: String): String =
        digits.filter { it.isDigit() }.mapNotNull { d ->
            KokoroPhrases.DIGIT_WORD[d]?.let { s(it) }
        }.joinToString(" ")

    /** 0–99 as phonemes ("twenty three"), else null → caller falls back. */
    fun countWord(n: Int): String? {
        if (n < 0 || n > 99) return null
        val ones = listOf("zero","one","two","three","four","five","six","seven","eight","nine")
        if (n <= 10 || n in 11..19 || n == 20 || n == 30 || n == 40 || n == 50 ||
            n == 60 || n == 70 || n == 80 || n == 90
        ) {
            val w = when (n) {
                0 -> "zero"; 1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"
                5 -> "five"; 6 -> "six"; 7 -> "seven"; 8 -> "eight"; 9 -> "nine"
                10 -> "ten"; 11 -> "eleven"; 12 -> "twelve"; 13 -> "thirteen"
                14 -> "fourteen"; 15 -> "fifteen"; 16 -> "sixteen"; 17 -> "seventeen"
                18 -> "eighteen"; 19 -> "nineteen"; 20 -> "twenty"; 30 -> "thirty"
                40 -> "forty"; 50 -> "fifty"; 60 -> "sixty"; 70 -> "seventy"
                80 -> "eighty"; else -> "ninety"
            }
            return s(w)
        }
        val tens = listOf("twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety")
        val t = tens[n / 10 - 2]
        val o = ones[n % 10]
        val a = s(t) ?: return null
        val b = s(o) ?: return null
        return "$a $b"
    }
}
