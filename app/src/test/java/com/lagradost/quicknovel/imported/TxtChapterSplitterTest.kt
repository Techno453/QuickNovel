package com.lagradost.quicknovel.imported

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All sample text below was written for these tests. */
class TxtChapterSplitterTest {
    @Test
    fun recognisesCjkHeadings() {
        listOf(
            "第一章 风起青萍", "第十二章", "第一百零三章：归来", "第12章 夜雨", "第１２話　月の庭", "第三回 初见",
            "第5话 再会", "第二卷 山河", "　　第七章 雪落", "제1화", "제 12 화 - 별빛", "1화", "제3장 바다",
            "Chapter 4", "CHAPTER XII The Gate", "序章", "楔子", "プロローグ", "エピローグ", "番外一 春日",
            "프롤로그", "외전 1", "尾声",
        ).forEach { assertTrue(it, TxtChapterSplitter.isHeading(it)) }
    }

    @Test
    fun ordinarySentencesAreNotHeadings() {
        listOf(
            "他翻到第三章的时候，窗外的雨已经停了，街上的灯一盏一盏亮了起来。",
            "第一天", // no chapter counter word
            "彼女は静かに笑った。",
            "그는 문을 열었다.",
            "",
        ).forEach { assertFalse(it, TxtChapterSplitter.isHeading(it)) }
    }

    @Test
    fun splitsChineseTextOnHeadings() {
        val text = """
            星河旅人
            作者：某作者

            第一章 风起
            　　清晨的山路上起了薄雾。
            　　少年背着竹篓往山上走。

            第二章 夜雨
            　　夜里下起了雨。
        """.trimIndent()
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(listOf("第一章 风起", "第二章 夜雨"), chapters.map { it.title })
        assertEquals(listOf("清晨的山路上起了薄雾。", "少年背着竹篓往山上走。"), chapters[0].paragraphs)
        assertEquals(listOf("夜里下起了雨。"), chapters[1].paragraphs)
    }

    @Test
    fun keepsRealFrontMatterAsFirstChapter() {
        val text = "内容简介\n这是一个关于旅人和星河的故事，他从山村出发，一路向北，遇见了许多人，也失去了许多东西。\n" +
            "第一章 出发\n天亮了。\n第二章 渡河\n河水很急。"
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(listOf("内容简介", "第一章 出发", "第二章 渡河"), chapters.map { it.title })
    }

    @Test
    fun dropsATableOfContentsAtTheTop() {
        val text = "目录\n第一話 春\n第二話 夏\n\n第一話 春\n桜が咲いた。\n第二話 夏\n蝉が鳴いている。"
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(listOf("第一話 春", "第二話 夏"), chapters.map { it.title })
        assertEquals(listOf("桜が咲いた。"), chapters[0].paragraphs)
    }

    @Test
    fun splitsKoreanEpisodes() {
        val text = "제1화 시작\n그는 문을 열었다.\n바람이 불었다.\n제2화 바다\n파도가 높았다."
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(listOf("제1화 시작", "제2화 바다"), chapters.map { it.title })
        assertEquals(2, chapters[0].paragraphs.size)
    }

    @Test
    fun fallsBackToPartsBySize() {
        val paragraph = "雨落在屋檐上，声音很轻。".repeat(20) // 240 chars
        val text = List(60) { paragraph }.joinToString("\n") // 14,400 chars, no headings
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(listOf("Part 1", "Part 2", "Part 3"), chapters.map { it.title })
        chapters.forEach { assertTrue(it.length <= TxtChapterSplitter.TARGET_PART_CHARS) }
        assertEquals(60, chapters.sumOf { it.paragraphs.size })
    }

    @Test
    fun shortTextWithoutHeadingsIsOneChapter() {
        val chapters = TxtChapterSplitter.split("短篇\n只有一段。")
        assertEquals(1, chapters.size)
        assertEquals("短篇", chapters[0].title)
        assertEquals(listOf("短篇", "只有一段。"), chapters[0].paragraphs)
    }

    @Test
    fun aSingleHeadingIsNotEnoughToSplitOn() {
        val chapters = TxtChapterSplitter.split("第一章 唯一\n一些文字。")
        assertEquals(1, chapters.size)
    }

    @Test
    fun handlesWindowsLineEndingsAndEmptyText() {
        val chapters = TxtChapterSplitter.split("第1章 一\r\n文字一。\r\n第2章 二\r\n文字二。\r\n")
        assertEquals(listOf("第1章 一", "第2章 二"), chapters.map { it.title })
        assertTrue(TxtChapterSplitter.split("\n \n").isEmpty())
    }
}
