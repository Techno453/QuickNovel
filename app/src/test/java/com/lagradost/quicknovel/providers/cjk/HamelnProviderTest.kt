package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.network.CloudflareKiller
import com.lagradost.quicknovel.providers.ProviderFixtures
import com.lagradost.quicknovel.providers.SourceGuard
import com.lagradost.quicknovel.providers.japanese.HamelnParser
import com.lagradost.quicknovel.providers.japanese.HamelnProvider
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fixture tests for [HamelnProvider] (src/test/resources/providers/hameln, smartphone layout
 * recorded 2026-10-12 through a mobile browser that had passed Cloudflare; prose replaced).
 */
class HamelnProviderTest {
    private fun fixture(name: String): String =
        ProviderFixtures.bytes("hameln/$name").toString(Charsets.UTF_8)

    private val api = HamelnProvider()
    private val novelUrl = "https://syosetu.org/novel/425756/"

    @Test
    fun declaresJapaneseAndCloudflare() {
        assertEquals("ja", api.lang)
        assertTrue(api.usesCloudFlareKiller)
        assertTrue(api.rateLimitTime >= 1000L)
    }

    @Test
    fun rankingCardsWithoutAdultWorks() {
        val results = HamelnParser.parseList(api, fixture("rank.html"))
        // Six cards on the page; the fifth is tagged R-18 (edited in the fixture) and is dropped.
        assertEquals(
            listOf("TASでカンストした次の人生。", "卍解までは転生者の嗜み。", "最も強い戦士", "全員女体化しているホグワーツ", "転生しました、主人公でした。"),
            results.map { it.name }
        )
        assertEquals("https://syosetu.org/novel/427109/", results[0].url)
        assertEquals("Episode 37", results[0].latestChapter)
        assertEquals(810, results[0].rating)
        assertTrue(results.none { it.url.contains("429106") })
    }

    @Test
    fun searchAndLatestCards() {
        val search = HamelnParser.parseList(api, fixture("search.html"))
        assertEquals(5, search.size)
        assertEquals("バスタード・ソードマン", search[0].name)
        assertTrue(search.all { it.url.matches(Regex("""https://syosetu\.org/novel/\d+/""")) })
        // The recommendation box under the results is not a result.
        val latest = HamelnParser.parseList(api, fixture("latest.html"))
        assertEquals(listOf("酒を片手に笠を被り青春を謳歌せよ", "旅の仲間は家族となりて", "FINAL FANTASY VIII もう一人のレウァール"), latest.map { it.name })
        assertTrue(HamelnParser.parseList(api, fixture("search_empty.html")).isEmpty())
    }

    @Test
    fun novelPageEpisodesWithSections() {
        val toc = HamelnParser.parseToc(api, fixture("novel.html"), novelUrl)
        assertNotNull(toc)
        toc!!
        assertEquals("卍解までは転生者の嗜み。", toc.title)
        assertEquals("点差惨敗", toc.author)
        assertFalse(toc.oneShot)
        assertEquals(33, toc.chapters.size)
        assertEquals("1,000年前 - 1.因果応報", toc.chapters[0].name)
        assertEquals("https://syosetu.org/novel/425756/1.html", toc.chapters[0].url)
        assertEquals("2026-09-07", toc.chapters[0].dateOfRelease)
        assertEquals("https://syosetu.org/novel/425756/33.html", toc.chapters.last().url)
        assertTrue(toc.chapters.last().name.startsWith("113年前 - "))
        assertTrue("BLEACH" in toc.tags)
        assertTrue("R-15" in toc.tags)
        assertNotNull(toc.synopsis)
        assertFalse(toc.synopsis!!.contains("タグ"))
    }

    @Test
    fun infoPageStatusTagsRating() {
        val detail = HamelnParser.parseDetail(fixture("detail.html"))
        assertNotNull(detail)
        detail!!
        assertEquals("卍解までは転生者の嗜み。", detail.title)
        assertEquals(ReleaseStatus.Ongoing, detail.status) // 連載(連載中)
        assertEquals("BLEACH", detail.tags.first())
        assertTrue(detail.tags.containsAll(listOf("R-15", "オリ主", "チート")))
        assertEquals(891, detail.rating)
        assertEquals(1_719_601, detail.views)
        assertNotNull(detail.synopsis)

        val oneShot = HamelnParser.parseDetail(fixture("oneshot_detail.html"))!!
        assertEquals(ReleaseStatus.Completed, oneShot.status) // 短編 1話
    }

    @Test
    fun statusWords() {
        fun status(text: String) = HamelnParser.parseDetail(
            "<table class=\"table1\"><tr><td class=\"label\">話数</td><td>$text</td></tr></table>"
        )!!.status
        assertEquals(ReleaseStatus.Completed, status("連載(完結) 120話"))
        assertEquals(ReleaseStatus.Ongoing, status("連載(未完) 12話"))
        assertEquals(ReleaseStatus.Completed, status("短編 3話"))
    }

    @Test
    fun oneShotIsASingleChapterOnTheNovelPage() {
        val url = "https://syosetu.org/novel/428992/"
        val toc = HamelnParser.parseToc(api, fixture("oneshot.html"), url)!!
        assertTrue(toc.oneShot)
        assertEquals("バリ茶", toc.author)
        assertEquals(1, toc.chapters.size)
        assertEquals(url, toc.chapters[0].url)
        assertEquals(toc.title, toc.chapters[0].name)
        assertNotNull(HamelnParser.parseChapter(fixture("oneshot.html"), url))
    }

