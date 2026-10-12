package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.LengthRange
import com.lagradost.quicknovel.LengthUnit
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.providers.chinese.CzbooksProvider
import com.lagradost.quicknovel.providers.chinese.IxdzsProvider
import com.lagradost.quicknovel.providers.japanese.KakuyomuProvider
import com.lagradost.quicknovel.providers.japanese.PixivNovelProvider
import com.lagradost.quicknovel.providers.korean.NaverWebNovelProvider
import com.lagradost.quicknovel.providers.korean.NovelpiaProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live check of the length filter (docs/browse.md, "Length filter"): the sites that filter
 * server-side honour the range, and the listings of the client-side sources carry a length.
 * Skipped unless run with `-Dlive=1`:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*LengthListingsLiveTest*' -Dlive=1
 *
 * 69shuba search, Piaotia and Hameln only answer the device WebView, so they are covered by the
 * fixtures only.
 */
class LengthListingsLiveTest {
    companion object {
        private val enabled: Boolean
            get() = (System.getProperty("live") ?: System.getenv("LIVE"))
                ?.let { it.isNotBlank() && it != "0" && !it.equals("false", true) } == true

        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "length-live").apply { isDaemon = true } }

        @BeforeClass
        @JvmStatic
        fun initHttp() {
            if (enabled) CjkProvidersLiveSmokeTest.initHttp(force = true)
        }
    }

    /** One blocking site call on a daemon thread, bounded (`app.get` can park forever). */
    private fun page(api: MainAPI, main: String?, order: String?, tag: String?, length: LengthRange? = null): List<SearchResponse> {
        assumeTrue("live tests need -Dlive=1", enabled)
        val list = pool.submit(Callable {
            runBlocking { api.loadMainPage(1, main, order, tag, length).list }
        }).get(90, TimeUnit.SECONDS)
        println("[live ${api.name}] ${list.size} items; ${list.take(3).map { it.name to it.length }}")
        assertTrue("${api.name}: empty listing", list.isNotEmpty())
        return list
    }

    private fun MainAPI.first(): Triple<String?, String?, String?> =
        Triple(mainCategories.firstOrNull()?.second, orderBys.firstOrNull()?.second, tags.firstOrNull()?.second)

    private fun assertMostHaveLength(api: MainAPI, list: List<SearchResponse>, unit: LengthUnit) {
        val with = list.count { it.length?.valueIn(unit) != null }
        assertTrue("${api.name}: only $with of ${list.size} items have a length in $unit", with * 10 >= list.size * 8)
    }

    @Test
    fun royalRoadSearchHonoursThePageRange() {
        val api = RoyalRoadProvider()
        val range = LengthRange(500, 1500)
        val list = page(api, null, "best-rated", "fantasy", range)
        assertTrue(list.all { it.length?.pages != null && it.length!!.pages!! in 500..1500 })
        val longOnes = page(api, null, "complete", "", LengthRange(1500, null))
        assertTrue(longOnes.all { (it.length?.pages ?: 0) >= 1500 })
    }

    @Test
    fun royalRoadListsCarryPages() {
        val api = RoyalRoadProvider()
        assertMostHaveLength(api, page(api, null, "best-rated", ""), LengthUnit.PAGES)
    }

    @Test
    fun syosetuSearchHonoursTheCharacterRange() {
        val api = SyosetuProvider()
        val list = page(api, "201", "weekly", "er", LengthRange(500_000, 2_000_000))
        assertTrue(list.all { (it.length?.characters ?: -1) in 500_000..2_000_000 })
        assertMostHaveLength(api, page(api, "total", "total", ""), LengthUnit.CHARACTERS)
    }

    @Test
    fun clientSideSourcesCarryLengths() {
        val cases = listOf(
            KakuyomuProvider() to LengthUnit.CHARACTERS,
            IxdzsProvider() to LengthUnit.CHARACTERS,
            NovelpiaProvider() to LengthUnit.CHAPTERS,
            NaverWebNovelProvider() to LengthUnit.CHAPTERS,
        )
        for ((api, unit) in cases) {
            val (main, order, tag) = api.first()
            assertMostHaveLength(api, page(api, main, order, tag), unit)
        }
        // Pixiv: one-shots carry their length, series rows of the ranking do not.
        val pixiv = PixivNovelProvider()
        val (pm, po, pt) = pixiv.first()
        assertTrue(page(pixiv, pm, po, pt).any { it.length?.characters != null })
        // czbooks: the latest chapter's number, for most cards.
        val czbooks = CzbooksProvider()
        val (cm, co, ct) = czbooks.first()
        val cz = page(czbooks, cm, co, ct)
        assertTrue(cz.count { it.length?.chapters != null } * 2 >= cz.size)
    }
}
