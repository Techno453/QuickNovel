package com.lagradost.quicknovel.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLanguageTest {
    private data class FakeApi(val name: String, val lang: String?)

    private val apis = listOf(
        FakeApi("Royal", "en"),
        FakeApi("Ixdzs", "zh"),
        FakeApi("Syosetu", "ja"),
        FakeApi("Novelpia", "ko"),
        FakeApi("Quanben", "zh-CN"),
        FakeApi("Kakuyomu", "JA"),
        FakeApi("Upstream", null),
        FakeApi("Ranobes", "ru"),
        FakeApi("Booktoki", "ko_KR"),
        FakeApi("Wuxia", ""),
    )

    private fun names(lang: SearchLanguage) = lang.filter(apis) { it.lang }.map { it.name }

    // ---- source filtering ----

    @Test
    fun allKeepsEverySourceInOrder() {
        assertEquals(apis.map { it.name }, names(SearchLanguage.ALL))
    }

    @Test
    fun chineseKeepsZhWithRegion() {
        assertEquals(listOf("Ixdzs", "Quanben"), names(SearchLanguage.CHINESE))
    }

    @Test
    fun japaneseIgnoresCase() {
        assertEquals(listOf("Syosetu", "Kakuyomu"), names(SearchLanguage.JAPANESE))
    }

    @Test
    fun koreanKeepsUnderscoreRegion() {
        assertEquals(listOf("Novelpia", "Booktoki"), names(SearchLanguage.KOREAN))
    }

    @Test
    fun englishKeepsEnAndUnsetButNotOtherLanguages() {
        assertEquals(listOf("Royal", "Upstream", "Wuxia"), names(SearchLanguage.ENGLISH))
        assertFalse(SearchLanguage.ENGLISH.matches("ru"))
        assertFalse(SearchLanguage.ENGLISH.matches("zh"))
    }

    @Test
    fun otherLanguagesOnlyUnderAll() {
        val ru = SearchLanguage.entries.filter { it.matches("ru") }
        assertEquals(listOf(SearchLanguage.ALL), ru)
    }

    @Test
    fun emptyListStaysEmpty() {
        assertTrue(SearchLanguage.CHINESE.filter(emptyList<FakeApi>()) { it.lang }.isEmpty())
    }

    // ---- query translation targets ----

    @Test
    fun allTranslatesIntoEachCjkLanguageWithSources() {
        assertEquals(setOf("zh", "ja", "ko"), SearchLanguage.ALL.translationTargets(apis.map { it.lang }))
        // Only the languages that have sources.
        assertEquals(setOf("ja"), SearchLanguage.ALL.translationTargets(listOf("en", "ja", null)))
    }

    @Test
    fun cjkChipTranslatesIntoThatLanguageOnly() {
        val langs = apis.map { it.lang }
        assertEquals(setOf("zh"), SearchLanguage.CHINESE.translationTargets(langs))
        assertEquals(setOf("ja"), SearchLanguage.JAPANESE.translationTargets(langs))
        assertEquals(setOf("ko"), SearchLanguage.KOREAN.translationTargets(langs))
    }

    @Test
    fun englishDoesNotTranslate() {
        assertTrue(SearchLanguage.ENGLISH.translationTargets(apis.map { it.lang }).isEmpty())
        assertNull(SearchLanguage.ENGLISH.queryLanguageFor("en"))
        assertNull(SearchLanguage.ENGLISH.queryLanguageFor(null))
    }

    @Test
    fun queryLanguagePerSource() {
        assertEquals("zh", SearchLanguage.ALL.queryLanguageFor("zh-CN"))
        assertEquals("ko", SearchLanguage.ALL.queryLanguageFor("ko"))
        assertNull(SearchLanguage.ALL.queryLanguageFor("en"))
        assertNull(SearchLanguage.ALL.queryLanguageFor("ru"))
        assertEquals("ja", SearchLanguage.JAPANESE.queryLanguageFor("ja"))
        // A source outside the chip is never searched, so it never gets a translation.
        assertNull(SearchLanguage.JAPANESE.queryLanguageFor("zh"))
        assertNull(SearchLanguage.CHINESE.queryLanguageFor("ko"))
    }

    @Test
    fun noTargetWhenTheChipHasNoSources() {
        assertTrue(SearchLanguage.KOREAN.translationTargets(listOf("zh", "en")).isEmpty())
    }

    // ---- multi-select (SearchLanguages) ----

    private fun names(selection: SearchLanguages) = selection.filter(apis) { it.lang }.map { it.name }

    private fun of(vararg langs: SearchLanguage) = SearchLanguages(langs.toSet())

    @Test
    fun emptySelectionIsAllLanguages() {
        val all = SearchLanguages.ALL
        assertTrue(all.isAll)
        assertTrue(all.isSelected(SearchLanguage.ALL))
        assertFalse(all.isSelected(SearchLanguage.CHINESE))
        assertEquals(apis.map { it.name }, names(all))
        assertTrue(all.matches("ru"))
    }

    @Test
    fun selectingALanguageUnselectsAll() {
        val zh = SearchLanguages.ALL.toggle(SearchLanguage.CHINESE)
        assertEquals(of(SearchLanguage.CHINESE), zh)
        assertFalse(zh.isSelected(SearchLanguage.ALL))
        assertTrue(zh.isSelected(SearchLanguage.CHINESE))
    }

    @Test
    fun languagesToggleIndependently() {
        val zhJa = SearchLanguages.ALL.toggle(SearchLanguage.CHINESE).toggle(SearchLanguage.JAPANESE)
        assertEquals(of(SearchLanguage.CHINESE, SearchLanguage.JAPANESE), zhJa)
        assertEquals(of(SearchLanguage.JAPANESE), zhJa.toggle(SearchLanguage.CHINESE))
        // Removing the last language goes back to All.
        assertEquals(SearchLanguages.ALL, of(SearchLanguage.KOREAN).toggle(SearchLanguage.KOREAN))
    }

    @Test
    fun allClearsTheOthers() {
        val three = of(SearchLanguage.CHINESE, SearchLanguage.KOREAN, SearchLanguage.ENGLISH)
        assertEquals(SearchLanguages.ALL, three.toggle(SearchLanguage.ALL))
        assertEquals(SearchLanguages.ALL, SearchLanguages.ALL.toggle(SearchLanguage.ALL))
    }

    @Test(expected = IllegalArgumentException::class)
    fun allIsNeverStoredAsALanguage() {
        SearchLanguages(setOf(SearchLanguage.ALL))
    }

    @Test
    fun sourcesAreTheUnionOfTheSelectedLanguages() {
        assertEquals(
            listOf("Ixdzs", "Novelpia", "Quanben", "Booktoki"),
            names(of(SearchLanguage.CHINESE, SearchLanguage.KOREAN)),
        )
        assertEquals(
            listOf("Royal", "Syosetu", "Kakuyomu", "Upstream", "Wuxia"),
            names(of(SearchLanguage.JAPANESE, SearchLanguage.ENGLISH)),
        )
        // Every listed language selected still leaves out other languages (only All has them).
        val four = of(SearchLanguage.CHINESE, SearchLanguage.JAPANESE, SearchLanguage.KOREAN, SearchLanguage.ENGLISH)
        assertFalse("Ranobes" in names(four))
        assertEquals(apis.size - 1, names(four).size)
    }

    @Test
    fun eachCjkSourceGetsItsOwnLanguageAndEnglishTheRawQuery() {
        val zhEn = of(SearchLanguage.CHINESE, SearchLanguage.ENGLISH)
        assertEquals("zh", zhEn.queryLanguageFor("zh-CN"))
        assertNull(zhEn.queryLanguageFor("en"))
        assertNull(zhEn.queryLanguageFor(null))
        // Not selected: not searched, never translated.
        assertNull(zhEn.queryLanguageFor("ja"))
        assertEquals(setOf("zh"), zhEn.translationTargets(apis.map { it.lang }))
        assertEquals(
            setOf("ja", "ko"),
            of(SearchLanguage.JAPANESE, SearchLanguage.KOREAN).translationTargets(apis.map { it.lang }),
        )
        assertEquals(setOf("zh", "ja", "ko"), SearchLanguages.ALL.translationTargets(apis.map { it.lang }))
        assertTrue(of(SearchLanguage.ENGLISH).translationTargets(apis.map { it.lang }).isEmpty())
    }

    // ---- preference ----

    @Test
    fun preferenceKeys() {
        assertEquals("search_languages", SearchLanguages.PREF_KEY)
        assertEquals("search_language", SearchLanguages.LEGACY_PREF_KEY)
    }

    @Test
    fun preferenceRoundTrips() {
        val zhKo = of(SearchLanguage.CHINESE, SearchLanguage.KOREAN)
        assertEquals(setOf("zh", "ko"), zhKo.toPref())
        assertEquals(zhKo, SearchLanguages.fromPref(zhKo.toPref()))
        assertEquals(emptySet<String>(), SearchLanguages.ALL.toPref())
        assertEquals(SearchLanguages.ALL, SearchLanguages.fromPref(emptySet()))
        assertEquals(SearchLanguages.ALL, SearchLanguages.fromPref(null))
        // Unknown values and "all" are ignored; case and spaces are not significant.
        assertEquals(of(SearchLanguage.JAPANESE), SearchLanguages.fromPref(setOf(" JA ", "klingon", "all")))
    }

    @Test
    fun oldSingleChipIsMigratedOnce() {
        assertEquals(emptySet<String>(), SearchLanguages.migrate(mapOf("search_language" to "all")))
        assertEquals(setOf("zh"), SearchLanguages.migrate(mapOf("search_language" to "zh")))
        assertEquals(setOf("ja"), SearchLanguages.migrate(mapOf("search_language" to "ja")))
        assertEquals(setOf("ko"), SearchLanguages.migrate(mapOf("search_language" to "ko")))
        assertEquals(setOf("en"), SearchLanguages.migrate(mapOf("search_language" to "en")))
        assertEquals(emptySet<String>(), SearchLanguages.migrate(mapOf("search_language" to "klingon")))
        // Nothing stored, or already migrated: nothing to do.
        assertNull(SearchLanguages.migrate(emptyMap<String, Any>()))
        assertNull(
            SearchLanguages.migrate(mapOf("search_language" to "zh", "search_languages" to setOf("ko")))
        )
        assertNull(SearchLanguages.migrate(mapOf("search_language" to 3)))
    }

    @Test
    fun chipPrefValues() {
        for (lang in SearchLanguage.entries) {
            assertEquals(lang, SearchLanguage.fromPrefValue(lang.prefValue))
        }
        assertNull(SearchLanguage.fromPrefValue(null))
        assertNull(SearchLanguage.fromPrefValue("klingon"))
    }

    @Test
    fun chipOrder() {
        assertEquals(
            listOf(
                SearchLanguage.ALL, SearchLanguage.CHINESE, SearchLanguage.JAPANESE,
                SearchLanguage.KOREAN, SearchLanguage.ENGLISH,
            ),
            SearchLanguage.CHIPS,
        )
    }
}
