package com.lagradost.quicknovel.ui.result

import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ui.common.ImmutableChapterData
import com.lagradost.quicknovel.ui.download.CHAPTER_SORT
import com.lagradost.quicknovel.ui.download.LAST_ACCES_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_CHAPTER_SORT
import com.lagradost.quicknovel.ui.download.REVERSE_LAST_ACCES_SORT
import com.lagradost.quicknovel.ui.result.DownloadMenuAction.Delete
import com.lagradost.quicknovel.ui.result.DownloadMenuAction.Download
import com.lagradost.quicknovel.ui.result.DownloadMenuAction.DownloadFrom
import com.lagradost.quicknovel.ui.result.DownloadMenuAction.Pause
import com.lagradost.quicknovel.ui.result.DownloadMenuAction.Redownload
import com.lagradost.quicknovel.ui.result.DownloadMenuAction.Resume
import com.lagradost.quicknovel.ui.result.DownloadMenuAction.Stop
import com.lagradost.quicknovel.ui.translate.TranslationProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultPageLogicTest {
    private val logic = ResultPageLogic

    // ---------------- panes and tabs ----------------

    @Test
    fun twoPanesFromTheExpandedWidthClass() {
        assertFalse(logic.isTwoPane(360)) // phone
        assertFalse(logic.isTwoPane(475)) // Fold cover screen
        assertFalse(logic.isTwoPane(839)) // medium
        assertTrue(logic.isTwoPane(840))
        assertTrue(logic.isTwoPane(932)) // Fold inner screen at 420 dpi
        assertTrue(logic.isTwoPane(1280))
    }

    @Test
    fun detailsPaneWidthIsBounded() {
        assertEquals(391 to 541, logic.twoPaneWidths(932))
        assertEquals(360 to 480, logic.twoPaneWidths(840)) // 0.42 * 840 = 352 → min 360
        assertEquals(520 to 1080, logic.twoPaneWidths(1600)) // capped
    }

    @Test
    fun chaptersIsATabOnlyInTheSingleColumn() {
        assertEquals(
            listOf(ResultTab.Novel, ResultTab.Reviews, ResultTab.Related, ResultTab.Chapters),
            logic.tabs(hasReviews = true, hasRelated = true, hasChapters = true, twoPane = false)
        )
        assertEquals(
            listOf(ResultTab.Novel, ResultTab.Reviews, ResultTab.Related),
            logic.tabs(hasReviews = true, hasRelated = true, hasChapters = true, twoPane = true)
        )
        val onlyNovel = logic.tabs(hasReviews = false, hasRelated = false, hasChapters = true, twoPane = true)
        assertEquals(listOf(ResultTab.Novel), onlyNovel)
        assertFalse(logic.showTabRow(onlyNovel))
    }

    @Test
    fun selectedChaptersTabShowsNovelInTwoPanesAndComesBackWhenFolded() {
        val two = logic.tabs(hasReviews = false, hasRelated = false, hasChapters = true, twoPane = true)
        val one = logic.tabs(hasReviews = false, hasRelated = false, hasChapters = true, twoPane = false)
        assertEquals(ResultTab.Novel, logic.shownTab(ResultTab.Chapters, two))
        assertEquals(ResultTab.Chapters, logic.shownTab(ResultTab.Chapters, one))
        assertEquals(ResultTab.Novel, logic.shownTab(ResultTab.Reviews, one)) // source has no reviews
    }

    // ---------------- stats ----------------

    @Test
    fun cellsWithoutDataAreHidden() {
        assertEquals(emptyList<StatCell>(), logic.statCells(null, null, null, null))
        assertEquals(listOf(StatCell.Chapters(12)), logic.statCells(null, null, null, 12))
        assertEquals(
            listOf(StatCell.Views(1000), StatCell.Rating(800, 42), StatCell.Chapters(3)),
            logic.statCells(1000, 800, 42, 3)
        )
        assertEquals(listOf(StatCell.Rating(800, null)), logic.statCells(0, 800, 0, 0))
        assertEquals(listOf(StatCell.Votes(7)), logic.statCells(null, null, 7, null))
    }

    // ---------------- downloads ----------------

    @Test
    fun downloadMenuFollowsTheOldPage() {
        assertEquals(listOf(Download, DownloadFrom), logic.downloadActions(DownloadState.Nothing, 0, 0, 10))
        assertEquals(listOf(Pause, Stop, Delete), logic.downloadActions(DownloadState.IsDownloading, 3, 3, 10))
        assertEquals(listOf(Resume, Stop, Delete), logic.downloadActions(DownloadState.IsPaused, 3, 3, 10))
        assertEquals(listOf(Delete), logic.downloadActions(DownloadState.IsDone, 10, 10, 10))
        assertEquals(listOf(DownloadFrom, Delete), logic.downloadActions(DownloadState.IsDone, 10, 4, 10))
        assertEquals(listOf(Redownload, DownloadFrom, Delete), logic.downloadActions(DownloadState.IsFailed, 3, 3, 10))
        assertEquals(emptyList<DownloadMenuAction>(), logic.downloadActions(DownloadState.IsPending, 0, 0, 10))
    }

    @Test
    fun tapOnTheDownloadButton() {
        assertEquals(DownloadClick.Toggle, logic.downloadClick(listOf(Download, DownloadFrom)))
        assertEquals(DownloadClick.Toggle, logic.downloadClick(listOf(Pause, Stop, Delete)))
        assertEquals(DownloadClick.Run(Delete), logic.downloadClick(listOf(Delete)))
        assertEquals(DownloadClick.Menu(listOf(Resume, Stop, Delete)), logic.downloadClick(listOf(Resume, Stop, Delete)))
        assertEquals(DownloadClick.Toggle, logic.downloadClick(emptyList()))
    }

    @Test
    fun downloadButtonLook() {
        assertEquals(R.string.download, logic.downloadButton(DownloadState.Nothing, 0, 10).label)
        assertEquals(R.string.manage, logic.downloadButton(DownloadState.Nothing, 10, 10).label)
        assertEquals(R.string.pause, logic.downloadButton(DownloadState.IsDownloading, 3, 10).label)
        assertFalse(logic.downloadButton(DownloadState.Nothing, 0, 0).enabled)
    }

    // ---------------- chapters ----------------

    private val chapters = (0 until 5).map { ImmutableChapterData(name = "Chapter ${it + 1} 第${it + 1}章", url = "u$it", index = it) }

    private fun names(list: List<ImmutableChapterData>) = list.map { it.index }

    @Test
    fun filterKeepsAChapterWhenAnyCheckedSwitchMatches() {
        val readAt = mapOf(0 to 100L, 2 to 300L)
        val all = ChapterFilter(read = true, unread = true)
        assertEquals(listOf(0, 1, 2, 3, 4), names(logic.filterChapters(chapters, readAt, null, all, CHAPTER_SORT)))
        assertEquals(listOf(1, 3, 4), names(logic.filterChapters(chapters, readAt, null, ChapterFilter(read = false), CHAPTER_SORT)))
        assertEquals(listOf(0, 2), names(logic.filterChapters(chapters, readAt, null, ChapterFilter(unread = false), CHAPTER_SORT)))
        // downloaded only: the first 2 chapters are downloaded
        val downloaded = ChapterFilter(read = false, unread = false, downloaded = true)
        assertEquals(listOf(0, 1), names(logic.filterChapters(chapters, readAt, 2, downloaded, CHAPTER_SORT)))
        assertEquals(emptyList<Int>(), names(logic.filterChapters(chapters, readAt, null, downloaded, CHAPTER_SORT)))
    }

    @Test
    fun sortAndSearch() {
        val readAt = mapOf(0 to 100L, 2 to 300L)
        val all = ChapterFilter()
        assertEquals(listOf(4, 3, 2, 1, 0), names(logic.filterChapters(chapters, readAt, null, all, REVERSE_CHAPTER_SORT)))
        assertEquals(listOf(0, 2, 1, 3, 4), names(logic.filterChapters(chapters, readAt, null, all, LAST_ACCES_SORT)))
        assertEquals(listOf(1, 3, 4, 2, 0), names(logic.filterChapters(chapters, readAt, null, all, REVERSE_LAST_ACCES_SORT)))
        assertEquals(listOf(2), names(logic.filterChapters(chapters, readAt, null, all, CHAPTER_SORT, query = "第3章")))
        assertEquals(listOf(3), names(logic.filterChapters(chapters, readAt, null, all, CHAPTER_SORT, query = "4")))
        assertEquals(listOf(0, 1, 2, 3, 4), names(logic.filterChapters(chapters, readAt, null, all, CHAPTER_SORT, query = "chapter")))
    }

    @Test
    fun chipsComeFromTheMergedStatuses() {
        val statuses = ChapterStatusSource.merge(translated = setOf(0, 1, 2), stale = setOf(1), queued = setOf(2, 3))
        assertEquals(ChapterTranslationStatus.TRANSLATED, logic.chipFor(0, statuses))
        assertEquals(ChapterTranslationStatus.STALE, logic.chipFor(1, statuses))
        assertEquals(ChapterTranslationStatus.QUEUED, logic.chipFor(2, statuses))
        assertEquals(ChapterTranslationStatus.QUEUED, logic.chipFor(3, statuses))
        assertEquals(ChapterTranslationStatus.NONE, logic.chipFor(4, statuses))
    }

    // ---------------- translate button ----------------

    @Test
    fun translateButtonOnlyBusyForThisNovelsRunningJob() {
        val running = TranslationProgress(current = 2, total = 5, currentChapterName = "c")
        assertEquals(TranslateButtonState(), logic.translateButton(null, isForThisNovel = true))
        assertEquals(TranslateButtonState(), logic.translateButton(running, isForThisNovel = false))
        assertEquals(TranslateButtonState(false, "2/5"), logic.translateButton(running, isForThisNovel = true))
        assertEquals(TranslateButtonState(), logic.translateButton(running.copy(completed = true), isForThisNovel = true))
        assertEquals(TranslateButtonState(), logic.translateButton(running.copy(error = "x"), isForThisNovel = true))
        assertEquals(
            TranslateButtonState(false, "Analyzing novel… 1/3"),
            logic.translateButton(running.copy(analyzing = true, currentChapterName = "Analyzing novel… 1/3"), true)
        )
        assertEquals(
            TranslateButtonState(false, ResultPageLogic.STARTING),
            logic.translateButton(TranslationProgress(0, 0, ""), true)
        )
    }

    // ---------------- metadata ----------------

    @Test
    fun manualLanguageWinsOverDetection() {
        assertEquals(SourceLanguageLabel("ja", manual = true), logic.sourceLanguageLabel("zh", "ja"))
        assertEquals(SourceLanguageLabel("zh", manual = false), logic.sourceLanguageLabel("zh", null))
        assertEquals(SourceLanguageLabel(null, manual = false), logic.sourceLanguageLabel(null, null))
    }

    @Test
    fun translatedMetadataAndShowOriginal() {
        val labels = mapOf("第三章 出发" to "Chapter 3: Departure", "玄幻" to "Xuanhuan")
        val translated = logic.metadataDisplay(
            name = "星河旅人", synopsis = "简介", latest = "第三章 出发", tags = listOf("玄幻", "Fantasy (판타지)"),
            translatedTitle = "Star River Traveller", translatedSynopsis = "Synopsis", labels = labels, showOriginal = false,
        )
        assertEquals("Star River Traveller", translated.title)
        assertEquals("星河旅人", translated.originalTitle)
        assertEquals("Synopsis", translated.synopsis)
        assertEquals("Chapter 3: Departure", translated.latest)
        assertEquals(listOf("Xuanhuan", "Fantasy (판타지)"), translated.tags)
        assertTrue(translated.canToggle)

        val original = logic.metadataDisplay(
            "星河旅人", "简介", "第三章 出发", listOf("玄幻"), "Star River Traveller", "Synopsis", labels, showOriginal = true,
        )
        assertEquals("星河旅人", original.title)
        assertNull(original.originalTitle)
        assertEquals("简介", original.synopsis)
        assertEquals("第三章 出发", original.latest)
        assertEquals(listOf("玄幻"), original.tags)
        assertTrue(original.canToggle)
    }

    @Test
    fun onlyLabelsTranslatedStillOffersTheToggle() {
        val d = logic.metadataDisplay("Name", null, "第三章", emptyList(), null, null, mapOf("第三章" to "Chapter 3"), false)
        assertEquals("Name", d.title)
        assertNull(d.originalTitle)
        assertEquals("Chapter 3", d.latest)
        assertTrue(d.canToggle)

        val none = logic.metadataDisplay("Name", null, "Chapter 3", emptyList(), null, null, emptyMap(), false)
        assertFalse(none.canToggle)
    }

    // ---------------- chapter names ----------------

    @Test
    fun chapterRowShowsTheTranslationWithTheOriginalUnderIt() {
        val names = mapOf("第1章 开始" to "Chapter 1: The Beginning")
        assertEquals("Chapter 1: The Beginning" to "第1章 开始", logic.chapterRowName("第1章 开始", names, translate = true, showOriginal = false))
        assertEquals("第1章 开始" to null, logic.chapterRowName("第1章 开始", names, translate = true, showOriginal = true))
        assertEquals("第1章 开始" to null, logic.chapterRowName("第1章 开始", names, translate = false, showOriginal = false))
        assertEquals("第2章 夜" to null, logic.chapterRowName("第2章 夜", names, translate = true, showOriginal = false))
        // Names are looked up trimmed, as they are requested.
        assertEquals("Chapter 1: The Beginning" to " 第1章 开始", logic.chapterRowName(" 第1章 开始", names, translate = true, showOriginal = false))
    }

    @Test
    fun chapterSearchAlsoMatchesTranslatedNames() {
        val all = ChapterFilter(read = true, unread = true)
        val translated = mapOf("Chapter 3 第3章" to "Chapter 3: Rainy Night")
        assertEquals(listOf(2), names(logic.filterChapters(chapters, emptyMap(), null, all, CHAPTER_SORT, query = "rainy", translatedNames = translated)))
        assertEquals(emptyList<Int>(), names(logic.filterChapters(chapters, emptyMap(), null, all, CHAPTER_SORT, query = "rainy")))
    }

    @Test
    fun translatedChapterNamesAloneOfferShowOriginal() {
        val display = logic.metadataDisplay("Name", null, null, emptyList(), null, null, emptyMap(), false, chapterNamesTranslated = true)
        assertTrue(display.canToggle)
    }

    @Test
    fun reviewsToTopAppearsAfterAFewReviews() {
        assertFalse(logic.showScrollToTop(0))
        assertFalse(logic.showScrollToTop(2))
        assertTrue(logic.showScrollToTop(3))
        assertTrue(logic.showScrollToTop(40))
    }
}
