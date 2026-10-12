package com.lagradost.quicknovel.ui.result

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream4.compose.Colors
import com.lagradost.cloudstream4.compose.RoundedShape
import com.lagradost.cloudstream4.compose.SingleSelectDialog
import com.lagradost.cloudstream4.compose.circle
import com.lagradost.cloudstream4.compose.rounded
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.MaxContentWidth
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.ui.common.ImmutableChapterData
import com.lagradost.quicknovel.ui.common.ImmutableDownloadState
import com.lagradost.quicknovel.ui.common.ImmutableReview
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import com.lagradost.quicknovel.ui.common.LoadingButton
import com.lagradost.quicknovel.ui.common.LoadingLine
import com.lagradost.quicknovel.ui.common.LoadingPoster
import com.lagradost.quicknovel.ui.common.LoadingWeight
import com.lagradost.quicknovel.ui.common.LoadingWidth
import com.lagradost.quicknovel.ui.common.SearchList
import com.lagradost.quicknovel.ui.common.loading
import com.lagradost.quicknovel.ui.common.loadingLineMargin
import com.lagradost.quicknovel.ui.download.CHAPTER_SORT
import com.lagradost.quicknovel.ui.download.LAST_ACCES_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_CHAPTER_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_LAST_ACCES_SORT
import com.lagradost.quicknovel.util.AppUtils.openInBrowser
import com.lagradost.quicknovel.util.SettingsHelper.getRating
import com.lagradost.quicknovel.util.SettingsHelper.getRatingReview
import com.lagradost.quicknovel.util.UIHelper.humanReadableByteCountSI
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull

/**
 * Scroll positions of the page, created above the layout switch so that fold / unfold (one pane
 * ↔ two panes, same composition) and tab switches keep them. All of them are saveable.
 */
@Stable
class ResultListStates(
    val details: LazyListState,
    val chapters: LazyListState,
    val reviews: LazyListState,
    val related: LazyGridState,
)

@Composable
fun rememberResultListStates(): ResultListStates {
    val details = rememberLazyListState()
    val chapters = rememberLazyListState()
    val reviews = rememberLazyListState()
    val related = rememberLazyGridState()
    return remember(details, chapters, reviews, related) { ResultListStates(details, chapters, reviews, related) }
}

/** Html → text, re-parsed when the text changes (translated ↔ original). */
@Composable
private fun rememberHtml(text: String): AnnotatedString = remember(text) {
    val parsed = AnnotatedString.fromHtml(text.replace("</p>", "<br/><br/>"))
    val start = parsed.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val end = parsed.indexOfLast { !it.isWhitespace() } + 1
    if (end <= start) AnnotatedString("") else parsed.subSequence(start, end)
}

/** Caps an item at [MaxContentWidth], centred (single column on wide-ish windows). */
private fun Modifier.capped(max: Dp = MaxContentWidth): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(state: ResultState, action: (ResultPageAction) -> Unit) {
    val lists = rememberResultListStates()
    var posterShown by rememberSaveable { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val response = state.response
    val display = response?.let { metadataDisplayOf(state, it) }

    val showTitleInBar by remember(state) {
        derivedStateOf {
            state.selectedTab != ResultTab.Novel || lists.details.firstVisibleItemIndex > 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { backDispatcher?.onBackPressed() }) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.result_back)
                        )
                    }
                },
                title = {
                    if (display != null && showTitleInBar) {
                        Text(display.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                response == null && state.loadingResponse -> LoadingScreen(Modifier.capped())
                response == null || display == null -> ErrorScreen(state, action)
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val widthDp = maxWidth.value.toInt()
                    if (ResultPageLogic.isTwoPane(widthDp)) {
                        TwoPaneLayout(widthDp, state, response, display, lists, action) { posterShown = true }
                    } else {
                        SinglePaneLayout(state, response, display, lists, action) { posterShown = true }
                    }
                }
            }
        }
    }

    state.dialog?.let { ResultScreenDialog(it, state, action) }

    if (response != null) PosterOverlay(response, posterShown) { posterShown = false }
}

