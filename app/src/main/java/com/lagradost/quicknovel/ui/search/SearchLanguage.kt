package com.lagradost.quicknovel.ui.search

import androidx.compose.runtime.Immutable
import com.lagradost.quicknovel.translator.TranslationLanguages

/**
 * One language chip of the Search tab (docs/search.md). Pure, JVM-tested. The chips are
 * multi-select: what a search uses is a [SearchLanguages] selection.
 *
 * A source takes part under a chip when its `MainAPI.lang` has the chip's base language ("zh-CN"
 * counts as Chinese). An unset lang counts as English (the upstream default of `MainAPI.lang`).
 * [ALL] queries every enabled source, other languages (ar, es, ru, …) included. The user's
 * per-source list (Settings → Search providers) and provider-language list still apply on top.
 */
enum class SearchLanguage(
    /** Base ISO 639-1 code, null for [ALL]. */
    val code: String?,
    /** Value stored in the preferences ([SearchLanguages.PREF_KEY], and the old single value). */
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
     * search. Under [ALL] each CJK source gets its own language; under a CJK chip only that
     * language is ever a target.
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
        /** Chip order on screen. */
        val CHIPS: List<SearchLanguage> = entries

        /** The chip stored as [value], or null for null or an unknown value. */
        fun fromPrefValue(value: String?): SearchLanguage? =
            entries.firstOrNull { it.prefValue == value?.trim()?.lowercase() }

        /** Base language of a source: "zh-CN" → "zh", "pt-pt" → "pt", null or blank → "en". */
        fun baseLang(lang: String?): String {
            if (lang.isNullOrBlank()) return "en"
            return lang.trim().lowercase().substringBefore('-').substringBefore('_')
        }
    }
}

/**
 * The selected language chips of the Search tab: any of Chinese / Japanese / Korean / English.
 * The empty selection is "All languages" (every enabled source, other languages included), shown
 * as the All chip being selected. Sources searched = the union of the selected languages; a CJK
 * source gets candidate queries in its own language, English and others the raw query.
 */
@Immutable
data class SearchLanguages(val languages: Set<SearchLanguage> = emptySet()) {
    init {
        require(SearchLanguage.ALL !in languages) { "ALL is the empty selection" }
    }

    val isAll: Boolean get() = languages.isEmpty()

    /** Whether [chip] shows as selected: All when nothing else is, a language when it is chosen. */
    fun isSelected(chip: SearchLanguage): Boolean =
        if (chip == SearchLanguage.ALL) isAll else chip in languages

    /**
     * Selection after tapping [chip]: All clears the others; a language is added or removed
     * (removing the last one goes back to All).
     */
    fun toggle(chip: SearchLanguage): SearchLanguages = when {
        chip == SearchLanguage.ALL -> ALL
        chip in languages -> SearchLanguages(languages - chip)
        else -> SearchLanguages(languages + chip)
    }

    /** True if a source whose `MainAPI.lang` is [sourceLang] is searched. */
    fun matches(sourceLang: String?): Boolean = isAll || languages.any { it.matches(sourceLang) }

    /** [items] whose language (from [langOf]) is selected, in their given order. */
    fun <T> filter(items: List<T>, langOf: (T) -> String?): List<T> =
        if (isAll) items else items.filter { matches(langOf(it)) }

    /**
     * Language the query is translated into for a source whose lang is [sourceLang]: its own CJK
     * language when the source is searched, else null (raw query, or not searched at all).
     */
    fun queryLanguageFor(sourceLang: String?): String? {
        if (!matches(sourceLang)) return null
        return TranslationLanguages.normalize(sourceLang)
    }

    /** Every language the query is translated into for a search over sources with [sourceLangs]. */
    fun translationTargets(sourceLangs: Collection<String?>): Set<String> =
        sourceLangs.mapNotNullTo(linkedSetOf()) { queryLanguageFor(it) }

    /** Value stored under [PREF_KEY]. */
    fun toPref(): Set<String> = languages.mapTo(linkedSetOf()) { it.prefValue }

    companion object {
        /** Default SharedPreferences key: a string set of `zh` / `ja` / `ko` / `en` (empty = All). */
        const val PREF_KEY = "search_languages"

        /** Key of the single chip stored before the chips were multi-select (`all`, `zh`, …). */
        const val LEGACY_PREF_KEY = "search_language"

        val ALL = SearchLanguages()

        /** The selection stored as [values]; unknown values and `all` are ignored. */
        fun fromPref(values: Collection<String>?): SearchLanguages = SearchLanguages(
            values.orEmpty().mapNotNullTo(linkedSetOf()) { value ->
                SearchLanguage.fromPrefValue(value)?.takeIf { it != SearchLanguage.ALL }
            }
        )

        /**
         * The value to write under [PREF_KEY] when [stored] (all default preferences) still has
         * only the old single chip: `all` → empty, `zh` → {zh}, …; null when there is nothing to
         * migrate (the new key exists, or the old one is absent).
         */
        fun migrate(stored: Map<String, *>): Set<String>? {
            if (stored.containsKey(PREF_KEY)) return null
            val old = stored[LEGACY_PREF_KEY] as? String ?: return null
            return fromPref(listOf(old)).toPref()
        }
    }
}
