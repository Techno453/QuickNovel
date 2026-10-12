package com.lagradost.quicknovel.ui.result

import com.lagradost.quicknovel.ui.result.ChapterTranslationStatus.QUEUED
import com.lagradost.quicknovel.ui.result.ChapterTranslationStatus.STALE
import com.lagradost.quicknovel.ui.result.ChapterTranslationStatus.TRANSLATED
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
    fun nothingKnown() {
        assertEquals(emptyMap<Int, ChapterTranslationStatus>(), ChapterStatusSource.merge(emptySet(), emptySet(), emptySet()))
    }
}
