package com.lagradost.quicknovel.ui.browse

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.ui.common.ImmutableHeadMainPageResponse
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Apis.Companion.getApiFromName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads source listings for the Browse tab (docs/browse.md) through the provider objects only
 * (never a WebView or the site in a browser):
 *
 * - Cache: every page loaded for (source, listing, page) is kept in memory until the app
 *   process ends and written through to a small disk cache ([ListingDiskCache]: 6 h TTL, newest
 *   200 pages) that is read on first access after a restart; pull to refresh / retry bypass both.
 *   Failures and empty pages are not cached.
 * - Politeness: the Browse home loads at most [RAIL_CONCURRENCY] sources at a time, the "All
 *   sources" feed at most [FEED_CONCURRENCY]; each load times out after [TIMEOUT_MS]
 *   ([CLOUDFLARE_TIMEOUT_MS] behind the Cloudflare interceptor). Concurrent
 *   requests for the same page share one network call. The provider's own rate limit still
 *   applies on top.
 */
object BrowseRepository {
    const val RAIL_CONCURRENCY = 2
    const val FEED_CONCURRENCY = 3
    const val TIMEOUT_MS = 25_000L

    /**
     * Sources behind the Cloudflare interceptor (`usesCloudFlareKiller`): the first request has
     * to clear the challenge in a hidden WebView (about 25-30 s on Hameln, including the 5 s
     * cookie settle); later requests reuse the cookie and are as fast as anywhere else.
     */
    const val CLOUDFLARE_TIMEOUT_MS = 60_000L

    /** Gate for the Browse home rails. */
    val railGate = Semaphore(RAIL_CONCURRENCY)

    /** Gate for the "All sources" feed. */
    val feedGate = Semaphore(FEED_CONCURRENCY)

    /**
     * One cached page: [tags] is the set of genre indexes (sorted; one entry for a plain listing,
     * several for a merged multi-genre page, see [TagFilter]).
     */
    data class PageKey(val api: String, val category: Int, val orderBy: Int, val tags: List<Int>, val page: Int) {
        constructor(api: String, listing: BrowseCatalog.Listing, page: Int) :
                this(api, listing.category, listing.orderBy, listOf(listing.tag), page)
    }

    private val cache by lazy {
        ListingCache<PageKey, ImmutableHeadMainPageResponse>(
            disk = BaseApplication.context?.cacheDir?.let { ListingDiskCache(java.io.File(it, DISK_DIR)) },
            diskKey = { it.diskKey() },
            encode = { DataStore.mapper.writeValueAsString(DiskListingPage.from(it)) },
            decode = { DataStore.mapper.readValue(it, DiskListingPage::class.java)?.toResponse() },
            // Empty pages are not cached: a site that briefly answers with nothing (rate limiting, a
            // hiccup) is asked again next time instead of ending the list.
            cacheable = { it.list.isNotEmpty() },
        )
    }

    /** Folder under the app's cache dir. */
    const val DISK_DIR = "browse_listings"

    /** Key on disk; bump the version when [DiskListingPage] changes shape. */
    fun PageKey.diskKey() = TagFilter.pageKey(api, category, orderBy, tags, page)

    /** The page in the memory cache (no disk read). */
    fun cached(key: PageKey): ImmutableHeadMainPageResponse? = cache.inMemory(key)

    /**
     * Page [page] (1-based) of [listing] on [apiName], from the memory or disk cache unless
     * [refresh]. [gate] limits how many sources load at once (null = not throttled, for the page
     * the user is looking at).
     */
    suspend fun page(
        apiName: String,
        listing: BrowseCatalog.Listing,
        page: Int,
        gate: Semaphore? = null,
        refresh: Boolean = false,
    ): Result<ImmutableHeadMainPageResponse> =
        cache.get(PageKey(apiName, listing, page), refresh) {
            if (gate != null) gate.withPermit { load(apiName, listing, page) } else load(apiName, listing, page)
        }

    /**
     * Page [page] of a source page filtered by several genres ([tags], indexes into the provider's
     * tags; at most [TagFilter.MAX_LOADED] are loaded): page [page] of each genre's listing, merged
     * by rank and de-duplicated by URL ([TagFilter.merge]). Each genre's page is cached on its own
     * and the merged page under the sorted tag set. A genre that fails is left out of the merged
     * page, which is then not cached (Retry or pull to refresh asks again); the page fails only
     * when every genre fails. One tag is the plain listing.
     */
    suspend fun page(
        apiName: String,
        category: Int,
        orderBy: Int,
        tags: List<Int>,
        page: Int,
        gate: Semaphore? = null,
        refresh: Boolean = false,
    ): Result<ImmutableHeadMainPageResponse> {
        val loaded = TagFilter.loaded(tags)
        if (loaded.size == 1) {
            return BrowseRepository.page(apiName, BrowseCatalog.Listing(category, orderBy, loaded[0]), page, gate, refresh)
        }
        val key = PageKey(apiName, category, orderBy, loaded, page)
        var partial = false
        val result = cache.get(key, refresh) {
            val results = coroutineScope {
                loaded.map { tag ->
                    async { BrowseRepository.page(apiName, BrowseCatalog.Listing(category, orderBy, tag), page, gate, refresh) }
                }.awaitAll()
            }
            val pages = results.mapNotNull { it.getOrNull() }
            if (pages.isEmpty()) return@get Result.failure(results.firstNotNullOf { it.exceptionOrNull() })
            partial = pages.size < results.size
            Result.success(
                ImmutableHeadMainPageResponse(
                    url = pages.first().url,
                    list = TagFilter.merge(pages.map { it.list }) { it.url }.toImmutableList(),
                )
            )
        }
        if (partial) cache.forget(key)
        return result
    }

