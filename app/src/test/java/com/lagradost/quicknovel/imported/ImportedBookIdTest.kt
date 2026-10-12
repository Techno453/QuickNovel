package com.lagradost.quicknovel.imported

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedBookIdTest {
    @Test
    fun formatIsPrefixTitleAndDigest() {
        val id = ImportedBookId.of("月下の約束", "山田")
        assertTrue(id, Regex("""Imported/月下の約束#[0-9a-f]{8}""").matches(id))
        assertTrue(ImportedBookId.isImported(id))
        assertFalse(ImportedBookId.isImported("Kakuyomu/月下の約束"))
        assertFalse(ImportedBookId.isImported(null))
    }

    @Test
    fun stableForTheSameRecord() {
        assertEquals(ImportedBookId.of("星河旅人", "某作者"), ImportedBookId.of("星河旅人", "某作者"))
        // whitespace noise and Unicode composition do not change the id
        assertEquals(ImportedBookId.of("星河旅人", "某作者"), ImportedBookId.of("  星河旅人 ", "某作者\n"))
        assertEquals(ImportedBookId.of("Café", null), ImportedBookId.of("Café", null))
    }

    @Test
    fun sameTitleDifferentAuthorIsAnotherBook() {
        assertNotEquals(ImportedBookId.of("星河旅人", "甲"), ImportedBookId.of("星河旅人", "乙"))
        assertEquals(ImportedBookId.of("星河旅人", null), ImportedBookId.of("星河旅人", ""))
    }

    @Test
    fun resultUrlRoundTrip() {
        val id = ImportedBookId.of("별빛 정원", null)
        assertEquals(id, ImportedBookId.fromResultUrl(ImportedBookId.resultUrl(id)))
        assertNull(ImportedBookId.fromResultUrl("https://kakuyomu.jp/works/1"))
        assertNull(ImportedBookId.fromResultUrl("imported://Kakuyomu/x")) // not an imported id
        assertNull(ImportedBookId.fromResultUrl(null))
    }

    @Test
    fun contentHashTellsFilesApart() {
        val a = ImportedBookId.contentHash("第一章 风起".toByteArray())
        assertEquals(64, a.length)
        assertEquals(a, ImportedBookId.contentHash("第一章 风起".toByteArray()))
        assertNotEquals(a, ImportedBookId.contentHash("第一章 风停".toByteArray()))
    }
}
