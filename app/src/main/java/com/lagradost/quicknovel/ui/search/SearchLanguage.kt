package com.lagradost.quicknovel.ui.search

import com.lagradost.quicknovel.translator.TranslationLanguages

/**
 * The language chips of the Search tab (docs/search.md): which sources a search queries and which
 * language the query is translated into. Pure, JVM-tested.
 *
 * A source takes part when its `MainAPI.lang` has the chip's base language ("zh-CN" counts as
 * Chinese). An unset lang counts as English (the upstream default of `MainAPI.lang`). [ALL] queries
 * every enabled source, other languages (ar, es, ru, …) included. The user's per-source list
 * (Settings → Search providers) and provider-language list still apply on top.
 */
enum class SearchLanguage(
    /** Base ISO 639-1 code, null for [ALL]. */
    val code: String?,
    /** Value stored under [PREF_KEY]. */
    val prefValue: String,
) {
    ALL(null, "all"),
    CHINESE(TranslationLanguages.CHINESE, "zh"),
    JAPANESE(TranslationLanguages.JAPANESE, "ja"),
    KOREAN(TranslationLanguages.KOREAN, "ko"),
    ENGLISH("en", "en");

    /** True if a source whose `MainAPI.lang` is [sourceLang] is searched under this chip. */
    fun matches(sourceLang: String?): Boolean = code == null || baseLang(sourceLang) == code

    /** [items] whose language (from [langOf]) matches this chip, in their given order. */
    fun <T> filter(items: List<T>, langOf: (T) -> String?): List<T> =
        if (this == ALL) items else items.filter { matches(langOf(it)) }

    /**
     * Language the query is translated into for a source whose lang is [sourceLang], or null when
     * the original query is sent: English and other non-CJK sources, and sources this chip does not
     * search. Under [ALL] each CJK source gets its own language (the behaviour before the chips);
     * under a CJK chip only that language is ever a target.
     */
    fun queryLanguageFor(sourceLang: String?): String? {
        if (!matches(sourceLang)) return null
        return TranslationLanguages.normalize(sourceLang)
    }

    /**
     * Every language the query is translated into for a search over sources with [sourceLangs]:
     * under [ALL] each CJK language that has a source, under Chinese / Japanese / Korean that
     * language alone (if it has a source), under English none.
     */
    fun translationTargets(sourceLangs: Collection<String?>): Set<String> =
        sourceLangs.mapNotNullTo(linkedSetOf()) { queryLanguageFor(it) }

    companion object {
        /** SharedPreferences key (default preferences) of the selected chip. */
        const val PREF_KEY = "search_language"

        /** Selected chip when nothing is stored. */
        val DEFAULT = ALL

        /** Chip order on screen. */
        val CHIPS: List<SearchLanguage> = entries

        /** The chip stored as [value]; [DEFAULT] for null or an unknown value. */
        fun fromPref(value: String?): SearchLanguage =
            entries.firstOrNull { it.prefValue == value?.trim()?.lowercase() } ?: DEFAULT

        /** Base language of a source: "zh-CN" → "zh", "pt-pt" → "pt", null or blank → "en". */
        fun baseLang(lang: String?): String {
            if (lang.isNullOrBlank()) return "en"
            return lang.trim().lowercase().substringBefore('-').substringBefore('_')
        }
    }
}
