package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.providers.ProviderFixtures
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import com.lagradost.quicknovel.providers.chinese.IxdzsProvider
import com.lagradost.quicknovel.providers.chinese.PiaotiaProvider
import com.lagradost.quicknovel.providers.japanese.AlphapolisProvider
import com.lagradost.quicknovel.providers.japanese.HamelnProvider
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
 * Browse listings (docs/browse.md): every CJK provider of the Browse tab exposes English listing
 * labels with a popularity ranking first (the Browse home loads index 0), and each listing value
 * maps to the URL verified against the live site (2026-10).
 */
class BrowseListingsTest {
    private val kakuyomu = KakuyomuProvider()
    private val novelpia = NovelpiaProvider()
    private val naver = NaverWebNovelProvider()
    private val ixdzs = IxdzsProvider()
    private val piaotia = PiaotiaProvider()
    private val alphapolis = AlphapolisProvider()
    private val pixiv = PixivNovelProvider()
    private val shuba = BiqugeProvider()
    private val hameln = HamelnProvider()

    private val all: List<MainAPI> = listOf(kakuyomu, novelpia, naver, ixdzs, piaotia, alphapolis, pixiv, shuba, hameln)

    @Test
    fun listingsAreEnglishAndStartWithARanking() {
        val popular = Regex("ranking|popular|top 100|most viewed", RegexOption.IGNORE_CASE)
        for (api in all) {
            assertTrue("${api.name} has listings", api.mainCategories.size >= 3)
            val first = api.mainCategories.first().first
            assertTrue("${api.name}: first listing '$first' is not a popularity ranking", popular.containsMatchIn(first))
            for ((label, value) in api.mainCategories) {
                assertTrue("${api.name}: '$label' must start in English", label.first().isLetterOrDigit() && label.first().code < 0x80)
                assertTrue("${api.name}: empty value for '$label'", value.isNotBlank())
            }
            assertEquals("${api.name}: duplicate labels", api.mainCategories.size, api.mainCategories.map { it.first }.toSet().size)
            // Listings replaced the old "order by" lists for these providers.
            assertTrue("${api.name} should not mix listings with a separate order", api.orderBys.isEmpty())
        }
    }

    @Test
    fun everyProviderOffersCompletedWorksAndNewOrLatest() {
        for (api in all - pixiv) { // pixiv rankings have no completed filter
            val labels = api.mainCategories.map { it.first.lowercase() }
            assertTrue("${api.name}: no completed listing in $labels", labels.any { "completed" in it })
        }
        for (api in all) {
            val labels = api.mainCategories.map { it.first.lowercase() }
            assertTrue("${api.name}: no new/latest listing in $labels",
                labels.any { "new" in it || "latest" in it || "newest" in it })
        }
    }

    @Test
    fun kakuyomuListingUrls() {
        assertEquals("https://kakuyomu.jp/search?order=weekly_ranking", kakuyomu.listingUrl(null, null, 1))
        assertEquals(
            "https://kakuyomu.jp/search?genre_name=fantasy&order=popular&serial_status=completed&page=2",
            kakuyomu.listingUrl("popular|completed", "fantasy", 2)
        )
        assertEquals("https://kakuyomu.jp/search?order=published_at", kakuyomu.listingUrl("published_at", "", 1))
    }

    @Test
    fun novelpiaListingUrls() {
        assertEquals(
            "https://novelpia.com/top100/freestory/today/view/all/all?main_genre=",
            novelpia.listingUrl(null, null, 1)
        )
        assertNull("a Top 100 has one page", novelpia.listingUrl("top100:all/weekly", "", 2))
        assertEquals(
            "https://novelpia.com/freestory/all/view/3/all?main_genre=1",
            novelpia.listingUrl("free:view", "1", 3)
        )
    }

    @Test
    fun novelpiaTop100UsesDesktopBoxesOnly() {
        val results = NovelpiaParser.parseBrowse(novelpia, ProviderFixtures.text("novelpia/synthetic_top100.html"))
        assertEquals(listOf("가나다 라마", "바사아 자차"), results.map { it.name })
        assertEquals("https://novelpia.com/novel/111", results[0].url)
        assertEquals("https://images.novelpia.com/imagebox/cover/aaa_ori.wimg", results[0].posterUrl)
    }

