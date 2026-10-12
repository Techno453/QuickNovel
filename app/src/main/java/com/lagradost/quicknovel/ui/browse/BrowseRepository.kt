package com.lagradost.quicknovel.ui.browse

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.ui.common.ImmutableHeadMainPageResponse
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Apis.Companion.getApiFromName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads source listings for the Browse tab (docs/browse.md) through the provider objects only
 * (never a WebView or the site in a browser):
 *
 * - Session cache: every page loaded for (source, listing, page) is kept in memory until the app
 *   process ends; pull to refresh / retry bypass it. Failures are not cached.
 * - Politeness: the Browse home loads at most [RAIL_CONCURRENCY] sources at a time, the "All
 *   sources" feed at most [FEED_CONCURRENCY]; each load times out after [TIMEOUT_MS]. Concurrent
 *   requests for the same page share one network call. The provider's own rate limit still
 *   applies on top.
 */
object BrowseRepository {
    const val RAIL_CONCURRENCY = 2
    const val FEED_CONCURRENCY = 3
    const val TIMEOUT_MS = 25_000L

    /** Gate for the Browse home rails. */
    val railGate = Semaphore(RAIL_CONCURRENCY)

    /** Gate for the "All sources" feed. */
    val feedGate = Semaphore(FEED_CONCURRENCY)

    data class PageKey(val api: String, val listing: BrowseCatalog.Listing, val page: Int)

    private val cache = ConcurrentHashMap<PageKey, ImmutableHeadMainPageResponse>()
    private val inFlight = ConcurrentHashMap<PageKey, CompletableDeferred<Result<ImmutableHeadMainPageResponse>>>()

    fun cached(key: PageKey): ImmutableHeadMainPageResponse? = cache[key]

    /**
     * Page [page] (1-based) of [listing] on [apiName], from the session cache unless [refresh].
     * [gate] limits how many sources load at once (null = not throttled, for the page the user
     * is looking at).
     */
    suspend fun page(
        apiName: String,
        listing: BrowseCatalog.Listing,
        page: Int,
        gate: Semaphore? = null,
        refresh: Boolean = false,
    ): Result<ImmutableHeadMainPageResponse> {
        val key = PageKey(apiName, listing, page)
        if (!refresh) cache[key]?.let { return Result.success(it) }
        val mine = CompletableDeferred<Result<ImmutableHeadMainPageResponse>>()
        val shared = inFlight.putIfAbsent(key, mine)
        if (shared != null) return shared.await()
        val result = try {
            if (gate != null) gate.withPermit { load(apiName, listing, page) } else load(apiName, listing, page)
        } catch (e: CancellationException) {
            inFlight.remove(key, mine)
            mine.cancel()
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        // Empty pages are not cached either: a site that briefly answers with nothing (rate
        // limiting, a hiccup) is asked again next time instead of ending the list for the session.
        result.onSuccess { if (it.list.isNotEmpty()) cache[key] = it }
        inFlight.remove(key, mine)
        mine.complete(result)
        return result
    }

    private suspend fun load(apiName: String, listing: BrowseCatalog.Listing, page: Int): Result<ImmutableHeadMainPageResponse> =
        withContext(Dispatchers.IO) {
            val api = getApiFromName(apiName)
            withTimeoutOrNull(TIMEOUT_MS) {
                api.loadMainPageResult(
                    page = page,
                    mainCategory = api.mainCategories.getOrNull(listing.category)?.second,
                    orderBy = api.orderBys.getOrNull(listing.orderBy)?.second,
                    tag = api.tags.getOrNull(listing.tag)?.second,
                )
            } ?: Result.failure(java.util.concurrent.TimeoutException("$apiName did not answer in ${TIMEOUT_MS / 1000} s"))
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

    /** The listing last chosen on a source's browse page, or null. */
    fun lastListing(context: Context?, apiName: String): BrowseCatalog.Listing? {
        val raw = prefs(context)?.getString(LAST_LISTING_PREFIX + apiName, null) ?: return null
        val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 3) return null
        return BrowseCatalog.Listing(parts[0], parts[1], parts[2])
    }

    fun setLastListing(context: Context?, apiName: String, listing: BrowseCatalog.Listing) {
        prefs(context)?.edit()?.putString(
            LAST_LISTING_PREFIX + apiName, "${listing.category},${listing.orderBy},${listing.tag}"
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
