package com.veritransit.inspector.ai

/**
 * Kokoro token vocabulary (115 entries, from the Kokoro-82M ONNX tokenizer).
 * Generated — do not hand-edit. Phoneme strings tokenize longest-match
 * against these keys into the model's input IDs.
 */
object KokoroVocab {
    val ID: Map<String, Long> = mapOf(
        "$" to 0L,
        ";" to 1L,
        ":" to 2L,
        "," to 3L,
        "." to 4L,
        "!" to 5L,
        "?" to 6L,
        "—" to 9L,
        "…" to 10L,
        "\"" to 11L,
        "(" to 12L,
        ")" to 13L,
        "“" to 14L,
        "”" to 15L,
        " " to 16L,
        "̃" to 17L,
        "ʣ" to 18L,
        "ʥ" to 19L,
        "ʦ" to 20L,
        "ʨ" to 21L,
        "ᵝ" to 22L,
        "ꭧ" to 23L,
        "A" to 24L,
        "I" to 25L,
        "O" to 31L,
        "Q" to 33L,
        "S" to 35L,
        "T" to 36L,
        "W" to 39L,
        "Y" to 41L,
        "ᵊ" to 42L,
        "a" to 43L,
        "b" to 44L,
        "c" to 45L,
        "d" to 46L,
        "e" to 47L,
        "f" to 48L,
        "h" to 50L,
        "i" to 51L,
        "j" to 52L,
        "k" to 53L,
        "l" to 54L,
        "m" to 55L,
        "n" to 56L,
        "o" to 57L,
        "p" to 58L,
        "q" to 59L,
        "r" to 60L,
        "s" to 61L,
        "t" to 62L,
        "u" to 63L,
        "v" to 64L,
        "w" to 65L,
        "x" to 66L,
        "y" to 67L,
        "z" to 68L,
        "ɑ" to 69L,
        "ɐ" to 70L,
        "ɒ" to 71L,
        "æ" to 72L,
        "β" to 75L,
        "ɔ" to 76L,
        "ɕ" to 77L,
        "ç" to 78L,
        "ɖ" to 80L,
        "ð" to 81L,
        "ʤ" to 82L,
        "ə" to 83L,
        "ɚ" to 85L,
        "ɛ" to 86L,
        "ɜ" to 87L,
        "ɟ" to 90L,
        "ɡ" to 92L,
        "ɥ" to 99L,
        "ɨ" to 101L,
        "ɪ" to 102L,
        "ʝ" to 103L,
        "ɯ" to 110L,
        "ɰ" to 111L,
        "ŋ" to 112L,
        "ɳ" to 113L,
        "ɲ" to 114L,
        "ɴ" to 115L,
        "ø" to 116L,
        "ɸ" to 118L,
        "θ" to 119L,
        "œ" to 120L,
        "ɹ" to 123L,
        "ɾ" to 125L,
        "ɻ" to 126L,
        "ʁ" to 128L,
        "ɽ" to 129L,
        "ʂ" to 130L,
        "ʃ" to 131L,
        "ʈ" to 132L,
        "ʧ" to 133L,
        "ʊ" to 135L,
        "ʋ" to 136L,
        "ʌ" to 138L,
        "ɣ" to 139L,
        "ɤ" to 140L,
        "χ" to 142L,
        "ʎ" to 143L,
        "ʒ" to 147L,
        "ʔ" to 148L,
        "ˈ" to 156L,
        "ˌ" to 157L,
        "ː" to 158L,
        "ʰ" to 162L,
        "ʲ" to 164L,
        "↓" to 169L,
        "→" to 171L,
        "↗" to 172L,
        "↘" to 173L,
        "ᵻ" to 177L,
    )

    /** Longest-match tokenization of an espeak IPA string into input IDs. */
    fun tokenize(phonemes: String, maxLen: Int = 510): LongArray {
        val clean = phonemes.replace("\u0361", "")
        val ids = ArrayList<Long>(clean.length)
        var i = 0
        // Longest key is 2 chars in this vocab; 3 covers any future addition.
        while (i < clean.length && ids.size < maxLen) {
            var hit: Long? = null
            var len = 0
            var l = 3
            while (l >= 1) {
                if (i + l <= clean.length) {
                    val hitId = ID[clean.substring(i, i + l)]
                    if (hitId != null) { hit = hitId; len = l; break }
                }
                l--
            }
            if (hit == null) { i++ ; continue } // skip unknown marks, never stall
            ids.add(hit)
            i += len
        }
        return ids.toLongArray()
    }
}
