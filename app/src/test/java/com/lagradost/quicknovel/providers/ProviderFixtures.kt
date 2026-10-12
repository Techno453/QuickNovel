package com.lagradost.quicknovel.providers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Loads recorded provider pages from `src/test/resources/providers/<provider>/<name>` (see the
 * README there for how fixtures are recorded and scrubbed) and parses them the way the
 * providers do at runtime.
 */
object ProviderFixtures {
    fun bytes(path: String): ByteArray {
        val stream = ProviderFixtures::class.java.classLoader!!.getResourceAsStream("providers/$path")
            ?: throw AssertionError("missing fixture providers/$path")
        return stream.use { it.readBytes() }
    }

    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)

    /**
     * Parses a fixture as a Jsoup [Document]. Without [baseUri] this matches NiceHttp's
     * `response.document` (`Jsoup.parse(text)`, so `absUrl` is empty); pass the page URL for
     * providers that call `Jsoup.parse(html, url)` themselves.
     */
    fun document(path: String, baseUri: String? = null): Document =
        if (baseUri == null) Jsoup.parse(text(path)) else Jsoup.parse(text(path), baseUri)

    /** Texts of the `<p>` elements in reader HTML returned by `loadHtml`. */
    fun paragraphs(html: String): List<String> =
        Jsoup.parseBodyFragment(html).select("p").map { it.text() }

    /** Strings that must never reach the reader: scripts, ad slots, page navigation. */
    private val junk = listOf(
        "<script", "</script", "<style", "<iframe", "<ins",
        "loadAdv", "googletag", "gtag(", "adsbygoogle", "gnshbrequest", "window.",
        "上一章", "下一章", "加入书签", "前へ", "次へ", "目次", "ブックマーク", "다음화", "이전화",
    )

    /**
     * Shared chapter checks: at least [minParagraphs] non-blank paragraphs, and none of the
     * script / ad / navigation text the page wraps around the prose.
     */
    fun assertCleanChapter(html: String?, minParagraphs: Int, extraJunk: List<String> = emptyList()) {
        assertTrue("chapter html is null", html != null)
        html!!
        val nonBlank = paragraphs(html).filter { it.isNotBlank() }
        assertTrue("expected > $minParagraphs paragraphs, got ${nonBlank.size}", nonBlank.size > minParagraphs)
        for (bad in junk + extraJunk) {
            assertFalse("chapter contains junk '$bad'", html.contains(bad))
        }
    }
}
