package com.lagradost.quicknovel.ui.mainpage

import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lagradost.cloudstream4.state.ActionHandler
import com.lagradost.cloudstream4.state.DefaultEffectContainer
import com.lagradost.cloudstream4.state.DefaultStateContainer
import com.lagradost.cloudstream4.state.EffectContainer
import com.lagradost.cloudstream4.state.SingleActiveQuery
import com.lagradost.cloudstream4.state.StateContainer
import com.lagradost.quicknovel.APIRepository
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.ui.browse.BrowsePrefs
import com.lagradost.quicknovel.ui.browse.BrowseRepository
import com.lagradost.quicknovel.ui.browse.TagFilter
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.common.SearchResponseOperation
import com.lagradost.quicknovel.ui.common.updateItem
import com.lagradost.quicknovel.util.Apis.Companion.getApiFromName
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

@Immutable
data class MainPageState(
    val openQuery: Boolean = false,
    val filter: FilterState = FilterState(),
    val filterVisual: FilterQueryVisual = FilterQueryVisual(),
    val query: QueryState = QueryState(),
    val dialog: MainPageDialog? = null,
    val apiName: String,
    /** Labels of the provider's listings / orders / genres (chip rows). */
    val categories: PersistentList<String> = persistentListOf(),
    val orderBys: PersistentList<String> = persistentListOf(),
    val tags: PersistentList<String> = persistentListOf(),
    /** The provider's first tag means "no genre filter" (see [TagFilter]). */
    val tagsFirstIsNoFilter: Boolean = false,
)

@Immutable
data class QueryState(
    val loading: Boolean = true,
    val query: String = "",
    val items: PersistentList<ImmutableSearchResponse> = persistentListOf(),
    val error: Throwable? = null,
)

@Immutable
data class FilterState(
    val loading: Boolean = true,
    val items: PersistentList<ImmutableSearchResponse> = persistentListOf(),
    val error: Throwable? = null,
    val url: String = "",
    val query: FilterQuery = FilterQuery(),
    /** The last page added nothing new: stop loading more. */
    val endReached: Boolean = false,
)

@Immutable
data class FilterQueryVisual(
    val category: String? = null,
    val orderBy: String? = null,
    val tag: String? = null,
)

@Immutable
data class FilterQuery(
    val page: Int = 0,
    val category: Int = 0,
    val orderBy: Int = 0,
    /** Selected genre indexes, normalized by [TagFilter.normalize] (empty = no genre filter). */
    val tags: List<Int> = emptyList(),
)

enum class DialogType {
    Tags, Category, OrderBy
}

@Immutable
data class MainPageDialog(
    val selected: Int,
    val options: PersistentList<String>,
    val type: DialogType,
)

@Immutable
sealed class MainPageAction {
    data class ResultAction(val action: SearchResponseAction) : MainPageAction()

    data class Search(val data: String) : MainPageAction()
    object Expand : MainPageAction()
    data class OpenInBrowser(val url: String) : MainPageAction()
    object Back : MainPageAction()
    object Dismiss : MainPageAction()
    data class OpenDialog(val type: DialogType) : MainPageAction()
    data class SelectDialog(val type: DialogType, val selected: Int) : MainPageAction()

    /** Genres chosen in the genre sheet (indexes into the provider's tags). */
    data class SelectTags(val tags: List<Int>) : MainPageAction()

    /** Pull to refresh, or retry after an error: reload from page 1 bypassing the session cache. */
    object Refresh : MainPageAction()
}

@Immutable
sealed class MainPageEffect {
    data class ErrorLoading(val error: Throwable) : MainPageEffect()
}

/**
 * One source's browse page (docs/browse.md): listing / order / genre chips from the provider's
 * mainCategories / orderBys / tags, infinite scroll through [BrowseRepository] (session cache),
 * pull to refresh, and the last chosen listing remembered per source ([BrowsePrefs]).
 */
