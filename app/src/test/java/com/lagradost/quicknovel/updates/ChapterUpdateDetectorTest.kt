package com.lagradost.quicknovel.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChapterUpdateDetectorTest {
    private val now = 1_000_000L

    private fun chapters(range: IntRange, base: String = "https://site.com/novel/ch-") =
        range.map { ChapterRef("Chapter $it", "$base$it") }

    private fun checked(count: Int, newChapters: Int = 0) = NovelUpdateState(
        id = 1,
        lastKnownChapterCount = count,
        lastChapterUrl = "https://site.com/novel/ch-$count",
        lastChapterName = "Chapter $count",
        lastCheckedAt = 10L,
        newChapters = newChapters,
        lastNewChaptersAt = 5L,
    )

    @Test
    fun firstCheckWithoutHintIsASilentBaseline() {
        val out = ChapterUpdateDetector.onSuccess(NovelUpdateState(id = 1), chapters(1..50), now)
        assertEquals(ChangeKind.Baseline, out.kind)
        assertEquals(0, out.added)
        assertEquals(50, out.state.lastKnownChapterCount)
        assertEquals("https://site.com/novel/ch-50", out.state.lastChapterUrl)
        assertEquals("Chapter 50", out.state.lastChapterName)
        assertEquals(now, out.state.lastCheckedAt)
        assertEquals(0, out.state.newChapters)
        assertNull(out.state.lastNewChaptersAt)
    }

    @Test
    fun firstCheckUsesTheLibraryCountAsBaseline() {
        val out = ChapterUpdateDetector.onSuccess(
            NovelUpdateState(id = 1), chapters(1..53), now, baselineHint = 50
        )
        assertEquals(ChangeKind.NewChapters, out.kind)
        assertEquals(3, out.added)
        assertEquals(3, out.state.newChapters)
        assertEquals(now, out.state.lastNewChaptersAt)
    }

    @Test
    fun firstCheckIgnoresAHintThatIsNotBelowTheList() {
        val same = ChapterUpdateDetector.onSuccess(NovelUpdateState(id = 1), chapters(1..50), now, 50)
        assertEquals(ChangeKind.Baseline, same.kind)
        val larger = ChapterUpdateDetector.onSuccess(NovelUpdateState(id = 1), chapters(1..50), now, 60)
        assertEquals(0, larger.added)
        val zero = ChapterUpdateDetector.onSuccess(NovelUpdateState(id = 1), chapters(1..50), now, 0)
        assertEquals(0, zero.added)
    }

    @Test
    fun unchangedListOnlyUpdatesTheCheckTime() {
        val out = ChapterUpdateDetector.onSuccess(checked(50, newChapters = 2), chapters(1..50), now)
        assertEquals(ChangeKind.Unchanged, out.kind)
        assertEquals(0, out.added)
        assertEquals(2, out.state.newChapters)
        assertEquals(5L, out.state.lastNewChaptersAt)
        assertEquals(now, out.state.lastCheckedAt)
    }

    @Test
    fun newChaptersAddToTheUnseenCount() {
        val out = ChapterUpdateDetector.onSuccess(checked(50, newChapters = 2), chapters(1..54), now)
        assertEquals(ChangeKind.NewChapters, out.kind)
        assertEquals(4, out.added)
        assertEquals(6, out.state.newChapters)
        assertEquals(54, out.state.lastKnownChapterCount)
        assertEquals("https://site.com/novel/ch-54", out.state.lastChapterUrl)
        assertEquals(now, out.state.lastNewChaptersAt)
    }

    @Test
    fun slidingWindowSitesAreCountedFromTheLastKnownChapter() {
        // The site only lists the latest 20 chapters: same count, 3 new at the end
        val out = ChapterUpdateDetector.onSuccess(checked(50), chapters(34..53), now)
        assertEquals(ChangeKind.NewChapters, out.kind)
        assertEquals(3, out.added)
        assertEquals(20, out.state.lastKnownChapterCount)
    }

    @Test
    fun olderChaptersInsertedBeforeTheLastOneAreNotNew() {
        val list = listOf(ChapterRef("Prologue", "https://site.com/novel/prologue")) + chapters(1..50)
        val out = ChapterUpdateDetector.onSuccess(checked(50), list, now)
        assertEquals(0, out.added)
        assertEquals(ChangeKind.ListChanged, out.kind)
        assertEquals(51, out.state.lastKnownChapterCount)
    }

    @Test
    fun urlComparisonIgnoresSchemeWwwTrailingSlashAndCase() {
        val list = chapters(1..52, base = "http://www.SITE.com/novel/ch-").map {
            it.copy(url = it.url + "/")
        }
        val out = ChapterUpdateDetector.onSuccess(checked(50), list, now)
        assertEquals(2, out.added)
    }

    @Test
    fun unknownLastUrlFallsBackToTheCount() {
        // The site changed its url scheme: the old last url is gone, count grew by 2
        val out = ChapterUpdateDetector.onSuccess(
            checked(50), chapters(1..52, base = "https://site.com/read/"), now
        )
        assertEquals(2, out.added)
        assertEquals(ChangeKind.NewChapters, out.kind)
    }

    @Test
    fun replacedLastChapterWithTheSameCountIsAListChange() {
        val list = chapters(1..49) + ChapterRef("Chapter 50 (rewrite)", "https://site.com/novel/ch-50-v2")
        val out = ChapterUpdateDetector.onSuccess(checked(50), list, now)
        assertEquals(0, out.added)
        assertEquals(ChangeKind.ListChanged, out.kind)
        assertEquals("https://site.com/novel/ch-50-v2", out.state.lastChapterUrl)
    }

    @Test
    fun removedChaptersLowerTheBaselineWithoutNewChapters() {
        val out = ChapterUpdateDetector.onSuccess(checked(50, newChapters = 1), chapters(1..45), now)
        assertEquals(ChangeKind.ChaptersRemoved, out.kind)
        assertEquals(0, out.added)
        assertEquals(1, out.state.newChapters)
        assertEquals(45, out.state.lastKnownChapterCount)

        // and growth is measured from the new count afterwards
        val later = ChapterUpdateDetector.onSuccess(out.state, chapters(1..47), now + 1)
        assertEquals(2, later.added)
    }

    @Test
    fun storedCountWithoutUrlUsesTheCount() {
        val stored = NovelUpdateState(id = 1, lastKnownChapterCount = 10)
        assertEquals(ChangeKind.Unchanged, ChapterUpdateDetector.onSuccess(stored, chapters(1..10), now).kind)
        assertEquals(3, ChapterUpdateDetector.onSuccess(stored, chapters(1..13), now).added)
    }

    @Test
    fun emptyListKeepsTheStoredStateAndNotesAnError() {
        val stored = checked(50, newChapters = 2)
        val out = ChapterUpdateDetector.onSuccess(stored, emptyList(), now)
        assertEquals(ChangeKind.EmptyList, out.kind)
        assertEquals(0, out.added)
        assertEquals(50, out.state.lastKnownChapterCount)
        assertEquals(10L, out.state.lastCheckedAt)
        assertEquals(ChapterUpdateDetector.EMPTY_LIST_ERROR, out.state.lastError)
        assertEquals(now, out.state.lastErrorAt)
    }

    @Test
    fun successClearsAnEarlierError() {
        val failed = ChapterUpdateDetector.onFailure(checked(50), "Connection Timeout", now)
        assertEquals("Connection Timeout", failed.lastError)
        assertEquals(10L, failed.lastCheckedAt)
        assertEquals(50, failed.lastKnownChapterCount)

        val out = ChapterUpdateDetector.onSuccess(failed, chapters(1..50), now + 1)
        assertNull(out.state.lastError)
        assertNull(out.state.lastErrorAt)
    }

    @Test
    fun blankErrorGetsAMessage() {
        assertEquals("Unknown error", ChapterUpdateDetector.onFailure(checked(1), "  ", now).lastError)
    }

    @Test
    fun markSeenClearsTheCountOnly() {
        val seen = ChapterUpdateDetector.markSeen(checked(50, newChapters = 4))
        assertEquals(0, seen.newChapters)
        assertEquals(50, seen.lastKnownChapterCount)
        assertEquals(5L, seen.lastNewChaptersAt)

        val already = checked(50)
        assertSame(already, ChapterUpdateDetector.markSeen(already))
    }
}
