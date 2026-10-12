package com.lagradost.quicknovel.ui.mainpage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.quicknovel.ui.browse.LoadingFooter
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream4.compose.Colors
import com.lagradost.cloudstream4.compose.SingleSelectDialog
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.BaseSearchBar
import com.lagradost.quicknovel.ui.common.SearchList
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.search.SearchRow
import com.mihon.common.preference.AndroidPreferenceStore
import com.mihon.presentation.settings.collectAsState
import kotlinx.coroutines.launch

@Composable
fun MainScreenDialog(
    dialog: MainPageDialog,
    action: (MainPageAction) -> Unit
) {
    val title = when (dialog.type) {
        DialogType.Tags -> stringResource(R.string.filter_dialog_genre)
        DialogType.Category -> stringResource(R.string.filter_dialog_general)
        DialogType.OrderBy -> stringResource(R.string.filter_dialog_order_by)
    }

    SingleSelectDialog(
        entries = dialog.options,
        dismiss = {
            action(MainPageAction.Dismiss)
        },
        title = title,
        selectedIndex = dialog.selected,
        confirm = { selected ->
            action(MainPageAction.SelectDialog(dialog.type, selected))
        })


    /*BaseDialog(
        dismiss = {
            action(MainPageAction.Dismiss)
        },
        title = { Text(text = title) },
        items = dialog.options,
        selected = dialog.selected,
        onSelect = { selected ->
            action(MainPageAction.SelectDialog(dialog.type, selected))
        })*/
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPageScreen(state: MainPageState, action: (MainPageAction) -> Unit) {
    if (state.openQuery) {
        BackHandler {
            action(MainPageAction.Back)
        }
    }

    if (state.dialog != null) {
        MainScreenDialog(state.dialog, action)
    }

    val listState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(snapAnimationSpec = null)
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 9
        }
    }

    val searchAction = remember<(SearchResponseAction) -> Unit>(action) {
        { action ->
            action(MainPageAction.ResultAction(action))
        }
    }

    val context = LocalContext.current
    val store = AndroidPreferenceStore(context)
    val searchIsRow = store.getBoolean(stringResource(R.string.search_list_view_key), false)
    val searchIsRowState by searchIsRow.collectAsState()

    // A different listing (or a refresh) starts at the top. Keyed on the list becoming non-empty
    // as well: while it is empty only the footer is shown, and the grid would otherwise keep the
    // footer in view when the first page arrives, i.e. open the new listing scrolled down.
    val listEmpty = state.filter.items.isEmpty()
    LaunchedEffect(state.filter.query.category, state.filter.query.orderBy, state.filter.query.tag, listEmpty) {
        listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            MainPageSearchBar(
                state = state,
                loading = if (state.openQuery) state.query.loading else state.filter.loading,
                action = action,
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            // This fixes the double padding from the bottom nav bar
            .padding(
                start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                top = innerPadding.calculateTopPadding()
            )
        if (state.openQuery) {
            SearchList(
                isRow = searchIsRowState,
                items = state.query.items,
                modifier = contentModifier,
                searchAction = searchAction,
            )
            return@Scaffold
        }
        val filter = state.filter
        var refreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                action(MainPageAction.Refresh)
                scope.launch {
                    delay(400)
                    refreshing = false
                }
            },
            modifier = contentModifier,
        ) {
            if (filter.items.isEmpty() && !filter.loading) {
                EmptyOrError(filter.error) { action(MainPageAction.Refresh) }
            } else {
                SearchList(
                    lazyGridState = listState,
                    isRow = searchIsRowState,
                    items = filter.items,
                    modifier = Modifier.fillMaxSize(),
                    searchAction = searchAction,
                    footer = {
                        when {
                            filter.loading -> LoadingFooter()
                            filter.error != null -> EmptyOrError(filter.error, compact = true) {
                                action(MainPageAction.Refresh)
                            }
                            filter.endReached -> Text(
                                stringResource(R.string.browse_end_of_list),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                            else -> Spacer(Modifier.height(1.dp))
                        }
                    },
                )
            }
        }

        LaunchedEffect(shouldLoadMore.value, filter.items.size) {
            if (shouldLoadMore.value && filter.error == null && !filter.loading && !filter.endReached) {
                action(MainPageAction.Expand)
            }
        }
    }
}