class MainPageViewModel2(
    val api: APIRepository,
    initQuery: FilterQuery,
) : ViewModel(), StateContainer<MainPageState> by DefaultStateContainer(
    MainPageState(
        filter = FilterState(query = initQuery), apiName = api.name,
        categories = api.mainCategories.map { it.first }.toPersistentList(),
        orderBys = api.orderBys.map { it.first }.toPersistentList(),
        tags = api.tags.map { it.first }.toPersistentList(),
        tagsFirstIsNoFilter = api.tags.firstOrNull().let { TagFilter.firstIsNoFilter(it?.first, it?.second) },
    )
), EffectContainer<MainPageEffect> by DefaultEffectContainer(), ActionHandler<MainPageAction> {
    companion object {
        /**
         * Explicit mainCategory / orderBy / tag / tags in the bundle (e.g. a genre chip on the
         * result page) win; without any, the source's last chosen listing is restored.
         */
        fun provideFactory(bundle: Bundle) = viewModelFactory {
            initializer {
                val apiName = bundle.getString("apiName")!!
                val api = getApiFromName(apiName)
                val explicit = bundle.containsKey("mainCategory") || bundle.containsKey("orderBy") ||
                        bundle.containsKey("tag") || bundle.containsKey("tags")
                val (category, orderBy, tags) = if (explicit) {
                    Triple(
                        bundle.getInt("mainCategory", 0), bundle.getInt("orderBy", 0),
                        bundle.getIntArray("tags")?.toList() ?: listOf(bundle.getInt("tag", 0)),
                    )
                } else {
                    BrowsePrefs.lastListing(BaseApplication.context, api.name) ?: Triple(0, 0, emptyList())
                }
                fun clamp(index: Int, size: Int) = if (index in 0 until size) index else 0
                val firstIsNoFilter = api.tags.firstOrNull().let { TagFilter.firstIsNoFilter(it?.first, it?.second) }
                MainPageViewModel2(
                    api,
                    FilterQuery(
                        page = 0,
                        category = clamp(category, api.mainCategories.size),
                        orderBy = clamp(orderBy, api.orderBys.size),
                        tags = TagFilter.normalize(tags, api.tags.size, firstIsNoFilter),
                    )
                )
            }
        }
    }

    private val expandMutex = Mutex()

    init {
        viewModelScope.launch {
            expand()
        }
    }

    suspend fun expand(refresh: Boolean = false) = withContext(Dispatchers.IO) {
        expandMutex.withLock {
            val from = state.value.filter.query
            val fromList = state.value.filter.items
            if (state.value.filter.endReached && !refresh) return@withLock

            val mainCategory = api.mainCategories.getOrNull(from.category)
            val tags = TagFilter.loaded(from.tags).mapNotNull { api.tags.getOrNull(it)?.first }
            val orderBy = api.orderBys.getOrNull(from.orderBy)
            updateState {
                copy(
                    filterVisual = FilterQueryVisual(
                        category = mainCategory?.first, tag = tags.joinToString().ifEmpty { null }, orderBy = orderBy?.first
                    ), filter = filter.copy(loading = true, error = null)
                )
            }

            val nextPage = from.page + 1
            // Several genres: page n of each genre's listing, merged by rank (TagFilter).
            BrowseRepository.page(
                api.name, from.category, from.orderBy, from.tags, nextPage, gate = null, refresh = refresh
            )
                .onFailure { error ->
                    if (error is CancellationException) {
                        updateState { copy(filter = filter.copy(loading = false)) }
                        return@withLock
                    }
                    updateState {
                        if (this.filter.query != from) copy(filter = filter.copy(loading = false))
                        else copy(filter = filter.copy(loading = false, error = error))
                    }
                    if (fromList.isNotEmpty()) postEffect { MainPageEffect.ErrorLoading(error = error) }
                }.onSuccess { response ->
                    // Sites repeat their last page or return nothing past the end.
                    val known = fromList.map { it.url }.toHashSet()
                    val fresh = response.list.filter { known.add(it.url) }
                    updateState {
                        // Outdated query, drop it
                        if (this.filter.query != from) {
                            return@updateState copy(filter = filter.copy(loading = false))
                        }
                        copy(
                            filter = filter.copy(
                                loading = false,
                                error = null,
                                items = fromList.addAll(fresh),
                                query = from.copy(page = nextPage),
                                url = response.url,
                                endReached = fresh.isEmpty(),
                            )
                        )
                    }
                }
            updateState { copy(filter = filter.copy(loading = false)) }
        }
    }

    val searchQuery = SingleActiveQuery(Dispatchers.IO)
    suspend fun search(queryText: String) = searchQuery.launch {
        updateState {
            copy(openQuery = true, query = query.copy(loading = true, items = persistentListOf()))
        }
        api.searchResult(queryText).onFailure { error ->
            if (error is CancellationException) {
                return@launch
            }
            updateState {
                copy(query = query.copy(loading = false, error = error))
            }
            postEffect {
                MainPageEffect.ErrorLoading(error)
            }
        }.onSuccess { response ->
            updateState {
                copy(
                    query = query.copy(
                        items = response.toPersistentList(),
                        loading = false,
                        error = null,
                        query = queryText,
                    )
                )
            }
        }
    }

    private fun select(type: DialogType, selected: Int) {
        val query = state.value.filter.query
        applyQuery(
            when (type) {
                DialogType.Tags -> query.copy(tags = normalizeTags(listOf(selected)))
                DialogType.Category -> query.copy(category = selected)
                DialogType.OrderBy -> query.copy(orderBy = selected)
            }
        )
    }

    private fun normalizeTags(tags: List<Int>) =
        TagFilter.normalize(tags, api.tags.size, state.value.tagsFirstIsNoFilter)

    /** Starts [query] from page 1 and remembers it as the source's last listing. */
    private fun applyQuery(query: FilterQuery) {
        val next = query.copy(page = 0)
        updateState {
            copy(
                filter = filter.copy(
                    query = next, items = persistentListOf(), error = null, url = "", endReached = false,
                ), dialog = null
            )
        }
        BrowsePrefs.setLastListing(BaseApplication.context, api.name, next.category, next.orderBy, next.tags)
        viewModelScope.launch {
            expand()
        }
    }

    override fun onAction(action: MainPageAction) {
        when (action) {
            is MainPageAction.ResultAction -> {
                resultAction(action.action)
            }

            is MainPageAction.Search -> {
                viewModelScope.launch {
                    search(action.data)
                }
            }

            is MainPageAction.Expand -> {
                val filter = state.value.filter
                if (filter.loading || filter.endReached || filter.error != null) return
                viewModelScope.launch {
                    expand()
                }
            }

            // Browsing stays in the app (docs/browse.md): the source site is never opened.
            is MainPageAction.OpenInBrowser -> Unit

            is MainPageAction.Back -> {
                updateState { copy(openQuery = false) }
            }

            is MainPageAction.OpenDialog -> {
                val names = when (action.type) {
                    DialogType.Category -> api.mainCategories
                    DialogType.Tags -> api.tags
                    DialogType.OrderBy -> api.orderBys
                }.map { it.first }
                updateState {
                    copy(
                        dialog = MainPageDialog(
                            selected = when (action.type) {
                                DialogType.Category -> filter.query.category
                                DialogType.Tags -> filter.query.tags.firstOrNull() ?: 0
                                DialogType.OrderBy -> filter.query.orderBy
                            }, options = names.toPersistentList(), type = action.type
                        )
                    )
                }
            }

            is MainPageAction.SelectDialog -> select(action.type, action.selected)

            is MainPageAction.SelectTags -> {
                val tags = normalizeTags(action.tags)
                val query = state.value.filter.query
                if (tags != query.tags) applyQuery(query.copy(tags = tags))
            }

            MainPageAction.Refresh -> {
                updateState {
                    copy(
                        filter = filter.copy(
                            query = filter.query.copy(page = 0),
                            items = persistentListOf(), error = null, endReached = false,
                        )
                    )
                }
                viewModelScope.launch {
                    expand(refresh = true)
                }
            }

            MainPageAction.Dismiss -> {
                updateState {
                    copy(dialog = null)
                }
            }
        }
    }

    private fun resultAction(action: SearchResponseAction) {
        when (action.operation) {
            SearchResponseOperation.Stream -> {
                viewModelScope.launch {
                    updateState {
                        copy(query = query.copy(items = query.items.updateItem(action.response) {
                            copy(generating = true)
                        }), filter = filter.copy(items = filter.items.updateItem(action.response) {
                            copy(generating = true)
                        }))
                    }

                    BookDownloader2.stream(action.response)

                    updateState {
                        copy(query = query.copy(items = query.items.updateItem(action.response) {
                            copy(generating = false)
                        }), filter = filter.copy(items = filter.items.updateItem(action.response) {
                            copy(generating = false)
                        }))
                    }
                }
            }

            else -> {
                action.doAction()
            }
        }
    }
}
