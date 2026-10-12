package com.lagradost.quicknovel.translator

/**
 * Per-novel translation style: how names are rendered and what happens to honorifics.
 * Pure Kotlin; values are stored as strings in [com.lagradost.quicknovel.translator.db.NovelContext].
 */
data class TranslationStyle(
    /** One of [nameStyleOptions] for the source language. */
    val nameStyle: String,
    /** [HONORIFICS_KEEP], [HONORIFICS_DROP] or [HONORIFICS_ADAPT]. */
    val honorificStyle: String,
    /** Free-text instructions from the user, passed to the model verbatim. */
    val styleNotes: String = "",
) {
    companion object {
        // Chinese name styles
        const val NAME_PINYIN = "pinyin"
        const val NAME_MEANING = "meaning"
        const val NAME_MIXED = "mixed"

        // Japanese / Korean name order
        const val NAME_GIVEN_FAMILY = "given-family"
        const val NAME_FAMILY_GIVEN = "family-given"

        const val HONORIFICS_KEEP = "keep"
        const val HONORIFICS_DROP = "drop"
        const val HONORIFICS_ADAPT = "adapt"

        val HONORIFIC_OPTIONS = listOf(HONORIFICS_KEEP, HONORIFICS_DROP, HONORIFICS_ADAPT)

        fun nameStyleOptions(lang: String?): List<String> = when (TranslationLanguages.normalize(lang)) {
            TranslationLanguages.JAPANESE, TranslationLanguages.KOREAN -> listOf(NAME_FAMILY_GIVEN, NAME_GIVEN_FAMILY)
            else -> listOf(NAME_PINYIN, NAME_MEANING, NAME_MIXED)
        }

        fun defaultNameStyle(lang: String?): String = nameStyleOptions(lang).first()

        fun defaultHonorificStyle(lang: String?): String = when (TranslationLanguages.normalize(lang)) {
            TranslationLanguages.JAPANESE, TranslationLanguages.KOREAN -> HONORIFICS_KEEP
            else -> HONORIFICS_ADAPT
        }

        /**
         * Resolves stored values: blank or invalid entries fall back to [fallbackName] /
         * [fallbackHonorific] (global settings) and then to the per-language defaults.
         */
        fun resolve(
            lang: String?,
            nameStyle: String?,
            honorificStyle: String?,
            styleNotes: String? = "",
            fallbackName: String? = null,
            fallbackHonorific: String? = null,
        ): TranslationStyle {
            val names = nameStyleOptions(lang)
            val name = listOf(nameStyle, fallbackName).firstOrNull { it != null && it in names }
                ?: defaultNameStyle(lang)
            val honorific = listOf(honorificStyle, fallbackHonorific).firstOrNull { it != null && it in HONORIFIC_OPTIONS }
                ?: defaultHonorificStyle(lang)
            return TranslationStyle(name, honorific, styleNotes?.trim().orEmpty())
        }

        fun defaultFor(lang: String?): TranslationStyle = resolve(lang, null, null)
    }
}
