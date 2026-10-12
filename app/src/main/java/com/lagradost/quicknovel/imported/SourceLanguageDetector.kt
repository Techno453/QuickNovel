package com.lagradost.quicknovel.imported

import com.lagradost.quicknovel.translator.TranslationLanguages

/**
 * Guesses the source language (zh / ja / ko) of an imported book from its text.
 *
 * Online novels take their language from the provider ([com.lagradost.quicknovel.MainAPI.lang]);
 * the app has no language-identification model (only ML Kit translation), and the three
 * translatable languages are told apart reliably by their scripts:
 * - Korean: Hangul syllables dominate (Hanja is rare in modern prose);
 * - Japanese: kana present in any real amount (Chinese prose has none);
 * - Chinese: Han characters without kana or Hangul.
 *
 * Returns null when there is too little CJK text to decide (Latin text, image-only books,
 * empty files); the result page then asks for a manual choice ([effective]).
 */
object SourceLanguageDetector {
    /** Fewer CJK letters than this: undecided. */
    const val MIN_CJK_LETTERS = 20

    data class Counts(val han: Int, val kana: Int, val hangul: Int) {
        val total get() = han + kana + hangul
    }

    fun count(text: CharSequence): Counts {
        var han = 0
        var kana = 0
        var hangul = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            when {
                cp in 0x3041..0x309F || cp in 0x30A0..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9D -> {
                    // ー (U+30FC) is shared with Chinese web text only rarely; count it as kana.
                    kana++
                }
                cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F -> hangul++
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN -> han++
            }
            i += Character.charCount(cp)
        }
        return Counts(han, kana, hangul)
    }

    /** "zh", "ja", "ko" or null (undecided). */
    fun detect(text: CharSequence): String? {
        val c = count(text)
        if (c.total < MIN_CJK_LETTERS) return null
        return when {
            c.hangul * 10 >= c.total * 3 -> TranslationLanguages.KOREAN // ≥ 30% Hangul
            c.kana * 20 >= c.total -> TranslationLanguages.JAPANESE // ≥ 5% kana
            c.han * 2 >= c.total -> TranslationLanguages.CHINESE
            else -> null
        }
    }

    /** The language the pipeline uses: the user's override, else the detected one, else null. */
    fun effective(detected: String?, override: String?): String? =
        TranslationLanguages.normalize(override) ?: TranslationLanguages.normalize(detected)
}