/** Empty listing or a failed load, with a retry button (and pull to refresh around it). */
@Composable
private fun EmptyOrError(error: Throwable?, compact: Boolean = false, onRetry: () -> Unit) {
    val content: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = if (compact) 12.dp else 64.dp)
        ) {
            Text(
                stringResource(if (error != null) R.string.browse_unavailable else R.string.browse_empty),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            if (error != null) {
                Text(
                    error.message ?: error.javaClass.simpleName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.browse_retry)) }
        }
    }
    if (compact) {
        content()
    } else {
        // Scrollable so pull to refresh works on the empty state too.
        LazyColumn(modifier = Modifier.fillMaxSize()) { item { content() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResponseDialog(
    dialog: SearchRow,
    action: (SearchResponseAction) -> Unit,
    dismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = dismiss,
        modifier = Modifier.fillMaxSize(),
        dragHandle = { },
        shape = RectangleShape
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clickable(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            dismiss()
                        }
                    }
                )
                .padding(horizontal = 10.dp)
        ) {
            Text(
                text = dialog.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
            )
            Icon(
                painter = painterResource(R.drawable.arrow_drop_down_24px),
                tint = MaterialTheme.colorScheme.onBackground,
                contentDescription = null,
            )
        }

        if (dialog.error != null) {
            Text(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                lineHeight = 15.sp,
                text = dialog.error.toString(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (dialog.items.isEmpty()) {
            Text(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                lineHeight = 15.sp,
                text = stringResource(R.string.no_data),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SearchList(
                isRow = false,
                items = dialog.items,
                modifier = Modifier
                    .fillMaxSize(),
                searchAction = action,
            )
        }
    }
}


@Composable
fun MainPageSearchBar(
    state: MainPageState,
    loading: Boolean,
    action: (MainPageAction) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val openQuery = state.openQuery
    val context = LocalContext.current
    val store = AndroidPreferenceStore(context)
    val searchIsRow = store.getBoolean(stringResource(R.string.search_list_view_key), false)
    val searchIsRowState by searchIsRow.collectAsState()

    BaseSearchBar(
        placeholder = "${stringResource(R.string.search)} ${state.apiName}…",
        onQueryChange = { _ ->
            // action(MainPageAction.Search(query))
        },
        onSearch = { query ->
            action(MainPageAction.Search(query))
        },
        leadingIcon = {
            if (openQuery) {
                IconButton(onClick = {
                    action(MainPageAction.Back)
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = stringResource(R.string.back_to_search),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.search_icon),
                    contentDescription = stringResource(R.string.search),
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        scrollBehavior = scrollBehavior,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(34.dp)
                            .padding(5.dp),
                        color = MaterialTheme.colorScheme.onBackground, strokeWidth = 3.0.dp
                    )
                }
                IconButton(onClick = {
                    searchIsRow.set(!searchIsRowState)
                }) {
                    Icon(
                        painter = painterResource(if (searchIsRowState) R.drawable.ic_baseline_grid_view_24 else R.drawable.ic_baseline_list_24),
                        contentDescription = stringResource(if (searchIsRowState) R.string.grid_view else R.string.list_view),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        },
    ) {
        if (!openQuery) {
            // Listing / order / genre as chip rows (docs/browse.md); the first entry of each is
            // the provider's default (most popular first).
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                ChipRow(stringResource(R.string.browse_listing), state.categories, state.filter.query.category) {
                    action(MainPageAction.SelectDialog(DialogType.Category, it))
                }
                ChipRow(stringResource(R.string.browse_order), state.orderBys, state.filter.query.orderBy) {
                    action(MainPageAction.SelectDialog(DialogType.OrderBy, it))
                }
                ChipRow(stringResource(R.string.browse_genre), state.tags, state.filter.query.tag) {
                    action(MainPageAction.SelectDialog(DialogType.Tags, it))
                }
            }
        }
    }
}

/** One horizontally scrolling row of filter chips; hidden when there is nothing to choose. */
@Composable
private fun ChipRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (options.size <= 1) return
    val rowState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (selected > 0) rowState.scrollToItem(selected)
    }
    LazyRow(
        state = rowState,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(options) { index, option ->
            FilterChip(
                selected = index == selected,
                onClick = { if (index != selected) onSelect(index) },
                label = { Text(option, maxLines = 1) },
            )
        }
    }
}

@Composable
fun RowScope.SelectButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        Modifier
            .weight(1.0f)
            .padding(horizontal = 5.dp),
        colors = Colors.blackButton,
    ) {
        Text(text)
        Spacer(Modifier.width(10.dp))
        Icon(
            painter = painterResource(R.drawable.arrow_drop_down_24px), contentDescription = text
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsScreenPreview() {
    CloudStreamPreviewTheme {
        MainPageScreen(
            state = MainPageState(apiName = "hello world"),
            action = {})
    }
}
