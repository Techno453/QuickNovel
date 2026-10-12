package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.providers.SourceGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Pure parsing of NovelUpdates pages for the title index (docs/search.md): search hits and the
 * "Associated Names" block of a series page, which lists the original CJK title(s).
 * JVM-tested with a synthetic fixture (`NovelUpdatesParserTest`).
 */
object NovelUpdatesParser {
    data class SeriesHit(val title: String, val url: String)
    data class SeriesPage(val title: String, val associatedNames: List<String>)

    /** Series hits of a search page (`/?s=…&post_type=seriesplans`), in page order. */
    fun parseSearch(html: String, baseUrl: String = NovelUpdatesIndex.BASE_URL): List<SeriesHit> {
        val doc = Jsoup.parse(html, baseUrl)
        val links = doc.select(".search_main_box_nu .search_title a[href*=/series/]")
            .ifEmpty { doc.select(".search_title a[href*=/series/]") }
        return links.mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = a.text().trim().ifBlank { return@mapNotNull null }
            SeriesHit(title, url)
        }.distinctBy { it.url }
    }

    /** Title and associated names of a series page; null when it is not one. */
    fun parseSeries(html: String): SeriesPage? {
        val doc = Jsoup.parse(html)
        val block = doc.selectFirst("#editassociated") ?: return null
        val title = doc.selectFirst(".seriestitlenu")?.text()?.trim().orEmpty()
        // One name per line; the block separates them with <br>.
        val names = block.html().split(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE))
            .map { Jsoup.parse(it).text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return SeriesPage(title, names)
    }

    /**
     * Hits ordered for opening: exact title matches of [query] first (case and punctuation
     * ignored), then the rest in page order.
     */
    fun rankHits(query: String, hits: List<SeriesHit>): List<SeriesHit> {
        val q = normalize(query)
        return hits.sortedBy { if (normalize(it.title) == q) 0 else 1 }
    }

    /** Script languages of a name: Hangul → ko, kana → ja, Han only → zh and ja; none for Latin. */
    fun languagesOf(name: String): Set<String> {
        var han = false
        var kana = false
        var hangul = false
        for (c in name) {
            val block = Character.UnicodeBlock.of(c)
            when {
                SearchQueryTranslator.isHangul(block) -> hangul = true
                SearchQueryTranslator.isKana(block) -> kana = true
                SearchQueryTranslator.isHan(block) -> han = true
            }
        }
        return when {
            hangul -> setOf(TranslationLanguages.KOREAN)
            kana -> setOf(TranslationLanguages.JAPANESE)
            han -> setOf(TranslationLanguages.CHINESE, TranslationLanguages.JAPANESE)
            else -> emptySet()
        }
    }

    /**
     * Names usable as queries on [lang]'s raw sources, best first, at most [max]: for Japanese,
     * names with kana before Han-only ones (a Han-only name may be a Chinese translation).
     */
    fun namesForLanguage(names: List<String>, lang: String, max: Int = 3): List<String> {
        val code = TranslationLanguages.normalize(lang) ?: return emptyList()
        val usable = names.filter { code in languagesOf(it) }
        val ordered = if (code == TranslationLanguages.JAPANESE) {
            usable.sortedBy { n -> if (n.any { SearchQueryTranslator.isKana(Character.UnicodeBlock.of(it)) }) 0 else 1 }
        } else usable
        return SearchQueryTranslator.distinctQueries(ordered).take(max)
    }

    /** True for a query worth looking up: has a Latin letter and no CJK character. */
    fun isLatinQuery(query: String): Boolean =
        query.any { it in 'a'..'z' || it in 'A'..'Z' } && languagesOf(query).isEmpty()

    private fun normalize(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}

