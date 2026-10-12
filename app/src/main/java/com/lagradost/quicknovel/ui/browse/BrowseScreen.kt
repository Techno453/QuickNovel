package com.lagradost.quicknovel.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import com.lagradost.cloudstream4.compose.rounded
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.centeredGutter
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.common.SearchResponseItem
import com.lagradost.quicknovel.util.AdaptiveLayout

/** Actions from the Browse UI. */
interface BrowseActions {
    fun ensureRail(apiName: String)
    fun retryRail(apiName: String)
    fun openSource(apiName: String)
    fun toggleOther()
    fun openAllSources()
    fun closeAllSources()
    fun selectFeed(language: String?, kind: BrowseCatalog.ListingKind)
    fun feedMore()
    fun refreshFeed()
    fun onPoster(action: SearchResponseAction)
}

/** Widest the Browse home grows; centred beyond that (foldable inner screen, tablets). */
private val BrowseMaxWidth = 1200.dp

/** Poster width in the popular rails. */
private val RailPosterWidth = 112.dp

/** "Kakuyomu (カクヨム)" → "Kakuyomu" for badges and compact labels. */
fun shortSourceName(name: String): String = name.substringBefore(" (").trim().ifEmpty { name }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(state: BrowseState, actions: BrowseActions) {
    if (state.allSourcesOpen) {
        BackHandler { actions.closeAllSources() }
        AllSourcesScreen(state.feed, actions)
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_browse)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // No bottom padding: the app's navigation bar is outside this screen.
                .padding(
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                    top = innerPadding.calculateTopPadding(),
                )
        ) {
            // Full-width cards on phones and the cover screen, two per row from 840 dp.
            val columns = if (maxWidth >= AdaptiveLayout.MAX_CONTENT_WIDTH_DP.dp) 2 else 1
            val gutter = centeredGutter(8.dp, BrowseMaxWidth)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all_sources", span = { GridItemSpan(maxLineSpan) }) {
                    AllSourcesCard(onClick = actions::openAllSources)
                }
                for (group in state.groups) {
                    val header = if (group.isOther) "other" else "header_${group.lang}"
                    item(key = header, span = { GridItemSpan(maxLineSpan) }) {
                        GroupHeader(group, expanded = !group.isOther || state.otherExpanded, onToggle = actions::toggleOther)
                    }
                    if (!group.isOther || state.otherExpanded) {
                        items(group.sources, key = { "source_" + it.name }) { source ->
                            SourceCard(source, state.rails[source.name], actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllSourcesCard(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .rounded()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_outline_explore_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.browse_all_sources),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.browse_all_sources_summary),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 13.sp,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_baseline_arrow_forward_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun GroupHeader(group: BrowseCatalog.Group, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (group.isOther) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 2.dp)
    ) {
        Text(
            text = if (group.isOther) stringResource(R.string.browse_other_sources) + " (${group.sources.size})"
            else BrowseCatalog.languageName(group.lang),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (group.isOther) {
            Icon(
                painter = painterResource(R.drawable.arrow_drop_down_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            )
        }
    }
}

@Composable
fun LanguageChip(lang: String) {
    Text(
        text = BrowseCatalog.languageName(lang),
        fontSize = 11.sp,
        lineHeight = 12.sp,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .rounded()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun SourceCard(source: BrowseCatalog.Source, rail: RailState?, actions: BrowseActions) {
    // Lazy: a card is only composed when it scrolls into view, so this is where its rail loads.
    LaunchedEffect(source.name) { actions.ensureRail(source.name) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rounded()
            // surfaceContainer is pure black in the AMOLED theme; a tint of surfaceVariant keeps
            // the card visible on every theme.
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.openSource(source.name) }
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Text(
                source.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            LanguageChip(source.lang)
            Spacer(Modifier.weight(0.01f))
            TextButton(onClick = { actions.openSource(source.name) }) {
                Text(stringResource(R.string.browse_see_all))
            }
        }
        Text(
            text = source.mainCategories.firstOrNull() ?: source.orderBys.firstOrNull() ?: stringResource(R.string.browse_popular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        val railHeight = RailPosterWidth / 0.68f + 56.dp
        when {
            rail == null || (rail.loading && rail.items.isEmpty()) -> RailPlaceholder(railHeight)
            rail.error != null && rail.items.isEmpty() -> RailMessage(
                railHeight, stringResource(R.string.browse_unavailable), rail.error,
                onRetry = { actions.retryRail(source.name) },
            )
            // An empty listing does not need a poster-high hole; Retry in case it was transient.
            rail.items.isEmpty() -> RailMessage(
                110.dp, stringResource(R.string.browse_empty), null,
                onRetry = { actions.retryRail(source.name) },
            )
            else -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(railHeight),
            ) {
                items(rail.items) { response ->
                    SearchResponseItem(
                        response = response,
                        action = actions::onPoster,
                        modifier = Modifier.width(RailPosterWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun RailPlaceholder(height: androidx.compose.ui.unit.Dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 10.dp)
    ) {
        repeat(6) {
            Box(
                modifier = Modifier
                    .width(RailPosterWidth)
                    .aspectRatio(0.68f)
                    .rounded()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

@Composable
private fun RailMessage(height: androidx.compose.ui.unit.Dp, title: String, detail: String?, onRetry: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 16.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp)
        if (detail != null) {
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.browse_retry)) }
        }
    }
}

/** A poster in a mixed-source list: the usual poster plus the source badge and "+N" marker. */
@Composable
fun FeedPoster(entry: FeedEntry, onPoster: (SearchResponseAction) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        SearchResponseItem(response = entry.response, action = onPoster, modifier = Modifier.fillMaxWidth())
        Text(
            text = shortSourceName(entry.source),
            fontSize = 10.sp,
            lineHeight = 11.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .rounded()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        if (entry.alsoOn.isNotEmpty()) {
            Text(
                text = stringResource(R.string.browse_also_on, entry.alsoOn.size),
                fontSize = 10.sp,
                lineHeight = 11.sp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .rounded()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun LoadingFooter(text: String? = null) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        if (text != null) {
            Spacer(Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}
