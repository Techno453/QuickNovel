package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.util.Apis

/**
 * Source languages the AI translation pipeline understands.
 *
 * Providers declare their language through [com.lagradost.quicknovel.MainAPI.lang]
 * (ISO 639-1). Everything translation-related (translate button, title auto-translation,
 * search query translation, on-demand reader translation) should gate on
 * [isTranslatable] instead of comparing against a hard-coded language code.
 */
object TranslationLanguages {
    data class SourceLanguage(
        /** ISO 639-1 code, as used by MainAPI.lang. */
        val code: String,
        /** English display name. */
        val displayName: String,
        /** Code for the free Google Translate endpoint. */
        val googleCode: String,
    )

    const val CHINESE = "zh"
    const val JAPANESE = "ja"
    const val KOREAN = "ko"

    /** Used when a translation record has no language (pre-v2 rows were all Chinese). */
    const val DEFAULT = CHINESE

    val all: List<SourceLanguage> = listOf(
        SourceLanguage(CHINESE, "Chinese", "zh-CN"),
        SourceLanguage(JAPANESE, "Japanese", "ja"),
        SourceLanguage(KOREAN, "Korean", "ko"),
    )

    private val byCode = all.associateBy { it.code }

    /**
     * Normalizes a language tag ("zh-CN", "zh_TW", "JA", "ko-KR") to its translatable
     * base code, or returns null if the language is not translatable.
     */
    fun normalize(lang: String?): String? {
        if (lang.isNullOrBlank()) return null
        val base = lang.trim().lowercase().substringBefore('-').substringBefore('_')
        return base.takeIf { it in byCode }
    }

    /** True if [lang] (ISO 639-1, optionally with region) is a supported translation source. */
    fun isTranslatable(lang: String?): Boolean = normalize(lang) != null

    fun get(lang: String?): SourceLanguage? = normalize(lang)?.let { byCode[it] }

    fun displayName(lang: String?): String = get(lang)?.displayName ?: "Source"

    /** Google Translate language code for [lang], falling back to the raw code. */
    fun googleCode(lang: String): String = get(lang)?.googleCode ?: lang

    /** Convenience for UI code that only knows the provider name. */
    fun isTranslatableApi(apiName: String?): Boolean = langOfApi(apiName) != null

    /** Normalized source language of a provider, or null if it is not translatable. */
    fun langOfApi(apiName: String?): String? =
        apiName?.let { normalize(Apis.getApiFromNameNull(it)?.lang) }
}
