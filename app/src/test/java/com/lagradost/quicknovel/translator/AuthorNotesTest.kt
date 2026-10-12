package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorNotesTest {

    @Test
    fun chineseAfterwordBlockRunsToTheEndOfTheChapter() {
        val chapter = """
            萧炎看着测验魔石碑，沉默不语。

            “斗之力，三段！”

            作者的话：新书上传，土豆在四川，求收藏！

            感谢剑圣的打赏。
        """.trimIndent()
        val split = AuthorNotes.split(chapter)
        assertEquals("萧炎看着测验魔石碑，沉默不语。\n\n“斗之力，三段！”", split.story)
        assertTrue(split.notes.contains("土豆"))
        assertTrue(split.notes.contains("剑圣"))
        assertTrue(split.hasNotes)
    }

    @Test
    fun psAndVotePleasAreSingleParagraphNotes() {
        val chapter = """
            PS：今天两更，求月票！
            萧炎握紧了拳头。
            （求推荐票）
            ps: second update tonight
            萧媚冷笑。
        """.trimIndent()
        val split = AuthorNotes.split(chapter)
        assertEquals("萧炎握紧了拳头。\n\n萧媚冷笑。", split.story)
        assertEquals(3, split.notes.split("\n\n").size)
    }

    @Test
    fun japaneseAndKoreanMarkers() {
        val ja = "リーリャは剣を置いた。\n【あとがき】\n次回は来週です。作者は東京在住。"
        assertEquals("リーリャは剣を置いた。", AuthorNotes.strip(ja))

        val ko = "강인호는 웃었다.\n\n작가의 말\n오늘도 읽어 주셔서 감사합니다.\n후원해 주신 분들께 감사드립니다."
        assertEquals("강인호는 웃었다.", AuthorNotes.strip(ko))

        // The in-story word 후원자 ("sponsor") is not a donation note.
        val story = "성좌들이 후원자를 찾고 있었다.\n김독자는 고개를 들었다."
        assertEquals(story.replace("\n", "\n\n"), AuthorNotes.strip(story))
    }

    @Test
    fun markersOnlyCountNearTheStartOfAParagraph() {
        assertTrue(AuthorNotes.startsBlock("——作者的话——"))
        assertTrue(AuthorNotes.startsBlock("（作者有话说：明天请假）"))
        assertFalse(AuthorNotes.startsBlock("萧炎说：这就是那位老先生临终前的作者的话"))
        assertFalse(AuthorNotes.isNoteLine("Psychic waves rolled over the city."))
    }

    @Test
    fun textWithoutNotesIsUnchanged() {
        val text = "第一段。\n\n第二段。"
        val split = AuthorNotes.split(text)
        assertEquals(text, split.story)
        assertEquals("", split.notes)
        assertFalse(split.hasNotes)
    }
}
