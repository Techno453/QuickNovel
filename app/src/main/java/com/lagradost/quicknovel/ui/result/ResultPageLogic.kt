package com.lagradost.quicknovel.ui.result

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ui.common.ImmutableChapterData
import com.lagradost.quicknovel.ui.download.CHAPTER_SORT
import com.lagradost.quicknovel.ui.download.LAST_ACCES_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_CHAPTER_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_LAST_ACCES_SORT
import com.lagradost.quicknovel.ui.translate.TranslationProgress

/**
 * The pure rules of the Compose result page (docs/result-page.md): tabs, panes, stats, download
 * actions, chapter filtering, the Translate button and the translated metadata. No Android types,
 * unit-tested in `ResultPageLogicTest`.
 */

/** Tabs of the result page. In the two-pane layout the chapter list is its own pane, not a tab. */
enum class ResultTab(@StringRes val title: Int) {
    Novel(R.string.novel),
    Reviews(R.string.reviews),
    Related(R.string.related),
    Chapters(R.string.read_action_chapters),
}

/** One cell of the stats row. Cells without data are left out (no "No Data" cells). */
@Immutable
sealed class StatCell {
    data class Views(val views: Int) : StatCell()
    data class Rating(val rating: Int, val votes: Int?) : StatCell()
    data class Votes(val votes: Int) : StatCell()
    data class Chapters(val count: Int) : StatCell()
}

/** Entries of the download button's menu (long press, or a tap when there is a choice). */
enum class DownloadMenuAction(@StringRes val label: Int) {
    Resume(R.string.resume),
    Pause(R.string.pause),
    Stop(R.string.stop),
    Download(R.string.download),
    Redownload(R.string.re_downloaded),
    DownloadFrom(R.string.download_from_chapter),
    Delete(R.string.delete),
}

/** What a tap on the download button does. */
@Immutable
sealed class DownloadClick {
    /** Download, or pause / resume the running download. */
    object Toggle : DownloadClick()
    data class Run(val action: DownloadMenuAction) : DownloadClick()
    /** Several choices: open the menu. */
    data class Menu(val actions: List<DownloadMenuAction>) : DownloadClick()
}

/** Text and icon of the download button. */
@Immutable
data class DownloadButtonLook(@StringRes val label: Int, @DrawableRes val icon: Int, val enabled: Boolean)

/** Chapter list filter, the same switches (and stored preferences) as the old page's sheet. */
@Immutable
data class ChapterFilter(
    val read: Boolean = true,
    val unread: Boolean = true,
    val downloaded: Boolean = false,
    val bookmarked: Boolean = false,
)

/** The Translate button: enabled, and the text to show instead of "Translate" while a job runs. */
@Immutable
data class TranslateButtonState(val enabled: Boolean = true, val progressLabel: String? = null)

/** How the source language of an imported book is shown. */
@Immutable
data class SourceLanguageLabel(val code: String?, val manual: Boolean)

/** Title, synopsis, latest chapter and tags as shown (translated or original). */
@Immutable
data class MetadataDisplay(
    val title: String,
    /** The original title under the translated one; null when the original is shown. */
    val originalTitle: String?,
    val synopsis: String?,
    val latest: String?,
    val tags: List<String>,
    /** A translation exists, so "Show original" / "Show translation" is offered. */
    val canToggle: Boolean,
)

object ResultPageLogic {
    /** The details/chapters two-pane layout starts at the expanded width class. */
    const val TWO_PANE_MIN_WIDTH_DP = 840

    /** The details pane of the two-pane layout: this share of the width, within the bounds below. */
    const val DETAILS_PANE_FRACTION = 0.42f
    const val DETAILS_PANE_MIN_DP = 360
    const val DETAILS_PANE_MAX_DP = 520

    fun isTwoPane(widthDp: Int): Boolean = widthDp >= TWO_PANE_MIN_WIDTH_DP

    /** (details, chapters) pane widths in dp for a two-pane window [totalDp] wide. */
    fun twoPaneWidths(totalDp: Int): Pair<Int, Int> {
        val details = (totalDp * DETAILS_PANE_FRACTION).toInt()
            .coerceIn(DETAILS_PANE_MIN_DP, DETAILS_PANE_MAX_DP)
            .coerceAtMost(totalDp)
        return details to (totalDp - details).coerceAtLeast(0)
    }

