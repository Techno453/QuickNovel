package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.util.GoogleTranslate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTranslateSplitTest {

    @Test
    fun shortTextStaysWhole() {
        assertEquals(listOf("hello"), GoogleTranslate.splitForRequest("hello", 4500))
    }

    @Test
    fun cutsOnParagraphsAndRoundTrips() {
        val para = "这是一个很长的段落。".repeat(30) // 300 chars
        val text = List(40) { para }.joinToString("\n") // ~12k chars
        val pieces = GoogleTranslate.splitForRequest(text, 4500)
        assertTrue(pieces.size >= 3)
        assertTrue(pieces.all { it.length <= 4500 })
        assertTrue(pieces.dropLast(1).all { it.endsWith("\n") })
        assertEquals(text, pieces.joinToString(""))
    }

    @Test
    fun handlesSingleHugeParagraph() {
        val text = "一二三四五六七八九十。".repeat(1000)
        val pieces = GoogleTranslate.splitForRequest(text, 4500)
        assertTrue(pieces.all { it.length <= 4500 })
        assertTrue(pieces.dropLast(1).all { it.endsWith("。") })
        assertEquals(text, pieces.joinToString(""))
    }
}
