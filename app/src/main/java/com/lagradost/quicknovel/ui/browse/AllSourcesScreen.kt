package com.lagradost.quicknovel.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.AdaptiveAtLeast
import com.lagradost.quicknovel.compose.PosterMinCellWidth
import com.lagradost.quicknovel.ui.browse.BrowseCatalog.ListingKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "All sources": one feed interleaving every zh/ja/ko source's listing by rank, with language
 * and listing filters (docs/browse.md). Mixed-language cards carry a source badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllSourcesScreen(feed: FeedState, actions: BrowseActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse_all_sources)) },
                navigationIcon = {
                    IconButton(onClick = actions::closeAllSources) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.browse_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = innerPadding.calculateTopPadding(),
                )
        ) {
            val languages = listOf<String?>(null) + BrowseCatalog.CJK_ORDER
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(languages.size) { i ->
                    val lang = languages[i]
                    FilterChip(
                        selected = feed.language == lang,
                        onClick = { actions.selectFeed(lang, feed.kind) },
                        label = {
                            Text(if (lang == null) stringResource(R.string.browse_language_all) else BrowseCatalog.languageName(lang))
                        },
                    )
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(ListingKind.entries.size) { i ->
                    val kind = ListingKind.entries[i]
                    FilterChip(
                        selected = feed.kind == kind,
                        onClick = { actions.selectFeed(feed.language, kind) },
                        label = {
                            Text(
                                stringResource(
                                    when (kind) {
                                        ListingKind.POPULAR -> R.string.browse_listing_popular
                                        ListingKind.NEW -> R.string.browse_listing_new
                                        ListingKind.COMPLETED -> R.string.browse_listing_completed
                                    }
                                )
                            )
                        },
                    )
                }
            }
            FeedStatus(feed)

            val gridState = rememberLazyGridState()
            val nearEnd by remember {
                derivedStateOf {
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= gridState.layoutInfo.totalItemsCount - 9
                }
            }
            LaunchedEffect(nearEnd, feed.entries.size) {
                if (nearEnd && !feed.complete) actions.feedMore()
            }
            // Different filter: start at the top.
            LaunchedEffect(feed.language, feed.kind) { gridState.scrollToItem(0) }

            var refreshing by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    actions.refreshFeed()
                    scope.launch {
                        delay(400)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = AdaptiveAtLeast(PosterMinCellWidth, minCount = 3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(feed.entries) { _, entry ->
                        FeedPoster(entry, actions::onPoster)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            feed.loading -> LoadingFooter(
                                if (feed.entries.isEmpty() && feed.sourcesTotal > 0)
                                    "${feed.sourcesAnswered} / ${feed.sourcesTotal}" else null
                            )
                            feed.complete && feed.entries.isEmpty() -> Text(
                                stringResource(R.string.browse_empty),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                            )
                            feed.complete -> Text(
                                stringResource(R.string.browse_end_of_list),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedStatus(feed: FeedState) {
    if (feed.unavailable == 0) return
    Text(
        text = if (feed.unavailable == 1) stringResource(R.string.browse_source_unavailable)
        else stringResource(R.string.browse_sources_unavailable, feed.unavailable),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )
}
