package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.LengthFilterSupport
import com.lagradost.quicknovel.LengthRange
import com.lagradost.quicknovel.LengthUnit
import com.lagradost.quicknovel.NovelLength
import com.lagradost.quicknovel.providers.ProviderFixtures.document
import com.lagradost.quicknovel.providers.ProviderFixtures.text
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import com.lagradost.quicknovel.providers.chinese.CzbooksParser
import com.lagradost.quicknovel.providers.chinese.CzbooksProvider
import com.lagradost.quicknovel.providers.chinese.IxdzsParser
import com.lagradost.quicknovel.providers.chinese.IxdzsProvider
import com.lagradost.quicknovel.providers.chinese.PiaotiaParser
import com.lagradost.quicknovel.providers.chinese.PiaotiaProvider
import com.lagradost.quicknovel.providers.japanese.HamelnParser
import com.lagradost.quicknovel.providers.japanese.HamelnProvider
import com.lagradost.quicknovel.providers.japanese.KakuyomuParser
import com.lagradost.quicknovel.providers.japanese.KakuyomuProvider
import com.lagradost.quicknovel.providers.japanese.PixivNovelParser
import com.lagradost.quicknovel.providers.japanese.PixivNovelProvider
import com.lagradost.quicknovel.providers.korean.NaverWebNovelParser
import com.lagradost.quicknovel.providers.korean.NaverWebNovelProvider
import com.lagradost.quicknovel.providers.korean.NovelpiaParser
import com.lagradost.quicknovel.providers.korean.NovelpiaProvider
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lengths parsed from the listing fixtures (docs/browse.md, "Length filter"), the per-source
 * length capability, and the Royal Road / Syosetu URLs with and without a length range.
 */
class LengthParsingFixtureTest {

    @Test
    fun capabilities() {
        assertEquals(LengthFilterSupport(LengthUnit.PAGES, serverSide = true), RoyalRoadProvider().lengthFilter)
        assertEquals(LengthFilterSupport(LengthUnit.CHARACTERS, serverSide = true), SyosetuProvider().lengthFilter)
        val client = mapOf(
            KakuyomuProvider() to LengthUnit.CHARACTERS,
            PixivNovelProvider() to LengthUnit.CHARACTERS,
            HamelnProvider() to LengthUnit.CHARACTERS,
            IxdzsProvider() to LengthUnit.CHARACTERS,
            PiaotiaProvider() to LengthUnit.CHARACTERS,
            NovelpiaProvider() to LengthUnit.CHAPTERS,
            NaverWebNovelProvider() to LengthUnit.CHAPTERS,
            BiqugeProvider() to LengthUnit.CHAPTERS,
            CzbooksProvider() to LengthUnit.CHAPTERS,
            FreewebnovelProvider() to LengthUnit.CHAPTERS,
            LibReadProvider() to LengthUnit.CHAPTERS,
            ScrollersPubProvider() to LengthUnit.CHAPTERS,
        )
        for ((api, unit) in client) assertEquals(api.name, LengthFilterSupport(unit, serverSide = false), api.lengthFilter)
        // No length in their listings: no chip.
        assertNull(QuanbenProvider().lengthFilter)
        assertNull(ScribblehubProvider().lengthFilter)
    }

    // ---- Royal Road ----

    private val royalRoad = RoyalRoadProvider()

    @Test
    fun royalRoadListCardsCarryPagesAndChapters() {
        val results = royalRoad.parseFictionList(document("royalroad/best_rated.html"), latestUpdates = false)
        assertEquals(listOf("Mother of Learning", "The Perfect Run", "Super Minion"), results.map { it.name })
        assertEquals("https://www.royalroad.com/fiction/21220/mother-of-learning", results[0].url)
        assertEquals(NovelLength(chapters = 109, pages = 2932), results[0].length)
        assertEquals(NovelLength(chapters = 131, pages = 1689), results[1].length)
        assertEquals("109 Chapters", results[0].latestChapter)
        assertEquals(966, results[0].rating) // 4.83 * 200
        assertTrue(results.all { it.posterUrl?.startsWith("https://www.royalroadcdn.com/") == true })
    }

    @Test
    fun royalRoadSearchCardsCarryPagesAndChapters() {
        val results = royalRoad.parseFictionList(document("royalroad/search_pages.html"), latestUpdates = false)
        assertEquals(3, results.size)
        assertEquals(NovelLength(chapters = 63, pages = 1080), results[0].length)
        // The search was minPages=500&maxPages=1500.
        assertTrue(results.all { it.length!!.pages!! in 500..1500 })
    }