    private suspend fun load(apiName: String, listing: BrowseCatalog.Listing, page: Int): Result<ImmutableHeadMainPageResponse> =
        withContext(Dispatchers.IO) {
            val api = getApiFromName(apiName)
            val timeoutMs = if (api.api.usesCloudFlareKiller) CLOUDFLARE_TIMEOUT_MS else TIMEOUT_MS
            withTimeoutOrNull(timeoutMs) {
                api.loadMainPageResult(
                    page = page,
                    mainCategory = api.mainCategories.getOrNull(listing.category)?.second,
                    orderBy = api.orderBys.getOrNull(listing.orderBy)?.second,
                    tag = api.tags.getOrNull(listing.tag)?.second,
                )
            } ?: Result.failure(java.util.concurrent.TimeoutException("$apiName did not answer in ${timeoutMs / 1000} s"))
        }

    /** Every source with a browsable main page, as [BrowseCatalog.Source]. */
    fun sources(): List<BrowseCatalog.Source> = Apis.apis.filter { it.hasMainPage }.map { it.toSource() }

    fun MainAPI.toSource() = BrowseCatalog.Source(
        name = name,
        lang = lang,
        mainCategories = mainCategories.map { it.first },
        orderBys = orderBys.map { it.first },
        tags = tags.map { it.first },
    )

    /** Traditional to Simplified Chinese for de-duplication (Android ICU, API 29+), else unchanged. */
    val chineseFold: (String) -> String by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transliterator = runCatching { android.icu.text.Transliterator.getInstance("Traditional-Simplified") }.getOrNull()
            if (transliterator != null) {
                { text: String -> runCatching { transliterator.transliterate(text) }.getOrDefault(text) }
            } else {
                { text: String -> text }
            }
        } else {
            { text: String -> text }
        }
    }
}

/** Per-viewer Browse preferences (default shared preferences). */
object BrowsePrefs {
    private const val LAST_LISTING_PREFIX = "browse_last_listing_"

    /** Keys the first-run setup wizard may store the reader's languages under (set or CSV). */
    private val READING_LANGUAGE_KEYS = listOf("reading_languages", "setup_reading_languages")

    private fun prefs(context: Context?) =
        (context ?: BaseApplication.context)?.applicationContext?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    /**
     * The listing last chosen on a source's browse page as (category, order, genre indexes), or
     * null. Stored as [TagFilter.encodeListing] ("0,1,3" or "0,1,2+5"; the single-tag form of
     * older versions reads the same way).
     */
    fun lastListing(context: Context?, apiName: String): Triple<Int, Int, List<Int>>? =
        TagFilter.decodeListing(prefs(context)?.getString(LAST_LISTING_PREFIX + apiName, null))

    fun setLastListing(context: Context?, apiName: String, category: Int, orderBy: Int, tags: List<Int>) {
        prefs(context)?.edit()?.putString(
            LAST_LISTING_PREFIX + apiName, TagFilter.encodeListing(category, orderBy, tags)
        )?.apply()
    }

    /** Reading languages stored by the setup wizard, if any (empty = show every language). */
    fun readingLanguages(context: Context?): List<String> {
        val p = prefs(context) ?: return emptyList()
        for (key in READING_LANGUAGE_KEYS) {
            val value = runCatching { p.all[key] }.getOrNull() ?: continue
            val langs = when (value) {
                is Set<*> -> value.filterIsInstance<String>()
                is String -> value.split(',', ';', ' ')
                else -> emptyList()
            }.map { it.trim() }.filter { it.isNotEmpty() }
            if (langs.isNotEmpty()) return langs
        }
        return emptyList()
    }
}

/** A listing page as stored on disk (only the fields a listing fills, see `ImmutableSearchResponse.from`). */
data class DiskListingPage(
    val url: String = "",
    val items: List<DiskListingItem> = emptyList(),
) {
    fun toResponse(): ImmutableHeadMainPageResponse = ImmutableHeadMainPageResponse(
        url = url,
        list = items.map { it.toResponse() }.toImmutableList(),
    )

    companion object {
        fun from(response: ImmutableHeadMainPageResponse) = DiskListingPage(
            url = response.url,
            items = response.list.map {
                DiskListingItem(
                    name = it.name,
                    url = it.url,
                    apiName = it.apiName,
                    posterUrl = it.posterUrl,
                    posterHeaders = it.posterHeaders,
                    rating = it.rating,
                    latestChapterName = it.latestChapterName,
                )
            },
        )
    }
}

data class DiskListingItem(
    val name: String = "",
    val url: String = "",
    val apiName: String = "",
    val posterUrl: String? = null,
    val posterHeaders: Map<String, String>? = null,
    val rating: Int? = null,
    val latestChapterName: String? = null,
) {
    fun toResponse() = ImmutableSearchResponse(
        name = name,
        url = url,
        apiName = apiName,
        posterUrl = posterUrl,
        posterHeaders = posterHeaders?.toImmutableMap(),
        rating = rating,
        latestChapterName = latestChapterName,
        timeOfCached = System.currentTimeMillis(),
        chaptersRead = ImmutableSearchResponse.chaptersRead(name),
    )
}