private fun metadataDisplayOf(state: ResultState, response: ImmutableSearchResponse): MetadataDisplay =
    ResultPageLogic.metadataDisplay(
        name = response.name,
        synopsis = response.synopsis,
        latest = state.latestChapterName,
        tags = response.tags.orEmpty(),
        translatedTitle = state.translatedTitle,
        translatedSynopsis = state.translatedSynopsis,
        labels = state.labels,
        showOriginal = state.showOriginal,
        chapterNamesTranslated = state.translateChapterNames && state.chapterNames.isNotEmpty(),
    )

@Composable
private fun SinglePaneLayout(
    state: ResultState,
    response: ImmutableSearchResponse,
    display: MetadataDisplay,
    lists: ResultListStates,
    action: (ResultPageAction) -> Unit,
    showPoster: () -> Unit,
) {
    val tabs = ResultPageLogic.tabs(state.hasReviews, state.hasRelated, state.hasChapters, twoPane = false)
    val shown = ResultPageLogic.shownTab(state.selectedTab, tabs)
    Column(Modifier.fillMaxSize()) {
        if (ResultPageLogic.showTabRow(tabs)) {
            ResultTabRow(tabs, shown, state.allChapters?.size, action)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (shown) {
                ResultTab.Novel -> DetailsList(state, response, display, lists.details, action, showPoster, capWidth = true)
                ResultTab.Reviews -> ReviewsPage(state.reviews, lists.reviews, action)
                ResultTab.Related -> RelatedPage(response, lists.related, action)
                ResultTab.Chapters -> ChapterPane(state, response, lists.chapters, action, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TwoPaneLayout(
    widthDp: Int,
    state: ResultState,
    response: ImmutableSearchResponse,
    display: MetadataDisplay,
    lists: ResultListStates,
    action: (ResultPageAction) -> Unit,
    showPoster: () -> Unit,
) {
    val (detailsWidth, _) = ResultPageLogic.twoPaneWidths(widthDp)
    val tabs = ResultPageLogic.tabs(state.hasReviews, state.hasRelated, state.hasChapters, twoPane = true)
    val shown = ResultPageLogic.shownTab(state.selectedTab, tabs)
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(detailsWidth.dp)
                .fillMaxHeight()
        ) {
            if (ResultPageLogic.showTabRow(tabs)) ResultTabRow(tabs, shown, null, action)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (shown) {
                    ResultTab.Reviews -> ReviewsPage(state.reviews, lists.reviews, action)
                    ResultTab.Related -> RelatedPage(response, lists.related, action)
                    else -> DetailsList(state, response, display, lists.details, action, showPoster, capWidth = false)
                }
            }
        }
        VerticalDivider()
        if (state.hasChapters) {
            ChapterPane(state, response, lists.chapters, action, Modifier.weight(1f).fillMaxHeight())
        } else {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_chapters), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultTabRow(
    tabs: List<ResultTab>,
    shown: ResultTab,
    chapterCount: Int?,
    action: (ResultPageAction) -> Unit,
) {
    PrimaryScrollableTabRow(
        selectedTabIndex = tabs.indexOf(shown).coerceAtLeast(0),
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.background,
        divider = { HorizontalDivider() },
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == shown,
                onClick = { action(ResultPageAction.SelectTab(tab)) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    val title = stringResource(tab.title)
                    Text(
                        if (tab == ResultTab.Chapters && chapterCount != null) "$title · $chapterCount" else title,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

// ================================== details ==================================

@Composable
private fun DetailsList(
    state: ResultState,
    response: ImmutableSearchResponse,
    display: MetadataDisplay,
    listState: LazyListState,
    action: (ResultPageAction) -> Unit,
    showPoster: () -> Unit,
    capWidth: Boolean,
) {
    val itemModifier = if (capWidth) Modifier.capped() else Modifier.fillMaxWidth()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "header") { Header(response, display, state, showPoster) }
        item(key = "actions") { ActionRow(state, response, itemModifier, action) }

        val cells = ResultPageLogic.statCells(
            views = response.loadData?.views,
            rating = response.rating,
            peopleVoted = response.loadData?.peopleVoted,
            chapterCount = (state.allChapters?.size).takeIf { response.loadData?.chapters != null },
        )
        if (cells.isNotEmpty()) item(key = "stats") { StatsRow(cells, itemModifier) }

        state.languageLabel?.let { label ->
            item(key = "language") { LanguageRow(label, itemModifier) { action(ResultPageAction.ChooseSourceLanguage) } }
        }

        if (state.slowSource && !state.isImported) {
            item(key = "slow") {
                Text(
                    stringResource(R.string.long_download_time),
                    color = colorResource(R.color.colorPrimaryRed),
                    fontSize = 14.sp,
                    modifier = itemModifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        val downloadState = response.downloadState
        if (downloadState != null) {
            item(key = "download") { DownloadSection(state, response, downloadState, itemModifier, action) }
        }

        if (state.showTranslate) {
            item(key = "translate") { TranslateRow(state, itemModifier, action) }
        }

        if (!display.synopsis.isNullOrBlank() || display.canToggle) {
            item(key = "synopsis") { Synopsis(display, state.showOriginal, itemModifier, action) }
        }

        if (display.tags.isNotEmpty()) {
            item(key = "tags") { Tags(response, display, state, itemModifier, action) }
        }
        item(key = "bottom") { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun Header(
    response: ImmutableSearchResponse,
    display: MetadataDisplay,
    state: ResultState,
    showPoster: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
    ) {
        AsyncImage(
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
            model = response.blurImageRequest(),
            contentDescription = null,
            alpha = 0.3f
        )
        Row(
            Modifier
                .capped()
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            com.lagradost.quicknovel.ui.common.PosterBox(
                request = response.imageRequest(),
                model = response.rememberPosterModel(),
                title = response.name,
                titleSize = 12.sp,
                modifier = Modifier
                    .size(width = 100.dp, height = 150.dp)
                    .rounded()
                    .clickable(onClick = showPoster),
                contentDescription = stringResource(R.string.poster_descript),
            )
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(display.title, fontSize = 20.sp, lineHeight = 24.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                display.originalTitle?.let {
                    Text(
                        it, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    response.author ?: stringResource(R.string.no_author),
                    color = MaterialTheme.colorScheme.primary, fontSize = 14.sp
                )
                response.loadData?.status?.let { status ->
                    Text(stringResource(status.resource), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp)
                }
                if (state.allChapters != null) {
                    Text(
                        display.latest?.let { stringResource(R.string.latest_format, it) } ?: stringResource(R.string.no_chapters),
                        fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    state: ResultState,
    response: ImmutableSearchResponse,
    modifier: Modifier,
    action: (ResultPageAction) -> Unit
) {
    Row(
        modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        TextIcon(
            stringResource(if (state.bookmark == ReadType.NONE) R.string.bookmark else state.bookmark.stringRes),
            icon = if (state.bookmark == ReadType.NONE) R.drawable.ic_baseline_bookmark_border_24 else R.drawable.ic_baseline_bookmark_24,
        ) { action(ResultPageAction.OpenBookmark) }
        if (!state.isImported) {
            TextIcon(stringResource(R.string.open_in_browser), R.drawable.ic_baseline_public_24) {
                openInBrowser(response.url)
            }
            TextIcon(stringResource(R.string.result_share), R.drawable.ic_outline_share_24) {
                safe {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_SUBJECT, response.name)
                    i.putExtra(Intent.EXTRA_TEXT, response.url)
                    activity?.startActivity(Intent.createChooser(i, response.name))
                }
            }
        }
    }
}

@Composable
private fun StatsRow(cells: List<StatCell>, modifier: Modifier) {
    val context = LocalContext.current
    Row(
        modifier
            .padding(horizontal = 5.dp)
            .height(56.dp)
    ) {
        cells.forEach { cell ->
            when (cell) {
                is StatCell.Views -> TextInfo(humanReadableByteCountSI(cell.views), stringResource(R.string.views))
                is StatCell.Rating -> TextInfo(
                    context.getRating(cell.rating),
                    cell.votes?.let { stringResource(R.string.votes_format, it) } ?: stringResource(R.string.result_rating)
                )

                is StatCell.Votes -> TextInfo(cell.votes.toString(), stringResource(R.string.votes_format, cell.votes).substringAfter(' '))
                is StatCell.Chapters -> TextInfo(
                    cell.count.toString(),
                    stringResource(if (cell.count == 1) R.string.chapter else R.string.chapters)
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(label: SourceLanguageLabel, modifier: Modifier, onClick: () -> Unit) {
    val name = label.code?.let { TranslationLanguages.displayName(it) }
    Row(
        modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(R.drawable.ic_baseline_language_24), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.result_source_language) + ": ", fontSize = 14.sp)
        Text(
            when {
                name == null -> stringResource(R.string.result_language_unknown)
                label.manual -> stringResource(R.string.result_language_chosen, name)
                else -> stringResource(R.string.result_language_detected, name)
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSection(
    state: ResultState,
    response: ImmutableSearchResponse,
    downloadState: ImmutableDownloadState,
    modifier: Modifier,
    action: (ResultPageAction) -> Unit,
) {
    val actions = remember(downloadState, state.isImported) {
        ResultPageLogic.downloadActions(downloadState.status, downloadState.progress, downloadState.downloaded, downloadState.total)
            .filter { !state.isImported || it == DownloadMenuAction.Delete }
    }
    val look = ResultPageLogic.downloadButton(downloadState.status, downloadState.progress, downloadState.total)
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row {
            TextButton(
                shape = RoundedShape(),
                colors = Colors.whiteButton,
                enabled = downloadState.progress > 0,
                modifier = Modifier
                    .padding(2.dp)
                    .height(44.dp)
                    .weight(1f),
                onClick = { action(ResultPageAction.ReadEpub) }
            ) {
                if (response.generating) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(22.dp), strokeWidth = 3.dp
                    )
                } else {
                    Icon(painterResource(R.drawable.book_2_24px), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.read_epub), maxLines = 1)
                }
            }
            Box(
                Modifier
                    .padding(2.dp)
                    .height(44.dp)
                    .weight(1f)
            ) {
                DownloadButton(look, enabled = look.enabled && (!state.isImported || actions.isNotEmpty()),
                    onClick = {
                        if (state.isImported) {
                            menuOpen = actions.isNotEmpty()
                            return@DownloadButton
                        }
                        when (val click = ResultPageLogic.downloadClick(actions)) {
                            DownloadClick.Toggle -> action(ResultPageAction.DownloadOrPause)
                            is DownloadClick.Run -> action(ResultPageAction.Download(click.action))
                            is DownloadClick.Menu -> menuOpen = true
                        }
                    },
                    onLongClick = { if (actions.isNotEmpty()) menuOpen = true })
                DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                    actions.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(stringResource(item.label)) },
                            onClick = {
                                menuOpen = false
                                action(ResultPageAction.Download(item))
                            })
                    }
                }
            }
        }
        if (!state.isImported && state.hasChapters) {
            // Preview (was upstream's Stream Read): long press shows what it does.
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(stringResource(R.string.preview_desc)) } },
                state = rememberTooltipState(),
            ) {
                TextButton(
                    shape = RoundedShape(),
                    colors = Colors.blackButton,
                    modifier = Modifier
                        .padding(2.dp)
                        .fillMaxWidth(),
                    onClick = { action(ResultPageAction.Preview) }
                ) {
                    Icon(painterResource(R.drawable.netflix_play), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.preview))
                }
            }
        }

        val animatedProgress by animateFloatAsState(
            downloadState.progressPercentage, label = "progress", animationSpec = tween(durationMillis = 500),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${downloadState.progress}/${downloadState.total}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadButton(look: DownloadButtonLook, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = Colors.blackButton
    Row(
        Modifier
            .fillMaxSize()
            .clip(RoundedShape())
            .background(colors.containerColor)
            .alpha(if (enabled) 1f else 0.5f)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(look.icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.contentColor)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(look.label), color = colors.contentColor, maxLines = 1, fontSize = 14.sp)
    }
}

@Composable
private fun TranslateRow(state: ResultState, modifier: Modifier, action: (ResultPageAction) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val button = state.translateButton
    Row(
        modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            shape = RoundedShape(),
            colors = Colors.whiteButton,
            enabled = button.enabled,
            modifier = Modifier
                .padding(2.dp)
                .weight(1f),
            onClick = { action(ResultPageAction.Translate()) }
        ) {
            Icon(painterResource(R.drawable.ic_baseline_translate_24), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when (val label = button.progressLabel) {
                    null -> stringResource(R.string.result_translate)
                    ResultPageLogic.STARTING -> stringResource(R.string.result_translate_starting)
                    else -> label
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            shape = RoundedShape(),
            colors = Colors.whiteButton,
            modifier = Modifier
                .padding(2.dp)
                .weight(1f),
            onClick = { action(ResultPageAction.OpenGlossary) }
        ) {
            Icon(painterResource(R.drawable.ic_glossary_24), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.result_glossary), maxLines = 1)
        }
        // The analysis and language actions used to hide behind a long press: a visible menu now.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    painterResource(R.drawable.ic_baseline_more_vert_24),
                    contentDescription = stringResource(R.string.result_more_actions)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.glossary_menu_analyze)) },
                    onClick = {
                        menuOpen = false
                        action(ResultPageAction.Analyze)
                    })
                if (state.isImported) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.imported_menu_language)) },
                        onClick = {
                            menuOpen = false
                            action(ResultPageAction.ChooseSourceLanguage)
                        })
                }
            }
        }
    }
}

@Composable
private fun Synopsis(display: MetadataDisplay, showOriginal: Boolean, modifier: Modifier, action: (ResultPageAction) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        if (display.canToggle) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedShape())
                    .clickable { action(ResultPageAction.ToggleOriginal) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_original_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(if (showOriginal) R.string.browse_show_translation else R.string.browse_show_original),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val synopsis = display.synopsis
        if (synopsis.isNullOrBlank()) return@Column
        Text(
            text = rememberHtml(synopsis),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedShape())
                .clickable { expanded = !expanded }
                .padding(6.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(
    response: ImmutableSearchResponse,
    display: MetadataDisplay,
    state: ResultState,
    modifier: Modifier,
    action: (ResultPageAction) -> Unit,
) {
    val originals = response.tags.orEmpty()
    FlowRow(modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        display.tags.forEachIndexed { i, tag ->
            val index = originals.getOrNull(i)?.let { state.tagIndex[it] }
            Text(
                text = tag,
                fontSize = 13.sp,
                lineHeight = 14.sp,
                modifier = Modifier
                    .padding(3.dp)
                    .clip(RoundedShape())
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (index != null) Modifier.clickable { action(ResultPageAction.OpenTag(index)) } else Modifier)
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            )
        }
    }
}

// ================================== chapters ==================================

@Composable
private fun ChapterPane(
    state: ResultState,
    response: ImmutableSearchResponse,
    listState: LazyListState,
    action: (ResultPageAction) -> Unit,
    modifier: Modifier,
) {
    val chapters = state.chapters
    var searching by rememberSaveable { mutableStateOf(state.chapterQuery.isNotEmpty()) }
    Column(modifier) {
        Row(
            Modifier
                .capped()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val total = state.allChapters?.size ?: 0
            val listed = chapters?.size ?: 0
            Text(
                if (listed == total) pluralStringResource(R.plurals.result_chapter_count, total, total)
                else "$listed / " + pluralStringResource(R.plurals.result_chapter_count, total, total),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                searching = !searching
                if (!searching) action(ResultPageAction.SetChapterQuery(""))
            }) {
                Icon(painterResource(R.drawable.search_icon), contentDescription = stringResource(R.string.result_search_chapters))
            }
            IconButton(onClick = { action(ResultPageAction.OpenChapterFilter) }) {
                Icon(
                    painterResource(R.drawable.ic_baseline_filter_list_24),
                    contentDescription = stringResource(R.string.result_filter_sort)
                )
            }
        }
        if (searching) {
            OutlinedTextField(
                value = state.chapterQuery,
                onValueChange = { action(ResultPageAction.SetChapterQuery(it)) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.result_search_chapters)) },
                modifier = Modifier
                    .capped()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        HorizontalDivider()
        if (chapters.isNullOrEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (state.hasChapters) R.string.result_no_matching_chapters else R.string.no_chapters),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            if (state.translateChapterNames && !state.showOriginal) {
                // Report the visible rows; the view model translates them (and a few more) in
                // batches once scrolling pauses.
                LaunchedEffect(listState, chapters) {
                    snapshotFlow {
                        val visible = listState.layoutInfo.visibleItemsInfo
                        if (visible.isEmpty()) null else visible.first().index to visible.last().index
                    }.filterNotNull().distinctUntilChanged().collect { (first, last) ->
                        action(ResultPageAction.ChapterRowsVisible(first, last))
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                items(chapters, key = { it.url + "#" + it.index }) { chapter ->
                    ChapterRow(
                        state = state,
                        response = response,
                        chapter = chapter,
                        read = state.readAt.containsKey(chapter.index),
                        status = ResultPageLogic.chipFor(chapter.index, state.chapterStatuses),
                        action = action,
                        modifier = Modifier.capped()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    state: ResultState,
    response: ImmutableSearchResponse,
    chapter: ImmutableChapterData,
    read: Boolean,
    status: ChapterTranslationStatus,
    action: (ResultPageAction) -> Unit,
    modifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val (name, original) = ResultPageLogic.chapterRowName(
        chapter.name, state.chapterNames, state.translateChapterNames, state.showOriginal
    )
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (state.isImported) null else stringResource(R.string.preview),
                    onClick = { action(ResultPageAction.ChapterAction(response, chapter, ChapterOperation.Preview)) },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .alpha(if (read) 0.5f else 1f)
            ) {
                Text(name, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val date = chapter.dateOfRelease?.takeIf { it.isNotBlank() }
                if (original != null || date != null) {
                    // The original name (when translated) and the date share one small line.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (original != null) {
                            Text(
                                original, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (original != null && date != null) Spacer(Modifier.width(8.dp))
                        if (date != null) {
                            Text(
                                date, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (status != ChapterTranslationStatus.NONE) {
                Spacer(Modifier.width(8.dp))
                StatusChip(status)
            }
        }
        DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
            if (response.loadData?.chapters != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (read) R.string.result_mark_unread else R.string.result_mark_read)) },
                    onClick = {
                        menuOpen = false
                        action(ResultPageAction.ChapterAction(response, chapter, ChapterOperation.ToggleRead))
                    })
            }
            if (state.showTranslate) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.result_translate_chapter)) },
                    onClick = {
                        menuOpen = false
                        action(ResultPageAction.Translate(startChapter = chapter.index))
                    })
            }
            if (!state.isImported && response.loadData?.chapters != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.result_download_from_here)) },
                    onClick = {
                        menuOpen = false
                        action(ResultPageAction.DownloadFrom(chapter.index))
                    })
            }
        }
    }
}

