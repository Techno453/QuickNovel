package com.lagradost.quicknovel.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewNoticeTest {
    private fun line(
        translatable: Boolean = true,
        translated: Boolean = false,
        withoutGlossary: Boolean = false,
        deferred: Boolean = false,
        original: OriginalNotice? = null,
    ) = PreviewNotice.lineFor(translatable, translated, withoutGlossary, deferred, original)

    @Test
    fun previewTranslationSaysItHasNoGlossary() {
        assertEquals(PreviewNotice.Line(PreviewNotice.WITHOUT_GLOSSARY), line(translated = true, withoutGlossary = true))
    }

    @Test
    fun storedTranslationWithTheGlossaryIsJustAPreview() {
        assertEquals(PreviewNotice.Line(PreviewNotice.PREVIEW), line(translated = true, withoutGlossary = false))
    }

    @Test
    fun originalCarriesItsReason() {
        assertEquals(
            PreviewNotice.Line(PreviewNotice.WITH_REASON, OriginalNotice.NO_API_KEY),
            line(original = OriginalNotice.NO_API_KEY),
        )
        assertEquals(
            PreviewNotice.Line(PreviewNotice.WITH_REASON, OriginalNotice.TRANSLATION_OFF),
            line(original = OriginalNotice.TRANSLATION_OFF),
        )
        // Show original / a failed translation: no reason, still a preview.
        assertEquals(PreviewNotice.Line(PreviewNotice.PREVIEW), line())
    }

    @Test
    fun preloadedChapterSaysWhenItIsTranslated() {
        assertEquals(PreviewNotice.Line(PreviewNotice.NOT_YET), line(deferred = true))
        // a stored translation is served at once, deferred or not
        assertEquals(PreviewNotice.Line(PreviewNotice.WITHOUT_GLOSSARY), line(translated = true, withoutGlossary = true, deferred = true))
    }

    @Test
    fun onlyTheOpenChapterIsTranslated() {
        assertEquals(false, PreviewChapters.defers(wouldTranslate = true, index = 4, openChapter = 4))
        assertEquals(true, PreviewChapters.defers(wouldTranslate = true, index = 5, openChapter = 4))
        assertEquals(true, PreviewChapters.defers(wouldTranslate = true, index = 3, openChapter = 4))
        // switch off / no key: the original notice explains it, nothing waits for opening
        assertEquals(false, PreviewChapters.defers(wouldTranslate = false, index = 5, openChapter = 4))
        // no open chapter yet
        assertEquals(false, PreviewChapters.defers(wouldTranslate = true, index = 5, openChapter = -1))
    }

    @Test
    fun booksThatNeverTranslateGetNoLine() {
        assertNull(line(translatable = false))
        assertNull(line(translatable = false, original = OriginalNotice.NO_API_KEY))
    }
}
