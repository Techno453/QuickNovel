package com.lagradost.quicknovel.ui.result

import com.lagradost.quicknovel.ui.result.ChapterTranslationStatus.QUEUED
import com.lagradost.quicknovel.ui.result.ChapterTranslationStatus.STALE
import com.lagradost.quicknovel.ui.result.ChapterTranslationStatus.TRANSLATED
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterStatusSourceTest {
    @Test
    fun queuedWinsOverStaleWinsOverTranslated() {
        val map = ChapterStatusSource.merge(
            translated = setOf(0, 1, 2, 3),
            stale = setOf(2, 3),
            queued = setOf(3, 4),
        )
        assertEquals(mapOf(0 to TRANSLATED, 1 to TRANSLATED, 2 to STALE, 3 to QUEUED, 4 to QUEUED), map)
    }

    @Test
    fun previewRowsShowAsPreviewNotTranslated() {
        val map = ChapterStatusSource.merge(
            translated = setOf(0),
            stale = emptySet(),
            queued = setOf(2),
            preview = setOf(1, 2),
        )
        // a preview chapter queued for a real translation shows Queued
        assertEquals(mapOf(0 to TRANSLATED, 1 to ChapterTranslationStatus.PREVIEW, 2 to QUEUED), map)
    }

    @Test
    fun nothingKnown() {
        assertEquals(emptyMap<Int, ChapterTranslationStatus>(), ChapterStatusSource.merge(emptySet(), emptySet(), emptySet()))
    }

    /** Fake DAO / batch / read-ahead lookups; null means "throws". */
    private class FakeQueries(
        val translated: Map<String, List<Int>> = emptyMap(),
        val stale: Map<String, List<Int>>? = emptyMap(),
        val batches: Map<String, Set<Int>>? = emptyMap(),
        val readAheadNovel: String? = null,
        val readAhead: List<Int> = emptyList(),
    ) : ChapterStatusQueries {
        override suspend fun translatedChapterNums(novelId: String) = translated[novelId].orEmpty()
        override suspend fun staleChapterNums(novelId: String) =
            (stale ?: throw IllegalStateException("db closed"))[novelId].orEmpty()

        override suspend fun chaptersInUnfinishedBatches(novelId: String) =
            (batches ?: throw IllegalStateException("db closed"))[novelId].orEmpty()

        override fun readAheadPending() = readAheadNovel to readAhead
    }

    @Test
    fun staleComesFromTheStaleFlag() = runBlocking {
        val source = TranslatorChapterStatusSource(
            FakeQueries(translated = mapOf("A/x" to listOf(0, 1, 2)), stale = mapOf("A/x" to listOf(1), "B/y" to listOf(0)))
        )
        assertEquals(setOf(1), source.stale("A/x"))
        assertEquals(mapOf(0 to TRANSLATED, 1 to STALE, 2 to TRANSLATED), ChapterStatusSource.statuses(source, "A/x"))
    }

    @Test
    fun queuedIsReadAheadPlusUnfinishedBatches() = runBlocking {
        val source = TranslatorChapterStatusSource(
            FakeQueries(
                batches = mapOf("A/x" to setOf(5, 6, 7), "B/y" to setOf(1)),
                readAheadNovel = "A/x",
                readAhead = listOf(3, 4, 5),
            )
        )
        assertEquals(setOf(3, 4, 5, 6, 7), source.queued("A/x"))
    }

    @Test
    fun readAheadOfAnotherNovelIsIgnored() = runBlocking {
        val source = TranslatorChapterStatusSource(
            FakeQueries(batches = mapOf("A/x" to setOf(9)), readAheadNovel = "B/y", readAhead = listOf(1, 2))
        )
        assertEquals(setOf(9), source.queued("A/x"))
        assertEquals(setOf(1, 2), source.queued("B/y"))
    }

    @Test
    fun aFailingLookupOnlyDropsItsOwnChips() = runBlocking {
        val source = TranslatorChapterStatusSource(
            FakeQueries(
                translated = mapOf("A/x" to listOf(0, 1)),
                stale = null,
                batches = null,
                readAheadNovel = "A/x",
                readAhead = listOf(2),
            )
        )
        assertEquals(mapOf(0 to TRANSLATED, 1 to TRANSLATED, 2 to QUEUED), ChapterStatusSource.statuses(source, "A/x"))
    }

    @Test
    fun importedBookIdsWorkLikeAnyOther() = runBlocking {
        val id = "Imported/月下の約束#0123abcd"
        val source = TranslatorChapterStatusSource(
            FakeQueries(
                translated = mapOf(id to listOf(0, 1)),
                stale = mapOf(id to listOf(1)),
                batches = mapOf(id to setOf(3)),
                readAheadNovel = id,
                readAhead = listOf(2),
            )
        )
        assertEquals(mapOf(0 to TRANSLATED, 1 to STALE, 2 to QUEUED, 3 to QUEUED), ChapterStatusSource.statuses(source, id))
    }
}