@Composable
private fun StatusChip(status: ChapterTranslationStatus) {
    val (text, color) = when (status) {
        ChapterTranslationStatus.TRANSLATED -> R.string.chapter_status_translated to MaterialTheme.colorScheme.primary
        ChapterTranslationStatus.STALE -> R.string.chapter_status_stale to colorResource(R.color.ratingColorBg)
        ChapterTranslationStatus.QUEUED -> R.string.chapter_status_queued to MaterialTheme.colorScheme.onSurfaceVariant
        ChapterTranslationStatus.NONE -> return
    }
    Text(
        stringResource(text),
        color = color,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

// ================================== reviews / related ==================================

@Composable
private fun ReviewsPage(
    reviews: ResultReviewState,
    listState: LazyListState,
    action: (ResultPageAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val showToTop by remember { derivedStateOf { ResultPageLogic.showScrollToTop(listState.firstVisibleItemIndex) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(reviews.items, key = { it.randomUuid }) { review ->
                ReviewItem(review, modifier = Modifier.capped(), action = action)
            }
            if (reviews.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        // "To top", as the XML page's reviews FAB: appears once a few reviews are scrolled past.
        AnimatedVisibility(
            visible = showToTop,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    painterResource(R.drawable.ic_baseline_arrow_upward_24),
                    contentDescription = stringResource(R.string.reviews_to_top)
                )
            }
        }
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(shouldLoadMore, reviews.items.size) {
        if (shouldLoadMore && !reviews.loading && reviews.error == null) action(ResultPageAction.ExpandReviews)
    }
}

@Composable
fun ReviewItem(
    review: ImmutableReview,
    modifier: Modifier,
    action: (ResultPageAction) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showSpoiler by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.padding(bottom = 10.dp)) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (review.avatarUrl != null) {
                AsyncImage(
                    contentScale = ContentScale.Crop,
                    model = review.imageRequest(),
                    contentDescription = stringResource(R.string.user_image_avatar),
                    modifier = Modifier
                        .size(40.dp)
                        .circle()
                        .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), CircleShape)
                )
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                review.title?.takeIf { it.isNotBlank() }?.let { Text(text = it, fontSize = 14.sp) }
                Row {
                    review.username?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(end = 5.dp))
                    }
                    review.date?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                }
            }
        }

        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(modifier = Modifier.padding(horizontal = 6.dp)) {
            review.rating?.let {
                Text(
                    modifier = Modifier
                        .padding(4.dp)
                        .background(color = MaterialTheme.colorScheme.onBackground, shape = RoundedShape())
                        .padding(horizontal = 6.dp),
                    color = MaterialTheme.colorScheme.background,
                    text = stringResource(R.string.overall) + " " + LocalContext.current.getRatingReview(it),
                    fontSize = 13.sp
                )
            }
            review.ratings?.forEach { (rating, name) ->
                Text(
                    modifier = Modifier
                        .padding(4.dp)
                        .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedShape())
                        .padding(horizontal = 6.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                    text = "$name " + LocalContext.current.getRatingReview(rating),
                    fontSize = 13.sp
                )
            }
        }

        val isSpoilerActive = review.containsSpoilers && !showSpoiler
        Text(
            modifier = Modifier
                .padding(5.dp)
                .fillMaxWidth()
                .clip(RoundedShape())
                .clickable {
                    if (isSpoilerActive) showSpoiler = true else expanded = !expanded
                }
                .background(if (isSpoilerActive) MaterialTheme.colorScheme.onBackground else Color.Transparent, RoundedShape())
                .padding(5.dp),
            text = rememberHtml(review.content),
            color = if (isSpoilerActive) Color.Transparent else MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 8,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RelatedPage(
    response: ImmutableSearchResponse,
    gridState: LazyGridState,
    action: (ResultPageAction) -> Unit,
) {
    SearchList(
        lazyGridState = gridState,
        isRow = false,
        items = response.loadData?.related ?: kotlinx.collections.immutable.persistentListOf(),
        modifier = Modifier.fillMaxSize(),
        searchAction = { value -> action(ResultPageAction.ResultAction(value)) }
    )
}

// ================================== dialogs ==================================

@Composable
private fun ResultScreenDialog(dialog: ResultDialog, state: ResultState, action: (ResultPageAction) -> Unit) {
    val dismiss = { action(ResultPageAction.DismissDialog) }
    when (dialog) {
        is ResultDialog.Bookmark -> SingleSelectDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            entries = ReadType.entries.associateWith { item -> stringResource(item.stringRes) },
            dismiss = dismiss,
            title = stringResource(R.string.bookmark),
            selectedKey = dialog.selected,
            confirm = { selected ->
                action(ResultPageAction.SetBookmark(selected))
                action(ResultPageAction.DismissDialog)
            })

        is ResultDialog.SourceLanguage -> {
            val languages = TranslationLanguages.all
            SingleSelectDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                entries = languages.associate { it.code to it.displayName },
                dismiss = dismiss,
                title = if (dialog.detected == null) stringResource(R.string.imported_language_unknown)
                else stringResource(R.string.imported_language_title, TranslationLanguages.displayName(dialog.detected)),
                selectedKey = dialog.current ?: "",
                confirm = { code -> if (code.isNotEmpty()) action(ResultPageAction.SetSourceLanguage(code)) else dismiss() },
            )
        }

        ResultDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.permanently_delete_format).format(state.response?.name ?: "")) },
            confirmButton = { TextButton(onClick = { action(ResultPageAction.ConfirmDelete) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )

        is ResultDialog.DownloadFrom -> DownloadFromDialog(dialog.chapterCount, action, dismiss)
        ResultDialog.ChapterFilter -> ChapterFilterDialog(state, action, dismiss)
    }
}