    /**
     * Tabs to show. The single column has Novel, Reviews, Related and Chapters (when there are
     * any); the two-pane layout drops Chapters (it is the right pane). A lone Novel tab is not
     * shown as a tab row (see [showTabRow]).
     */
    fun tabs(hasReviews: Boolean, hasRelated: Boolean, hasChapters: Boolean, twoPane: Boolean): List<ResultTab> =
        buildList {
            add(ResultTab.Novel)
            if (hasReviews) add(ResultTab.Reviews)
            if (hasRelated) add(ResultTab.Related)
            if (hasChapters && !twoPane) add(ResultTab.Chapters)
        }

    fun showTabRow(tabs: List<ResultTab>): Boolean = tabs.size > 1

    /**
     * The tab to display for the remembered [selected] one. The selection itself is kept as it is,
     * so Chapters comes back when the window goes from two panes back to one (fold).
     */
    fun shownTab(selected: ResultTab, tabs: List<ResultTab>): ResultTab =
        if (selected in tabs) selected else ResultTab.Novel

    fun statCells(views: Int?, rating: Int?, peopleVoted: Int?, chapterCount: Int?): List<StatCell> = buildList {
        if (views != null && views > 0) add(StatCell.Views(views))
        when {
            rating != null -> add(StatCell.Rating(rating, peopleVoted?.takeIf { it > 0 }))
            peopleVoted != null && peopleVoted > 0 -> add(StatCell.Votes(peopleVoted))
        }
        if (chapterCount != null && chapterCount > 0) add(StatCell.Chapters(chapterCount))
    }

    /** Menu entries for the current download state (same rules as the old page). */
    fun downloadActions(status: DownloadState, progress: Long, downloaded: Long, total: Long): List<DownloadMenuAction> {
        val canDownload = progress < total
        val canPartialDownload = downloaded < total && total > 1
        val items = mutableListOf<DownloadMenuAction>()
        when (status) {
            DownloadState.IsPaused -> {
                items += DownloadMenuAction.Resume
                items += DownloadMenuAction.Stop
            }

            DownloadState.IsDownloading -> {
                items += DownloadMenuAction.Pause
                items += DownloadMenuAction.Stop
            }

            DownloadState.IsDone -> if (canPartialDownload) items += DownloadMenuAction.DownloadFrom
            DownloadState.IsPending -> {}
            DownloadState.IsFailed, DownloadState.IsStopped -> {
                if (canDownload) items += DownloadMenuAction.Redownload
                if (canPartialDownload) items += DownloadMenuAction.DownloadFrom
            }

            DownloadState.Nothing -> {
                if (canDownload) items += DownloadMenuAction.Download
                if (canPartialDownload) items += DownloadMenuAction.DownloadFrom
            }
        }
        if (progress > 0) items += DownloadMenuAction.Delete
        return items
    }

    fun downloadClick(actions: List<DownloadMenuAction>): DownloadClick = when {
        actions.size == 1 -> DownloadClick.Run(actions[0])
        DownloadMenuAction.Download in actions || DownloadMenuAction.Pause in actions -> DownloadClick.Toggle
        actions.isEmpty() -> DownloadClick.Toggle
        else -> DownloadClick.Menu(actions)
    }

    fun downloadButton(status: DownloadState, progress: Long, total: Long): DownloadButtonLook {
        val canDownload = progress < total
        val label = when (status) {
            DownloadState.IsDone -> R.string.manage
            DownloadState.IsDownloading -> R.string.pause
            DownloadState.IsPaused -> R.string.resume
            DownloadState.IsFailed -> R.string.re_downloaded
            DownloadState.IsStopped -> R.string.downloaded
            DownloadState.Nothing -> if (canDownload) R.string.download else R.string.manage
            DownloadState.IsPending -> R.string.loading
        }
        val icon = when (status) {
            DownloadState.IsDownloading -> R.drawable.ic_baseline_pause_24
            DownloadState.IsPaused -> R.drawable.netflix_play
            DownloadState.IsFailed -> R.drawable.ic_baseline_autorenew_24
            DownloadState.IsDone -> R.drawable.ic_outline_settings_24
            DownloadState.Nothing -> if (canDownload) R.drawable.netflix_download else R.drawable.ic_outline_settings_24
            else -> R.drawable.netflix_download
        }
        return DownloadButtonLook(label, icon, enabled = total > 0)
    }

