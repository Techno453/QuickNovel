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
    fun bracketedAsideToReadersRunsToItsClosingBracket() {
        val chapter = """
            萧炎缓缓行去…
            （土豆新书开张，请各位书友点击收藏，别忘记票票哦
            剑圣便将完美结局！）
            第二天，萧炎早早起床。
            （他心中暗想：这测验魔石碑，当真可恶。）
            【系统更新完成，获得收藏品一件】
        """.trimIndent()
        val split = AuthorNotes.split(chapter)
        assertEquals(
            "萧炎缓缓行去…\n\n第二天，萧炎早早起床。\n\n（他心中暗想：这测验魔石碑，当真可恶。）\n\n【系统更新完成，获得收藏品一件】",
            split.story,
        )
        assertTrue(split.notes.contains("土豆") && split.notes.contains("剑圣"))
    }

    @Test
    fun traditionalChineseNotesRewardThanksAndBookPlugs() {
        assertTrue(AuthorNotes.startsBlock("作者的話：今天兩更。"))
        assertTrue(AuthorNotes.isNoteLine("求推薦票，求訂閱！"))
        assertTrue(AuthorNotes.isNoteLine("感謝沐風之歌的盟主打賞！"))
        assertTrue(AuthorNotes.isNoteLine("感謝不離南山的盟主！"))
        assertTrue(AuthorNotes.isNoteLine("今天為佐伯伽椰子加更。"))
        assertTrue(AuthorNotes.isNoteLine("推薦一本朋友的新書《天啟預報》，非常好看。"))
        // In-story uses of the same words are not notes.
        assertFalse(AuthorNotes.isNoteLine("林风感谢武林盟主的救命之恩。"))
        assertFalse(AuthorNotes.isNoteLine("盟主站在高台上，俯视众人。"))
        assertFalse(AuthorNotes.isNoteLine("他推开门，桌上放着一本《论语》。"))
    }

    @Test
    fun aThanksLineEndingInAColonTakesTheUsernameListAfterIt() {
        val chapter = """
            伊恩走出屋子。

            感謝以下書友的打賞：
            天堂之T羽
            奇跡革新
            汪喵之蒼
            第二天清晨，伊恩又來到了碼頭，海風帶著鹹味吹過港口。
        """.trimIndent()
        val split = AuthorNotes.split(chapter)
        assertEquals("伊恩走出屋子。\n\n第二天清晨，伊恩又來到了碼頭，海風帶著鹹味吹過港口。", split.story)
        assertTrue(split.notes.contains("奇跡革新") && split.notes.contains("汪喵之蒼"))
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
