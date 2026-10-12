package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.TranslatedChapter
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
    fun previewRowsAreTheOnesNeverCheckedAgainstAGlossary() {
        fun row(violations: Int?) = TranslatedChapter("n", 0, "<p>x</p>", glossaryViolations = violations)
        assertTrue(ChapterTranslationRepository.isPreviewTranslation(row(null)))
        assertFalse(ChapterTranslationRepository.isPreviewTranslation(row(0)))
        assertFalse(ChapterTranslationRepository.isPreviewTranslation(row(3)))
        assertFalse(ChapterTranslationRepository.isPreviewTranslation(null))
    }
}