/**
 * Key-free title index: looks an English (Latin-script) query up on NovelUpdates and returns the
 * CJK associated names of the top series, so raw Chinese / Japanese / Korean sources can be
 * searched by their original title (docs/search.md).
 *
 * A lookup index only: never a reading provider, never shown as a result. Polite by design: a
 * browser user agent, at most [MAX_FETCHES] page fetches per query, [GAP_MS] between requests.
 * On the device the request goes through the Cloudflare interceptor client, so a solvable "Just a
 * moment" challenge is cleared by the hidden WebView; a firewall block, a challenge that stays,
 * or any error gives up quietly (empty list). Answers are cached in memory per query.
 */
object NovelUpdatesIndex {
    const val BASE_URL = "https://www.novelupdates.com"
    const val MAX_FETCHES = 3
    const val GAP_MS = 1_000L
    private const val TIMEOUT_MS = 25_000L
    private const val MAX_CACHE_ENTRIES = 100
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    /** One fetched page. [header] looks a response header up case-insensitively. */
    class Page(val code: Int, val header: (String) -> String?, val body: String)

    fun interface Fetcher {
        suspend fun get(url: String): Page
    }

    /** The app's client with the Cloudflare interceptor (WebView challenge solve). */
    val deviceFetcher = Fetcher { url ->
        val response = MainActivity.appWithInterceptor.get(
            url,
            headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        )
        Page(response.code, { response.headers[it] }, response.text)
    }

    private val cache = LookupCache("NovelUpdatesIndex", MAX_CACHE_ENTRIES)

    /**
     * CJK associated names of the series matching [query] (best series first), or an empty list
     * when the query is not Latin-script, nothing matched, or NovelUpdates could not be reached.
     * A block or an error is remembered briefly (the zh/ja/ko lookups of one search share it),
     * not for the session: it may clear later.
     */
    suspend fun associatedNames(query: String, fetcher: Fetcher = deviceFetcher): List<String> {
        val trimmed = query.trim()
        if (!NovelUpdatesParser.isLatinQuery(trimmed)) return emptyList()
        return cache.get(trimmed) {
            withTimeoutOrNull(TIMEOUT_MS) { lookup(trimmed, fetcher) } ?: IndexLookup(emptyList(), "timeout")
        }
    }

    /** Uncached lookup: search page, then up to [MAX_FETCHES] - 1 series pages. */
    suspend fun lookup(query: String, fetcher: Fetcher): IndexLookup {
        var lastFailure: String? = null
        var fetches = 0
        suspend fun fetch(url: String): String? {
            if (fetches >= MAX_FETCHES) return null
            if (fetches > 0) delay(GAP_MS)
            fetches++
            val page = try {
                fetcher.get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = "error: ${e.javaClass.simpleName}"
                return null
            }
            val block = SourceGuard.classify(page.code, page.header, page.body)
            if (block != null) {
                lastFailure = block.name
                return null
            }
            if (page.code !in 200..299) {
                lastFailure = "HTTP ${page.code}"
                return null
            }
            return page.body
        }

        val q = URLEncoder.encode(query, "UTF-8")
        val searchHtml = fetch("$BASE_URL/?s=$q&post_type=seriesplans")
            ?: return IndexLookup(emptyList(), lastFailure)
        val hits = NovelUpdatesParser.rankHits(query, NovelUpdatesParser.parseSearch(searchHtml))
        if (hits.isEmpty()) return IndexLookup(emptyList())
        val names = ArrayList<String>()
        for (hit in hits.take(MAX_FETCHES - 1)) {
            val html = fetch(hit.url) ?: return IndexLookup(SearchQueryTranslator.distinctQueries(names), lastFailure)
            val page = NovelUpdatesParser.parseSeries(html) ?: continue
            names += page.associatedNames.filter { NovelUpdatesParser.languagesOf(it).isNotEmpty() }
            // The exact match carries the names we want; a second series only adds noise.
            if (names.isNotEmpty()) break
        }
        return IndexLookup(SearchQueryTranslator.distinctQueries(names))
    }

    fun clearCache() = cache.clear()
}
