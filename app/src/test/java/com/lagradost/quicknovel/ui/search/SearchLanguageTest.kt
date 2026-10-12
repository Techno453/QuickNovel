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

    // ---- preference ----

    @Test
    fun preferenceDefaultIsAll() {
        assertEquals("search_language", SearchLanguage.PREF_KEY)
        assertEquals(SearchLanguage.ALL, SearchLanguage.DEFAULT)
        assertEquals("all", SearchLanguage.DEFAULT.prefValue)
        assertEquals(SearchLanguage.ALL, SearchLanguage.fromPref(null))
        assertEquals(SearchLanguage.ALL, SearchLanguage.fromPref(""))
        assertEquals(SearchLanguage.ALL, SearchLanguage.fromPref("klingon"))
    }

    @Test
    fun preferenceRoundTrips() {
        for (lang in SearchLanguage.entries) {
            assertEquals(lang, SearchLanguage.fromPref(lang.prefValue))
        }
        assertEquals(SearchLanguage.KOREAN, SearchLanguage.fromPref(" KO "))
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
