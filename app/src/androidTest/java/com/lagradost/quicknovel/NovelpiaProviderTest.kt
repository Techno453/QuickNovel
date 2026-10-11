package com.lagradost.quicknovel

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.quicknovel.ProviderTestSupport.runNetworkTest
import com.lagradost.quicknovel.providers.korean.NovelpiaProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented integration test for [NovelpiaProvider] (novelpia.com, free episodes only).
 * Hits the live site; network failures are logged as SKIPPED rather than failing.
 *
 *   gradlew connectedDebugAndroidTest
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.quicknovel.NovelpiaProviderTest
 */
@RunWith(AndroidJUnit4::class)
class NovelpiaProviderTest {
    companion object {
        private const val TAG = "NovelpiaTest"
        private lateinit var provider: NovelpiaProvider

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            ProviderTestSupport.initApp()
            provider = NovelpiaProvider()
        }
    }

    @Test
    fun testMainPage() = runNetworkTest(TAG, "testMainPage") {
        val result = provider.loadMainPage(1, null, provider.orderBys.first().second, provider.tags.first().second)
        Log.i(TAG, "Main page ${result.url}: ${result.list.size} works")
        assertTrue("Main page returned no works", result.list.isNotEmpty())
    }

    @Test
    fun testSearch() = runNetworkTest(TAG, "testSearch") {
        val results = provider.search("마왕")
        Log.i(TAG, "Search: ${results.size} results, first=${results.firstOrNull()?.name}")
        assertTrue("Search returned no results", results.isNotEmpty())
    }

    /** Uses the main page (free serials only) so every listed episode should be readable. */
    @Test
    fun testLoadAndChapter() = runNetworkTest(TAG, "testLoadAndChapter") {
        val works = provider.loadMainPage(1, null, "view", "").list
        assertTrue("No works to load", works.isNotEmpty())

        val stream = works.take(5).firstNotNullOfOrNull { work ->
            (provider.load(work.url) as? StreamResponse)?.takeIf { it.data.isNotEmpty() }
        }
        assertNotNull("None of the first works had free episodes", stream)
        stream!!
        Log.i(TAG, "Load: ${stream.name} by ${stream.author}, ${stream.data.size} free episodes")

        val html = provider.loadHtml(stream.data.first().url)
        assertNotNull("loadHtml() returned null", html)
        Log.i(TAG, "Episode 1: ${html!!.length} chars, preview=${html.take(120)}")
        assertTrue("Episode content too short", html.length >= 100)
        assertTrue("Episode content is not <p> HTML", html.contains("<p>"))
    }
}
