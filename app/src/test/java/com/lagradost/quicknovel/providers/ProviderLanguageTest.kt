package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import com.lagradost.quicknovel.providers.japanese.KakuyomuProvider
import com.lagradost.quicknovel.providers.korean.NovelpiaProvider
import com.lagradost.quicknovel.util.Apis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translated search and on-demand translation pick the source language from
 * [MainAPI.lang]; a CJK provider left on the default "en" would never be translated.
 */
class ProviderLanguageTest {
    @Test
    fun knownCjkProvidersDeclareTheirLanguage() {
        val expected = mapOf<MainAPI, String>(
            BiqugeProvider() to "zh",
            QuanbenProvider() to "zh",
            KakuyomuProvider() to "ja",
            SyosetuProvider() to "ja",
            NovelpiaProvider() to "ko",
        )
        for ((api, lang) in expected) assertEquals(api.name, lang, api.lang)
    }

    @Test
    fun registeredCjkProvidersAllDeclareACjkLanguage() {
        val cjk = setOf("zh", "ja", "ko")
        val registered = Apis.apis
        // Every known CJK provider is registered.
        for (name in listOf("Quanben", "Syosetu")) {
            assertTrue("$name not registered", registered.any { it.name == name })
        }
        // Any registered provider that looks CJK (CJK script in its name or filter labels, or a
        // CJK top-level domain) must declare zh / ja / ko. Catches new providers added without it.
        val offenders = registered.filter { looksCjk(it) && it.lang !in cjk }
            .map { "${it.name} (lang=${it.lang})" }
        assertTrue("CJK providers without a CJK lang: $offenders", offenders.isEmpty())
        assertTrue(registered.count { it.lang in cjk } >= 5)
    }

    private fun looksCjk(api: MainAPI): Boolean {
        val labels = listOf(api.name) + api.tags.map { it.first } + api.mainCategories.map { it.first } +
                api.orderBys.map { it.first }
        val host = api.mainUrl.substringAfter("://").substringBefore('/')
        return labels.any { label -> label.any { isCjk(it) } } ||
                host.endsWith(".cn") || host.endsWith(".jp") || host.endsWith(".kr")
    }

    private fun isCjk(c: Char): Boolean = c in '一'..'鿿' || c in '぀'..'ヿ' || c in '가'..'힣'
}