    /**
     * The chapter list as shown: filtered by read / unread / downloaded (a chapter passes when any
     * checked switch matches it, as before), then by [query] (part of the name, ignoring case, or
     * the chapter number), then sorted. [readAt] holds the last read time per chapter index;
     * [downloadedProgress] is the download progress (chapters below it are downloaded).
     */
    fun filterChapters(
        chapters: List<ImmutableChapterData>,
        readAt: Map<Int, Long>,
        downloadedProgress: Long?,
        filter: ChapterFilter,
        sort: Int,
        query: String = "",
    ): List<ImmutableChapterData> {
        val q = query.trim()
        val number = q.toIntOrNull()
        return chapters.filter { chapter ->
            val read = readAt.containsKey(chapter.index)
            (filter.unread && !read) || (filter.read && read) ||
                    (filter.downloaded && downloadedProgress != null && downloadedProgress > chapter.index)
        }.filter { chapter ->
            q.isEmpty() || chapter.name.contains(q, ignoreCase = true) || (number != null && chapter.index + 1 == number)
        }.let { list ->
            when (sort) {
                CHAPTER_SORT -> list.sortedBy { it.index }
                REVERSE_CHAPTER_SORT -> list.sortedByDescending { it.index }
                LAST_ACCES_SORT -> list.sortedBy { readAt[it.index] ?: Long.MAX_VALUE }
                REVERSE_LAST_ACCES_SORT -> list.sortedBy { -(readAt[it.index] ?: Long.MAX_VALUE) }
                else -> list
            }
        }
    }

    /** The chip of chapter [index] (Queued over Source changed over Translated, see [ChapterStatusSource]). */
    fun chipFor(index: Int, statuses: Map<Int, ChapterTranslationStatus>): ChapterTranslationStatus =
        statuses[index] ?: ChapterTranslationStatus.NONE

    /**
     * The Translate button for the activity-wide job [progress]: disabled with "n/m" (or the
     * analysis step) while a job for this novel runs; usable otherwise.
     */
    fun translateButton(progress: TranslationProgress?, isForThisNovel: Boolean): TranslateButtonState = when {
        progress == null || !isForThisNovel -> TranslateButtonState()
        progress.completed || progress.error != null -> TranslateButtonState()
        progress.analyzing -> TranslateButtonState(enabled = false, progressLabel = progress.currentChapterName)
        progress.total > 0 -> TranslateButtonState(enabled = false, progressLabel = "${progress.current}/${progress.total}")
        else -> TranslateButtonState(enabled = false, progressLabel = STARTING)
    }

    /** Marker for "Starting…" (localised by the UI). */
    const val STARTING = "\u0000starting"

    /** The language of an imported book: the user's choice wins over detection. */
    fun sourceLanguageLabel(detected: String?, override: String?): SourceLanguageLabel = when {
        override != null -> SourceLanguageLabel(override, manual = true)
        else -> SourceLanguageLabel(detected, manual = false)
    }

    /**
     * What to show for title, synopsis, latest chapter and tags. With [showOriginal] (or nothing
     * translated yet) the originals; otherwise each part that has a translation is replaced.
     */
    fun metadataDisplay(
        name: String,
        synopsis: String?,
        latest: String?,
        tags: List<String>,
        translatedTitle: String?,
        translatedSynopsis: String?,
        labels: Map<String, String>,
        showOriginal: Boolean,
    ): MetadataDisplay {
        val tagTranslated = tags.any { labels.containsKey(it.trim()) }
        val latestTranslated = latest != null && labels.containsKey(latest.trim())
        val canToggle = translatedTitle != null || translatedSynopsis != null || tagTranslated || latestTranslated
        if (showOriginal || !canToggle) {
            return MetadataDisplay(name, null, synopsis, latest, tags, canToggle)
        }
        return MetadataDisplay(
            title = translatedTitle ?: name,
            originalTitle = if (translatedTitle != null) name else null,
            synopsis = translatedSynopsis ?: synopsis,
            latest = latest?.let { labels[it.trim()] ?: it },
            tags = tags.map { labels[it.trim()] ?: it },
            canToggle = true,
        )
    }
}
