package com.lagradost.quicknovel.imported

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedLibraryCardTest {
    @Test
    fun recordCountWinsOverTheSingleDownloadChapter() {
        assertEquals(42L, ImportedLibraryCard.chapterCount(42, 1L))
        assertEquals(1L, ImportedLibraryCard.chapterCount(1, 1L))
    }

    @Test
    fun withoutAUsableRecordTheDownloadTotalStays() {
        assertEquals(1L, ImportedLibraryCard.chapterCount(null, 1L))
        assertEquals("an empty plan is not a count", 1L, ImportedLibraryCard.chapterCount(0, 1L))
        assertNull(ImportedLibraryCard.chapterCount(null, null))
    }

    @Test
    fun coverColourIsDeterministicAndFromThePalette() {
        val id = ImportedBookId.of("雨の図書館", null)
        val colour = ImportedLibraryCard.coverColor(id)
        assertEquals(colour, ImportedLibraryCard.coverColor(id))
        assertEquals(colour, ImportedLibraryCard.coverColor(ImportedBookId.of("雨の図書館", null)))
        assertTrue(colour in ImportedLibraryCard.PALETTE)
    }

    @Test
    fun differentBooksSpreadOverThePalette() {
        val colours = (1..200).map { ImportedLibraryCard.coverColor(ImportedBookId.of("Book $it", "Author")) }.toSet()
        assertTrue("used ${colours.size} of ${ImportedLibraryCard.PALETTE.size}", colours.size >= ImportedLibraryCard.PALETTE.size - 1)
    }

    @Test
    fun everyPaletteColourKeepsTheTitleReadable() {
        for (c in ImportedLibraryCard.PALETTE) {
            val ratio = ImportedLibraryCard.contrast(c, ImportedLibraryCard.TEXT_COLOR)
            assertTrue("0x%08X: %.2f".format(c, ratio), ratio >= 4.5)
        }
    }
}
