package com.lagradost.quicknovel.ui.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.ui.browse.BrowseCatalog.ListingKind
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The popular rail of one source card on the Browse home. */
@Immutable
data class RailState(
    val loading: Boolean = true,
    val items: ImmutableList<ImmutableSearchResponse> = persistentListOf(),
    val error: String? = null,
)

@Immutable
data class FeedEntry(
    val response: ImmutableSearchResponse,
    val source: String,
    val alsoOn: ImmutableList<String>,
)

/** The "All sources" feed for one language + listing filter. */
@Immutable
data class FeedState(
    /** zh / ja / ko, or null for all CJK languages. */
    val language: String? = "zh",
    val kind: ListingKind = ListingKind.POPULAR,
    val entries: ImmutableList<FeedEntry> = persistentListOf(),
    val loading: Boolean = true,
    val sourcesTotal: Int = 0,
    val sourcesAnswered: Int = 0,
    val unavailable: Int = 0,
    val complete: Boolean = false,
)

@Immutable
data class BrowseState(
    val groups: ImmutableList<BrowseCatalog.Group> = persistentListOf(),
    val rails: ImmutableMap<String, RailState> = persistentMapOf(),
    val otherExpanded: Boolean = false,
    val allSourcesOpen: Boolean = false,
    val feed: FeedState = FeedState(),
)

/**
 * Browse home (source cards with lazily loaded popular rails) and the "All sources" feed.
 * See docs/browse.md for the loading, caching and politeness rules.
 */
class BrowseViewModel : ViewModel() {
    companion object {
        /** Posters per popular rail. */
        const val RAIL_SIZE = 12

        /** Feed entries added per "page" of the All sources feed. */
        const val FEED_PAGE = 30

        /** How long the feed waits for every source's first page before showing what it has. */
        const val FEED_GRACE_MS = 6_000L
    }

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val readingLanguages = BrowsePrefs.readingLanguages(BaseApplication.context)

    init {
        val sources = BrowseRepository.sources()
        val groups = BrowseCatalog.group(sources, readingLanguages)
        val firstCjk = readingLanguages.map { it.lowercase().substringBefore('-') }
            .firstOrNull { it in BrowseCatalog.CJK_ORDER } ?: "zh"
        _state.value = BrowseState(groups = groups.toImmutableList(), feed = FeedState(language = firstCjk))
    }

    // ---- Popular rails ----

    /** Loads the popular rail of [apiName] once per session (called when its card is composed). */
    fun ensureRail(apiName: String, refresh: Boolean = false) {
        val current = _state.value.rails[apiName]
        if (!refresh && current != null && (current.loading || current.error == null)) return
        setRail(apiName, RailState(loading = true, items = current?.items ?: persistentListOf()))
        viewModelScope.launch {
            BrowseRepository.page(apiName, BrowseCatalog.Listing(), 1, BrowseRepository.railGate, refresh)
                .onSuccess { page ->
                    setRail(apiName, RailState(loading = false, items = page.list.take(RAIL_SIZE).toImmutableList()))
                }
                .onFailure { error ->
                    setRail(apiName, RailState(loading = false, error = error.message ?: error.javaClass.simpleName))
                }
        }
    }

    private fun setRail(apiName: String, rail: RailState) {
        _state.update { it.copy(rails = (it.rails + (apiName to rail)).toPersistentMap()) }
    }

    fun toggleOther() = _state.update { it.copy(otherExpanded = !it.otherExpanded) }

    // ---- All sources ----

    private inner class FeedSession(val language: String?, val kind: ListingKind, val refresh: Boolean = false) {
        val sources: List<Pair<BrowseCatalog.Source, BrowseCatalog.Listing>> =
            _state.value.groups.filter { !it.isOther }
                .filter { group -> group.lang in BrowseCatalog.CJK_ORDER && (language == null || group.lang == language) }
                .flatMap { it.sources }
                .mapNotNull { source -> BrowseCatalog.resolve(source, kind)?.let { source to it } }
        val listingOf = sources.associate { it.first.name to it.second }
        val merger = CombinedBrowse<ImmutableSearchResponse>(
            sources.map { it.first.name },
            title = { it.name },
            author = { it.author },
            chapters = { CombinedBrowse.chapterCount(it.latestChapterName) },
            fold = if (language == null || language == "zh") BrowseRepository.chineseFold else { s -> s },
        )
        var target = FEED_PAGE
        var started = 0L
        var graceOver = false

        fun start() {
            started = System.currentTimeMillis()
            sources.forEach { (source, _) -> fetch(source.name, 1) }
            viewModelScope.launch {
                delay(FEED_GRACE_MS)
                graceOver = true
                update()
            }
            update()
        }

        fun fetch(name: String, page: Int) {
            if (page > 1) merger.onFetchStarted(name)
            val listing = listingOf[name] ?: return
            viewModelScope.launch {
                BrowseRepository.page(name, listing, page, BrowseRepository.feedGate, refresh)
                    .onSuccess { merger.onPage(name, page, it.list) }
                    .onFailure { merger.onFailure(name) }
                update()
            }
        }

        fun more() {
            if (merger.feed.size + FEED_PAGE / 2 < target) return
            target = merger.feed.size + FEED_PAGE
            update()
        }

        fun update() {
            merger.fill(target, allowPartial = graceOver)
            if (merger.feed.size < target) merger.plan().forEach { (name, page) -> fetch(name, page) }
            if (session !== this) return
            publish()
        }

        fun publish() {
            val answered = sources.count { merger.statusOf(it.first.name) != CombinedBrowse.Status.LOADING }
            _state.update {
                it.copy(
                    feed = FeedState(
                        language = language,
                        kind = kind,
                        entries = merger.feed.map { entry ->
                            FeedEntry(entry.item, entry.source, entry.alsoOn.toImmutableList())
                        }.toImmutableList(),
                        loading = !merger.isComplete && merger.feed.size < target,
                        sourcesTotal = sources.size,
                        sourcesAnswered = answered,
                        unavailable = merger.unavailable,
                        complete = merger.isComplete,
                    )
                )
            }
        }
    }

    private val sessions = HashMap<Pair<String?, ListingKind>, FeedSession>()
    private var session: FeedSession? = null

    fun openAllSources() {
        _state.update { it.copy(allSourcesOpen = true) }
        selectFeed(_state.value.feed.language, _state.value.feed.kind)
    }

    fun closeAllSources() = _state.update { it.copy(allSourcesOpen = false) }

    fun selectFeed(language: String?, kind: ListingKind, refresh: Boolean = false) {
        val key = language to kind
        val existing = sessions[key]
        val next = existing ?: FeedSession(language, kind, refresh).also { sessions[key] = it }
        session = next
        if (existing == null) next.start() else next.publish()
    }

    fun feedMore() = session?.more()

    /** Pull to refresh on the feed: forget this filter's session and load it again. */
    fun refreshFeed() {
        val current = session ?: return
        sessions.remove(current.language to current.kind)
        selectFeed(current.language, current.kind, refresh = true)
    }
}