    @Test
    fun royalRoadLatestUpdatesHaveOnlyTheLatestChapter() {
        val results = royalRoad.parseFictionList(document("royalroad/latest_updates.html"), latestUpdates = true)
        assertEquals(3, results.size)
        assertEquals("1. It Is All Your Fault", results[0].latestChapter)
        // No stats on these cards: the latest chapter's number, approximate.
        assertEquals(NovelLength(chapters = 1, approximate = true), results[0].length)
        assertNull(results[0].length?.pages)
    }

    @Test
    fun royalRoadUrls() {
        assertEquals("https://www.royalroad.com/fictions/best-rated?page=2&genre=fantasy", royalRoad.listUrl(2, "best-rated", "fantasy"))
        assertEquals("https://www.royalroad.com/fictions/trending?page=1", royalRoad.listUrl(1, "trending", ""))
        assertEquals(
            "https://www.royalroad.com/fictions/search?minPages=500&maxPages=1500&tagsAdd=fantasy&orderBy=rating&page=1",
            royalRoad.searchListUrl(1, "best-rated", "fantasy", LengthRange(500, 1500)),
        )
        assertEquals(
            "https://www.royalroad.com/fictions/search?minPages=1500&status=COMPLETED&orderBy=popularity&page=3",
            royalRoad.searchListUrl(3, "complete", "", LengthRange(1500, null)),
        )
        assertEquals(
            "https://www.royalroad.com/fictions/search?maxPages=100&status=ONGOING&orderBy=popularity&page=1",
            royalRoad.searchListUrl(1, "active-popular", null, LengthRange(null, 100)),
        )
        assertEquals("last_update", royalRoad.searchOrder("latest-updates"))
        assertEquals("release_date", royalRoad.searchOrder("new-releases"))
        for (approximated in listOf("trending", "rising-stars", "weekly-popular", "writathon")) {
            assertEquals(approximated, "popularity", royalRoad.searchOrder(approximated))
        }
        // Every list has a search order.
        assertTrue(royalRoad.orderBys.all { royalRoad.searchOrder(it.second).isNotBlank() })
    }

    // ---- Syosetu ----

    private val syosetu = SyosetuProvider()

    @Test
    fun syosetuRankingAndSearchLengths() {
        val ranking = syosetu.parseRanking(document("syosetu/rank.html"))
        assertEquals(NovelLength(chapters = 662, characters = 2_209_879), ranking[0].length)
        assertTrue(ranking.all { it.length?.characters != null && it.length?.chapters != null })

        val search = syosetu.parseSearch(document("syosetu/search.html"))
        assertEquals(NovelLength(chapters = 286, characters = 2_830_123), search[0].length)
        assertEquals(NovelLength(chapters = 304, characters = 2_043_550), search[1].length)
        assertEquals(NovelLength(chapters = 1), syosetu.lengthOf("短編 1,234文字").let { it?.copy(characters = null) })
        assertEquals(1234L, syosetu.lengthOf("短編 1,234文字")?.characters)
    }

    @Test
    fun syosetuUrlsWithALength() {
        // Without a range the rankings stay.
        assertEquals("https://yomou.syosetu.com/rank/genrelist/type/daily_201_er/?p=2", syosetu.mainPageUrl(2, "201", "daily", "er", null))
        assertEquals(
            "https://yomou.syosetu.com/search.php?order=hyoka&minlen=500000&maxlen=2000000&p=1",
            syosetu.mainPageUrl(1, "total", "total", "", LengthRange(500_000, 2_000_000)),
        )
        assertEquals(
            "https://yomou.syosetu.com/search.php?order=weeklypoint&genre=201&type=er&minlen=500000&p=2",
            syosetu.mainPageUrl(2, "201", "weekly", "er", LengthRange(500_000, null)),
        )
        assertEquals(
            "https://yomou.syosetu.com/search.php?order=new&istt=1&biggenre=2&maxlen=100000&p=1",
            syosetu.mainPageUrl(1, "i2", SyosetuProvider.LATEST, null, LengthRange(null, 100_000)),
        )
        val orders = syosetu.orderBys.map { syosetu.searchOrder(it.second) }
        assertEquals(listOf("hyoka", "dailypoint", "weeklypoint", "monthlypoint", "quarterpoint", "yearlypoint", "new"), orders)
    }

    // ---- other sources ----

    @Test
    fun kakuyomuCharactersAndEpisodes() {
        val results = KakuyomuParser.parseSearch(KakuyomuProvider(), text("kakuyomu/search.html"))
        assertEquals(NovelLength(chapters = 174, characters = 831_302), results[0].length)
        assertTrue(results.all { it.length?.characters != null })
    }

