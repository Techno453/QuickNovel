package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceHashTest {
    @Test
    fun markupAndWhitespaceDoNotChangeTheHash() {
        val html = "<div><p>第一章　开始</p>\n<p>  他说：“你好。”  </p><br/><p></p></div>"
        val text = "第一章 开始\n\n他说：“你好。”"
        assertEquals(SourceHash.of(text), SourceHash.of(html))
        assertEquals("第一章 开始\n他说：“你好。”", SourceHash.normalize(html))
    }

    @Test
    fun aChangedSentenceChangesTheHash() {
        assertNotEquals(SourceHash.of("他说：“你好。”"), SourceHash.of("他说：“您好。”"))
    }

    @Test
    fun hashIsSha256Hex() {
        val h = SourceHash.of("abc")
        assertEquals(64, h.length)
        // SHA-256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h)
    }

    @Test
    fun compareTreatsAnEmptyStoredHashAsUnknown() {
        val h = SourceHash.of("x")
        assertEquals(SourceHash.Check.NO_TRANSLATION, SourceHash.compare(null, h))
        assertEquals(SourceHash.Check.UNKNOWN, SourceHash.compare("", h))
        assertEquals(SourceHash.Check.MATCH, SourceHash.compare(h, h))
        assertEquals(SourceHash.Check.CHANGED, SourceHash.compare(SourceHash.of("y"), h))
    }
}