    @Test
    fun naverListingUrls() {
        val first = NaverWebNovelProvider.Listing.parse(naver.mainCategories.first().second)
        assertEquals("https://novel.naver.com/best/ranking?genre=102&periodType=DAILY", naver.listingUrl(first, null, 1))
        assertNull(naver.listingUrl(first, null, 2))
        val completed = NaverWebNovelProvider.Listing.parse("genre|best|Read|finish")
        assertEquals(
            "https://novel.naver.com/best/genre?genre=110&order=Read&finish=true&page=2",
            naver.listingUrl(completed, "110", 2)
        )
        val newWorks = NaverWebNovelProvider.Listing.parse("rank|challenge|DAILY|right")
        assertTrue(newWorks.rightColumn)
        assertEquals("challenge", newWorks.league)
    }

    @Test
    fun naverRankingReadsOneColumn() {
        val doc = Jsoup.parse(ProviderFixtures.text("naver/synthetic_ranking.html"), "https://novel.naver.com/best/ranking")
        val left = NaverWebNovelParser.parseRanking(naver, doc)
        assertEquals(listOf("랭킹 작품 하나", "랭킹 작품 둘"), left.map { it.name })
        assertEquals("https://novel.naver.com/best/list?novelId=1197211", left[0].url)
        assertEquals("384회", left[0].latestChapter)
        assertNull("placeholder cover dropped", left[1].posterUrl)
        val right = NaverWebNovelParser.parseRanking(naver, doc, right = true)
        assertEquals(listOf("오른쪽 작품"), right.map { it.name })
    }

    @Test
    fun ixdzsListingUrls() {
        assertEquals("https://ixdzs8.com/hot/", ixdzs.listingUrl(null, null, 1))
        assertEquals("https://ixdzs8.com/end/?page=3", ixdzs.listingUrl("path:/end/", "2", 3))
        assertEquals("https://ixdzs8.com/sort/2/index-0-2-1-4.html", ixdzs.listingUrl("genre:2-1", "2", 4))
        assertEquals("https://ixdzs8.com/sort/1/index-0-0-0-1.html", ixdzs.listingUrl("genre:0-0", null, 1))
    }

    @Test
    fun piaotiaListingUrls() {
        assertEquals("https://www.piaotia.com/booktopweekvisit/0/1.html", piaotia.listingUrl(null, null, 1))
        assertEquals(
            "https://www.piaotia.com/modules/article/articlelist.php?fullflag=1&page=2",
            piaotia.listingUrl("full", "3", 2)
        )
        assertEquals("https://www.piaotia.com/booksort3/0/5.html", piaotia.listingUrl("sort", "3", 5))
    }

    @Test
    fun alphapolisListingUrls() {
        assertEquals("https://www.alphapolis.co.jp/novel/index?sort=24hpt", alphapolis.listingUrl(null, "", 1))
        assertEquals(
            "https://www.alphapolis.co.jp/novel/index?sort=completed&category_ids=110400&page=2",
            alphapolis.listingUrl("completed", "110400", 2)
        )
    }

    @Test
    fun pixivListingUrls() {
        assertEquals("https://www.pixiv.net/novel/ranking.php?mode=weekly_original", pixiv.listingUrl(null, null, 1))
        assertEquals("https://www.pixiv.net/novel/ranking.php?mode=daily&p=2", pixiv.listingUrl("rank:daily", null, 2))
        assertTrue(pixiv.listingUrl("tag:date_d", "異世界", 1).contains("/ajax/search/novels/"))
    }

    @Test
    fun pixivRankingFoldsSeriesAndFiltersRows() {
        val results = PixivNovelParser.parseRanking(pixiv, ProviderFixtures.text("pixiv/synthetic_ranking.html"))
        assertEquals(listOf("ひとつめの話", "連載のタイトル"), results.map { it.name })
        assertEquals("https://www.pixiv.net/novel/show.php?id=29339325", results[0].url)
        assertEquals("https://www.pixiv.net/novel/series/13549259", results[1].url)
        assertTrue(results[0].posterUrl!!.contains("/c/240x480_80/"))
        assertEquals("https://www.pixiv.net/", results[0].posterHeaders?.get("Referer"))
    }
}
