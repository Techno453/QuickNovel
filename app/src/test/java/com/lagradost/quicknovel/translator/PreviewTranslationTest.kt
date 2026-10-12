package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.TranslatedChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the result page's Preview (one chapter at a time, no glossary). */
class PreviewTranslationTest {
    @Test
    fun previewOptionsDropTheGlossaryButKeepTheQualityChecks() {
        val base = TranslationOptions(strictMode = true, autoAddTerms = true, carryStoryContext = true, promptProfile = "terse")
        val preview = TranslationOptions.preview(base)
        assertFalse(preview.autoAddTerms)
        assertFalse(preview.carryStoryContext)
        assertFalse(preview.collectTerms)
        // Same engine behaviour otherwise: strict-mode retries and the prompt profile are kept.
        assertTrue(preview.strictMode)
        assertEquals("terse", preview.promptProfile)
        assertEquals(base.autoAddMinConfidence, preview.autoAddMinConfidence)
        assertFalse(TranslationOptions.preview(base.copy(strictMode = false)).strictMode)
    }

    @Test
    fun normalOptionsStillCollectTerms() {
        assertTrue(TranslationOptions().collectTerms)
    }

    @Test
    fun previewInputsHaveNoGlossaryAndNoStoryContext() {
        val style = TranslationStyle.defaultFor("zh")
        val inputs = StoryBibleRepository.ChapterInputs.preview(style, TranslationOptions())
        assertTrue(inputs.bible.isEmpty())
        assertEquals(StoryContext(style = style), inputs.context)
        assertEquals("", inputs.context.premise)
        assertEquals("", inputs.context.previousChapterSummary)
        assertEquals("", inputs.context.previousEnglishTail)
        assertTrue(inputs.context.recentCharacters.isEmpty())
        assertEquals(TranslationOptions.preview(), inputs.options)
    }

    @Test
    fun previewNeverRunsTheAnalysis() {
        assertFalse(ChapterTranslationRepository.analyzesFirst(preview = true, canFetchChapters = true))
        assertFalse(ChapterTranslationRepository.analyzesFirst(preview = true, canFetchChapters = false))
        assertTrue(ChapterTranslationRepository.analyzesFirst(preview = false, canFetchChapters = true))
        assertFalse(ChapterTranslationRepository.analyzesFirst(preview = false, canFetchChapters = false))
    }

    @Test
    fun previewRowsAreMarkedByTheColumn() {
        fun row(preview: Boolean, violations: Int? = 0) =
            TranslatedChapter("n", 0, "<p>x</p>", glossaryViolations = violations, preview = preview)
        assertTrue(ChapterTranslationRepository.isPreviewTranslation(row(preview = true)))
        assertFalse(ChapterTranslationRepository.isPreviewTranslation(row(preview = false)))
        // Rows from before DB v3 (no glossary check recorded) are not previews.
        assertFalse(ChapterTranslationRepository.isPreviewTranslation(row(preview = false, violations = null)))
        assertFalse(ChapterTranslationRepository.isPreviewTranslation(null))
    }

    @Test
    fun previewRowsAreServedOnlyInsideAPreview() {
        val previewRow = TranslatedChapter("n", 0, "<p>p</p>", preview = true)
        val normalRow = TranslatedChapter("n", 0, "<p>n</p>")
        assertEquals(null, ChapterTranslationRepository.servable(previewRow))
        assertEquals(previewRow, ChapterTranslationRepository.servable(previewRow, preview = true))
        assertEquals(normalRow, ChapterTranslationRepository.servable(normalRow))
        assertEquals(normalRow, ChapterTranslationRepository.servable(normalRow, preview = true))
        assertEquals(null, ChapterTranslationRepository.servable(null, preview = true))
    }

    @Test
    fun theRowOfAPreviewIsFlaggedAndANormalOneIsNot() {
        val result = TranslationResult(englishText = "<p>x</p>", pendingTerms = emptyList(), chunkCount = 1, glossaryViolations = 2)
        val previewRow = StoryBibleRepository.chapterRow("n", 3, result, "m", "zh", "h", preview = true)
        assertTrue(previewRow.preview)
        assertEquals(null, previewRow.glossaryViolations)
        val normalRow = StoryBibleRepository.chapterRow("n", 3, result, "m", "zh", "h", preview = false)
        assertFalse(normalRow.preview)
        assertEquals(2, normalRow.glossaryViolations)
        assertEquals("h", normalRow.sourceHash)
    }

    /** In-memory translated_chapters for the repository's decisions. */
    private class FakeStore : ChapterTranslationRepository.ChapterStore {
        val rows = HashMap<Pair<String, Int>, TranslatedChapter>()
        override suspend fun entry(novelId: String, chapterIndex: Int) = rows[novelId to chapterIndex]
        override suspend fun markSourceState(novelId: String, chapterIndex: Int, hash: String, stale: Boolean) {
            rows[novelId to chapterIndex]?.let { rows[novelId to chapterIndex] = it.copy(sourceHash = hash, stale = stale) }
        }
    }

    private val source = "<p>夜里下起了雨。</p>"

    /** Runs the core like the repository does: [translate] stores a row flagged like [preview]. */
    private fun run(store: FakeStore, preview: Boolean, events: MutableList<String>): String? = runBlocking {
        ChapterTranslationRepository.translateIfMissingCore(
            store, "n", 0, retranslateStale = false,
            fetchSource = { events += "fetch"; source },
            beforeTranslate = { events += "analyze" },
            preview = preview,
        ) { _, hash ->
            val text = if (preview) "<p>PREVIEW</p>" else "<p>FULL</p>"
            store.rows["n" to 0] = TranslatedChapter("n", 0, text, sourceHash = hash, preview = preview)
            events += "translate"
            text
        }
    }

    @Test
    fun theNormalPathRetranslatesAPreviewRowAndStoresANormalRow() {
        val store = FakeStore()
        // A previewed chapter, with the same source hash the normal path will compute.
        store.rows["n" to 0] = TranslatedChapter("n", 0, "<p>PREVIEW</p>", sourceHash = SourceHash.of(source), preview = true)
        val events = mutableListOf<String>()
        // Not served, not kept by the hash shortcut: the full path runs (analysis first) and replaces it.
        assertEquals("<p>FULL</p>", run(store, preview = false, events))
        assertEquals(listOf("fetch", "analyze", "translate"), events)
        assertFalse(store.rows["n" to 0]!!.preview)
        // Served from now on, inside and outside a preview, never paid again.
        events.clear()
        assertEquals("<p>FULL</p>", run(store, preview = false, events))
        assertEquals("<p>FULL</p>", run(store, preview = true, events))
        assertTrue(events.isEmpty())
    }

    @Test
    fun insideAPreviewAStoredPreviewRowIsServedAndNeverPaidTwice() {
        val store = FakeStore()
        val events = mutableListOf<String>()
        assertEquals("<p>PREVIEW</p>", run(store, preview = true, events))
        assertTrue(store.rows["n" to 0]!!.preview)
        events.clear()
        assertEquals("<p>PREVIEW</p>", run(store, preview = true, events))
        assertTrue(events.isEmpty())
    }

    @Test
    fun previewRowsAreNeverMarkedStale() = runBlocking {
        val store = FakeStore()
        store.rows["n" to 0] = TranslatedChapter("n", 0, "<p>PREVIEW</p>", sourceHash = "old", preview = true)
        assertEquals(SourceHash.Check.NO_TRANSLATION, ChapterTranslationRepository.checkSourceHashCore(store, "n", 0, source))
        assertFalse(store.rows["n" to 0]!!.stale)
    }
}