@Composable
private fun DownloadFromDialog(chapterCount: Int, action: (ResultPageAction) -> Unit, dismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val value = text.toIntOrNull()
    val valid = value != null && value >= 0 && value < chapterCount
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.download_from_chapter)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                supportingText = { if (text.isNotEmpty() && !valid) Text(stringResource(R.string.error_outside_chapter)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { action(ResultPageAction.DownloadFrom(value)) }) {
                Text(stringResource(R.string.download))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChapterFilterDialog(state: ResultState, action: (ResultPageAction) -> Unit, dismiss: () -> Unit) {
    val filter = state.chapterFilter
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.result_filter_sort)) },
        text = {
            Column {
                Text(stringResource(R.string.mainpage_filter), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                CheckRow(stringResource(R.string.downloaded), filter.downloaded) {
                    action(ResultPageAction.SetChapterFilter(filter.copy(downloaded = it)))
                }
                CheckRow(stringResource(R.string.read), filter.read) {
                    action(ResultPageAction.SetChapterFilter(filter.copy(read = it)))
                }
                CheckRow(stringResource(R.string.unread), filter.unread) {
                    action(ResultPageAction.SetChapterFilter(filter.copy(unread = it)))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mainpage_sort_by_button_text), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                val ascending = stringResource(R.string.result_sort_ascending)
                val descending = stringResource(R.string.result_sort_descending)
                listOf(
                    CHAPTER_SORT to "${stringResource(R.string.chapter_sort)} ($ascending)",
                    REVERSE_CHAPTER_SORT to "${stringResource(R.string.chapter_sort)} ($descending)",
                    LAST_ACCES_SORT to "${stringResource(R.string.recently_sort)} ($ascending)",
                    REVERSE_LAST_ACCES_SORT to "${stringResource(R.string.recently_sort)} ($descending)",
                ).forEach { (sort, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { action(ResultPageAction.SetChapterSort(sort)) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = state.chapterSort == sort, onClick = { action(ResultPageAction.SetChapterSort(sort)) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

// ================================== misc ==================================

@Composable
private fun PosterOverlay(response: ImmutableSearchResponse, shown: Boolean, close: () -> Unit) {
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "poster", animationSpec = tween(durationMillis = 200))
    BackHandler(enabled = shown, onBack = close)
    if (alpha > 0f) {
        AsyncImage(
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f * alpha))
                .clickable(onClick = close)
                .alpha(alpha),
            model = response.imageRequest(),
            contentDescription = stringResource(R.string.poster_descript),
        )
    }
}

@Composable
private fun ErrorScreen(state: ResultState, action: (ResultPageAction) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            state.errorText ?: state.responseError?.message ?: stringResource(R.string.error_loading_novel),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Row {
            TextButton(onClick = { action(ResultPageAction.Retry) }) { Text(stringResource(R.string.result_retry)) }
            if (!state.isImported) {
                TextButton(onClick = { openInBrowser(state.url) }) { Text(stringResource(R.string.open_in_browser)) }
            }
        }
    }
}

@Composable
private fun RowScope.TextInfo(text: String, subText: String) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
    ) {
        Text(text, fontSize = 17.sp, lineHeight = 18.sp, maxLines = 1)
        Text(
            subText, fontSize = 12.sp, lineHeight = 13.sp, maxLines = 1,
            modifier = Modifier.padding(2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RowScope.TextIcon(text: String, icon: Int, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick)
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun LoadingScreen(modifier: Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingPoster()
            Column {
                LoadingLine()
                LoadingLine(0.7f)
            }
        }
        LoadingLine()
        Row(horizontalArrangement = Arrangement.SpaceEvenly) {
            Spacer(Modifier.weight(0.3f))
            LoadingWeight()
            Spacer(Modifier.weight(0.3f))
            LoadingWeight()
            Spacer(Modifier.weight(0.3f))
        }
        Spacer(Modifier.height(20.dp))
        Row {
            LoadingWidth(100.dp)
            LoadingWidth(100.dp)
        }
        Spacer(Modifier.height(20.dp))
        LoadingLine()
        LoadingLine()
        LoadingLine(0.4f)
        Spacer(Modifier.height(20.dp))
        LoadingLine()
        Row {
            LoadingButton()
            LoadingButton()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(loadingLineMargin.dp)
                .height(35.dp)
                .loading()
        )
    }
}
