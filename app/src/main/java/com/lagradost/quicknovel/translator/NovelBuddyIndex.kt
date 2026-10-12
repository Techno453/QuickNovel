package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.providers.SourceGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

/**
 * Pure parsing of the Novel Buddy search API for the title index (docs/search.md). The site is
 * not a reading source here (removed as an MTL aggregator, docs/sources.md); only its search
 * answer is read, because its entries carry the original CJK title as an alternative name.
 * JVM-tested on synthetic fixtures (`NovelBuddyIndexTest`).
 */
object NovelBuddyParser {
    data class Hit(val title: String, val url: String, val altNames: List<String>, val isMtl: Boolean)

    private val mapper = jacksonObjectMapper()
    private val MTL_PREFIX = Regex("""^\s*mtl\s*[-–:]\s*""", RegexOption.IGNORE_CASE)
    private val NAME_SEPARATORS = Regex("""\s*[,;、，；]\s*""")

    /**
     * Hits of a `/titles/search` answer in page order, each with its alternative names split on
     * commas and CJK separators; null for anything that is not such an answer.
     */
    fun parseSearch(json: String): List<Hit>? {
        val root: JsonNode = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val items = root.path("data").path("items")
        if (!items.isArray) return null
        return items.mapNotNull { item ->
            val title = item.path("name").asText("").trim().ifEmpty { return@mapNotNull null }
            val url = item.path("url").asText("").trim()
            val raw = ArrayList<String>()
            item.path("alt_names").forEach { raw += it.path("name").asText("") }
            if (raw.isEmpty()) raw += item.path("alt_name").asText("")
            val altNames = raw.flatMap { it.split(NAME_SEPARATORS) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            Hit(title, url, altNames, item.path("is_mtl").asBoolean(false))
        }
    }

    /**
     * CJK alternative names of the hits that are the novel named [query]: a hit whose title (an
     * "MTL -" prefix ignored) or a Latin alternative name equals the query, ignoring case and
     * punctuation. Other hits of the same search are unrelated novels, whatever names they carry.
     * Human-translation entries first, then MTL entries; distinct.
     */
    fun namesFor(query: String, hits: List<Hit>): List<String> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val matching = hits.filter { hit ->
            normalize(MTL_PREFIX.replace(hit.title, "")) == q ||
                    hit.altNames.any { NovelUpdatesParser.languagesOf(it).isEmpty() && normalize(it) == q }
        }.sortedBy { if (it.isMtl) 1 else 0 }
        return SearchQueryTranslator.distinctQueries(
            matching.flatMap { hit -> hit.altNames.filter { NovelUpdatesParser.languagesOf(it).isNotEmpty() } }
        )
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}

/**
 * Key-free title index on the Novel Buddy search API: the original CJK titles of the novel an
 * English (Latin-script) query names, so raw Chinese / Japanese / Korean sources can be searched
 * by them (docs/search.md). A lookup index only: never a reading provider, never shown as a
 * result. One JSON request per query with a browser user agent; answers cached in memory; a
 * block, an HTTP error, a malformed answer or an exception gives up quietly (empty list).
 */
object NovelBuddyIndex {
    const val BASE_URL = "https://api.novelbuddy.me/titles"
    const val LIMIT = 10
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_CACHE_ENTRIES = 100
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun interface Fetcher {
        suspend fun get(url: String): NovelUpdatesIndex.Page
    }

    /** The app's plain client: the API answers JSON without a Cloudflare challenge. */
    val deviceFetcher = Fetcher { url ->
        val response = MainActivity.app.get(
            url,
            headers = mapOf("User-Agent" to BROWSER_UA, "Accept" to "application/json"),
        )
        NovelUpdatesIndex.Page(response.code, { response.headers[it] }, response.text)
    }

    private val cache = LookupCache("NovelBuddyIndex", MAX_CACHE_ENTRIES)

    /**
     * CJK alternative names of the entries named [query], or an empty list when the query is not
     * Latin-script, nothing matched, or the API could not be reached.
     */
    suspend fun associatedNames(query: String, fetcher: Fetcher = deviceFetcher): List<String> {
        val trimmed = query.trim()
        if (!NovelUpdatesParser.isLatinQuery(trimmed)) return emptyList()
        return cache.get(trimmed) {
            withTimeoutOrNull(TIMEOUT_MS) { lookup(trimmed, fetcher) } ?: IndexLookup(emptyList(), "timeout")
        }
    }

    fun searchUrl(query: String): String =
        "$BASE_URL/search?page=1&limit=$LIMIT&q=${URLEncoder.encode(query, "UTF-8")}"

    /** Uncached lookup: one request. */
    suspend fun lookup(query: String, fetcher: Fetcher): IndexLookup {
        val page = try {
            fetcher.get(searchUrl(query))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return IndexLookup(emptyList(), "error: ${e.javaClass.simpleName}")
        }
        SourceGuard.classify(page.code, page.header, page.body)?.let { return IndexLookup(emptyList(), it.name) }
        if (page.code !in 200..299) return IndexLookup(emptyList(), "HTTP ${page.code}")
        val hits = NovelBuddyParser.parseSearch(page.body) ?: return IndexLookup(emptyList(), "parse")
        return IndexLookup(NovelBuddyParser.namesFor(query, hits))
    }

    fun clearCache() = cache.clear()
}
