package com.lagradost.quicknovel

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.quicknovel.ProviderTestSupport.runNetworkTest
import com.lagradost.quicknovel.providers.japanese.KakuyomuProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented integration test for [KakuyomuProvider] (kakuyomu.jp). Hits the live site;
 * network failures are logged as SKIPPED rather than failing.
 *
 *   gradlew connectedDebugAndroidTest
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.quicknovel.KakuyomuProviderTest
 */
@RunWith(AndroidJUnit4::class)
class KakuyomuProviderTest {
    companion object {
        private const val TAG = "KakuyomuTest"
        private lateinit var provider: KakuyomuProvider

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            ProviderTestSupport.initApp()
            provider = KakuyomuProvider()
        }
    }

    @Test
    fun testMainPage() = runNetworkTest(TAG, "testMainPage") {
        val result = provider.loadMainPage(1, null, provider.orderBys.first().second, provider.tags.first().second)
        Log.i(TAG, "Main page ${result.url}: ${result.list.size} works")
        assertTrue("Main page returned no works", result.list.isNotEmpty())
    }

    @Test
    fun testSearchLoadAndChapter() = runNetworkTest(TAG, "testSearchLoadAndChapter") {
        val results = provider.search("転生")
        Log.i(TAG, "Search: ${results.size} results, first=${results.firstOrNull()?.name}")
        assertTrue("Search returned no results", results.isNotEmpty())

        val stream = provider.load(results.first().url) as? StreamResponse
        assertNotNull("load() did not return a StreamResponse", stream)
        stream!!
        Log.i(TAG, "Load: ${stream.name} by ${stream.author}, ${stream.data.size} episodes")
        assertTrue("No title", stream.name.isNotBlank())
        assertTrue("No episodes", stream.data.isNotEmpty())

        val html = provider.loadHtml(stream.data.first().url)
        assertNotNull("loadHtml() returned null", html)
        Log.i(TAG, "Episode 1: ${html!!.length} chars, preview=${html.take(120)}")
        assertTrue("Episode content too short", html.length >= 100)
        assertTrue("Episode content is not <p> HTML", html.contains("<p>"))
    }
}