    @Test
    fun chapterText() {
        val html = HamelnParser.parseChapter(fixture("chapter.html"), "https://syosetu.org/novel/425756/1.html")
        assertNotNull(html)
        val paragraphs = Jsoup.parseBodyFragment(html!!).select("p").map { it.text() }
        assertTrue(paragraphs.size >= 20)
        assertTrue(paragraphs.none { it.isBlank() })
        // Chrome around the body (title, navigation, menu) is not part of the text.
        assertFalse(html.contains("目 次"))
        assertFalse(html.contains("1.因果応報"))
        assertNull(HamelnParser.parseChapter(fixture("detail.html"), "https://syosetu.org/"))
    }

    @Test
    fun chapterRubyIsFlattened() {
        val page = """<div id="honbun"><p id="1">「ここは、<ruby><rb>虚圏</rb><rp>(</rp><rt>ウェコムンド</rt><rp>)</rp></ruby>か」</p>""" +
                """<p id="2">　<ruby><rb>漢字</rb><rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>を読む。</p></div>"""
        val paragraphs = Jsoup.parseBodyFragment(HamelnParser.parseChapter(page, "https://syosetu.org/novel/1/1.html")!!)
            .select("p").map { it.text() }
        assertEquals(listOf("「ここは、虚圏《ウェコムンド》か」", "漢字を読む。"), paragraphs)
    }

    @Test
    fun listingUrls() {
        assertEquals("https://syosetu.org/?mode=rank_day", api.listingUrl(null, null, 1))
        assertEquals("https://syosetu.org/?mode=rank_ori_week", api.listingUrl("week", "ori", 1))
        assertEquals("https://syosetu.org/?mode=rank_2_complete_total", api.listingUrl("complete_total", "2", 1))
        assertEquals("https://syosetu.org/?mode=rank_new", api.listingUrl("new", "", 1))
        assertEquals("https://syosetu.org/search/?mode=search&type=45", api.listingUrl(HamelnProvider.LATEST, "", 1))
        assertEquals(
            "https://syosetu.org/search/?mode=search&type=45&gensaku=%E5%8E%9F%E4%BD%9C%EF%BC%9A%E3%82%AA%E3%83%AA%E3%82%B8%E3%83%8A%E3%83%AB&page=3",
            api.listingUrl(HamelnProvider.LATEST, "ori", 3)
        )
        assertEquals(
            "https://syosetu.org/search/?mode=search&word=%E8%BB%A2%E7%94%9F&type=28",
            api.searchUrl("転生")
        )
        // Every listing and scope stays on the all-ages host.
        for ((_, listing) in api.mainCategories) for ((_, scope) in api.tags) {
            assertTrue(api.listingUrl(listing, scope, 1).startsWith("https://syosetu.org/"))
        }
    }

    @Test
    fun rankingsArePagedLocally() {
        val all = (1..120).toList()
        assertEquals((1..50).toList(), HamelnParser.page(all, 1, 50))
        assertEquals((101..120).toList(), HamelnParser.page(all, 3, 50))
        assertTrue(HamelnParser.page(all, 4, 50).isEmpty())
        assertTrue(HamelnParser.isRanking(api.listingUrl("day", "", 1)))
        assertFalse(HamelnParser.isRanking(api.listingUrl(HamelnProvider.LATEST, "", 1)))
    }

    @Test
    fun ids() {
        assertEquals("425756", HamelnParser.novelId("https://syosetu.org/novel/425756/12.html"))
        assertNull(HamelnParser.novelId("https://syosetu.org/?mode=rank_day"))
        assertTrue(HamelnParser.isAdult(listOf("R-18")))
        assertFalse(HamelnParser.isAdult(listOf("R-15", "残酷な描写")))
    }

    @Test
    fun challengePageIsRecognised() {
        // What every request without cf_clearance gets (recorded 2026-10-12 with the JVM client).
        val challenge = ProviderFixtures.bytes("cloudflare/challenge_hameln.html").toString(Charsets.UTF_8)
        val headers = mapOf("server" to "cloudflare", "cf-mitigated" to "challenge")
        val header: (String) -> String? = { name -> headers.entries.firstOrNull { it.key.equals(name, true) }?.value }
        assertEquals(SourceGuard.Block.CLOUDFLARE_CHALLENGE, SourceGuard.classify(403, header, challenge))
        assertTrue(CloudflareKiller.isCloudflareChallenge(403, header, challenge.take(CloudflareKiller.BODY_SAMPLE_BYTES.toInt())))
    }

    /**
     * One search, one novel, one chapter and the first listing against the live site. Run with
     * -Dlive=1. The Cloudflare interceptor needs Android's WebView and CookieManager, so the JVM
     * test probes the site with the plain client first: Hameln has answered every JVM request
     * with a managed challenge so far, and then the test is skipped with a message (check it on
     * a device). If the site ever serves the JVM directly, the smoke test runs without the
     * interceptor.
     */
    @Test
    fun live() {
        assumeTrue("live test; run with -Dlive=1", LiveTests.enabled)
        val probe = runBlocking { MainActivity.app.get("${api.mainUrl}/", headers = mapOf("Referer" to "${api.mainUrl}/")) }
        val block = SourceGuard.classify(probe.code, { probe.headers[it] }, probe.text)
        if (block != null) println("[live] ${api.name}: HTTP ${probe.code} -> $block; skipped (needs the WebView interceptor on a device)")
        assumeTrue("Hameln answered the JVM client with $block; verify on a device", block == null)
        val plain = object : HamelnProvider() {
            override val usesCloudFlareKiller = false
        }
        runBlocking { LiveTests.smoke(plain, "転生") }
    }
}