    @Test
    fun pixivLengths() {
        val api = PixivNovelProvider()
        val grouped = PixivNovelParser.parseSearch(api, PixivNovelParser.body(text("pixiv/search.json")))
        val oneShot = grouped.first { it.url.endsWith("29361060") }
        assertEquals(NovelLength(chapters = 1, characters = 5000), oneShot.length)
        val series = grouped.first { it.url.contains("15840364") }
        assertEquals(NovelLength(chapters = 32, characters = 100_000), series.length)

        val plain = PixivNovelParser.parseSearch(api, PixivNovelParser.body(text("pixiv/search_title.json")))
        // An episode folded into its series only knows that episode's length: none.
        assertNull(plain.first { it.url.contains("16633979") }.length)

        val ranking = PixivNovelParser.parseRanking(api, text("pixiv/synthetic_ranking.html"))
        assertEquals(NovelLength(chapters = 1, characters = 36_093), ranking[0].length)
        assertNull(ranking[1].length) // a series row (its character_count is one episode's)
    }

    @Test
    fun novelpiaEpisodes() {
        val api = NovelpiaProvider()
        assertEquals(NovelLength(chapters = 1573), NovelpiaParser.parseBrowse(api, text("novelpia/synthetic_browse.html"))[0].length)
        assertNull(NovelpiaParser.parseBrowse(api, text("novelpia/synthetic_browse.html"))[1].length)
        assertEquals(NovelLength(chapters = 138), NovelpiaParser.parseBrowse(api, text("novelpia/synthetic_top100.html"))[0].length)
        assertEquals(NovelLength(chapters = 1582), NovelpiaParser.parseSearch(api, text("novelpia/search.json"))[0].length)
    }

    @Test
    fun naverTotalEpisodesAreExact() {
        val doc = Jsoup.parse(text("naver/genre.html"), "https://novel.naver.com/best/genre?genre=102")
        assertEquals(NovelLength(chapters = 76), NaverWebNovelParser.parseCards(NaverWebNovelProvider(), doc)[0].length)
    }

    @Test
    fun shuba69LatestChapterNumbers() {
        val api = BiqugeProvider()
        val rank = api.parseBookList(document("69shuba/rank.html", "https://www.69shuba.com/novels/monthvisit_0_0_1.htm"))
        assertEquals("1417.新书已发", rank[0].latestChapter)
        assertEquals(NovelLength(chapters = 1417, approximate = true), rank[0].length)
        val list = api.parseBookList(document("69shuba/booklist.html", "https://www.69shuba.com/novels/class/10.htm"))
        assertEquals(NovelLength(chapters = 568, approximate = true), list[0].length)
        val latest = api.parseBookList(document("69shuba/latest.html", "https://www.69shuba.com/last.html"))
        assertEquals("第1069章 我怕曦曦误会", latest[0].latestChapter)
        assertEquals(1069L, latest[0].length?.chapters)
    }

    @Test
    fun ixdzsWordCount() {
        val results = IxdzsParser.parseList(IxdzsProvider(), text("ixdzs/search.html"))
        assertEquals(NovelLength(characters = 2_894_000), results[0].length)
        assertEquals(NovelLength(characters = 8_090_000), results[1].length)
    }

    @Test
    fun czbooksLatestChapterNumbers() {
        val results = CzbooksParser.parseList(CzbooksProvider(), Jsoup.parse(text("czbooks/list_popular.html"), "https://czbooks.net/"), 1)
        assertNull(results[0].length) // "後記"
        assertEquals(NovelLength(chapters = 307, approximate = true), results[1].length) // "第307頁"
    }

    @Test
    fun piaotiaSizeColumn() {
        val bytes = ProviderFixtures.bytes("piaotia/search.html")
        val results = PiaotiaParser.parseGrid(PiaotiaProvider(), Jsoup.parse(bytes.toString(charset("GBK")), "https://www.piaotia.com/"))
        // 17265K of GBK text ≈ 17265 × 512 characters; "第一千章".
        assertEquals(NovelLength(chapters = 1000, characters = 17_265L * 512, approximate = true), results[0].length)
        assertEquals(NovelLength(chapters = 3, characters = 5_960L * 512, approximate = true), results[1].length)
        assertNull(PiaotiaParser.charactersOfSize("连载中"))
    }

    @Test
    fun hamelnEpisodesTimesAverage() {
        val results = HamelnParser.parseList(HamelnProvider(), text("hameln/rank.html"))
        assertEquals(NovelLength(chapters = 37, characters = 37L * 1942), results[0].length)
        assertEquals(NovelLength(chapters = 33, characters = 33L * 15323), results[1].length)
    }
}
