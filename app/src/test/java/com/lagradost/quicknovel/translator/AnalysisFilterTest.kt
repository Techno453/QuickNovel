package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.AnalysisFilter.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases taken from the 2026-10-11 analysis-model comparison (Luna vs sol on the bench samples,
 * app/build/translation-bench/20261011-195931/analysis).
 */
class AnalysisFilterTest {
    private fun t(
        source: String,
        english: String,
        category: String,
        confidence: Float = 0.99f,
        gender: String = "unknown",
        aliases: List<String> = emptyList(),
        proper: Boolean? = null,
        notes: String = "",
    ) = DiscoveredTerm(source, english, category, gender, notes, confidence, aliases, proper)

    private val doupoExcerpt = """
        萧炎站在测验魔石碑前，中年男子看了他一眼。测验人高声喊道：“斗之力，三段！级别：低级！”

        萧媚是斗之气七段，萧熏儿是天才。乌坦城的萧家广场上，族长叹了口气。萧家小姐们窃窃私语。

        作者的话：土豆的新书，四川的朋友们求收藏！也谢谢剑圣的打赏。
    """.trimIndent()

    @Test
    fun doupoNoiseIsRemovedAndNamesKept() {
        val terms = listOf(
            t("萧炎", "Xiao Yan", "character", 1f, "male"),
            t("萧媚", "Xiao Mei", "character", 1f, "female"),
            t("萧熏儿", "Xiao Xun'er", "character", 1f, "female"),
            t("测验人", "examiner", "character", 0.72f),
            t("中年男子", "Middle-Aged Tester", "title"),
            t("土豆", "Tudou", "character"),
            t("乌坦城", "Wutan City", "location", 1f),
            t("四川", "Sichuan", "location", 1f),
            t("萧家", "Xiao Family", "sect", 1f),
            t("斗之气", "Dou Qi", "rank", 1f),
            t("斗者", "Dou Zhe", "rank", 1f),
            t("低级", "low level", "rank", 1f),
            t("天才", "prodigy", "title"),
            t("广场", "testing square", "location", 0.98f),
            t("剑圣", "Jiansheng", "other"),
            t("测验魔石碑", "Testing Magic Stone Tablet", "artifact", 1f),
        )
        val r = AnalysisFilter.filter(terms, doupoExcerpt)
        assertEquals(
            listOf("萧炎", "萧媚", "萧熏儿", "乌坦城", "萧家", "斗之气", "斗者", "测验魔石碑"),
            r.terms.map { it.source },
        )
        assertEquals(setOf("测验人", "中年男子"), r.droppedFor(Reason.GENERIC_ROLE).map { it.source }.toSet())
        assertEquals(setOf("土豆", "四川", "剑圣"), r.droppedFor(Reason.AUTHOR_NOTE).map { it.source }.toSet())
        assertEquals(setOf("低级", "天才", "广场"), r.droppedFor(Reason.COMMON_NOUN).map { it.source }.toSet())
    }

    @Test
    fun mushokuRolesAndCommonNounsAreRemoved() {
        val terms = listOf(
            t("リーリャ", "Lilia", "character", 1f, "female"),
            t("ゼニス", "Zenith", "character", 1f, "female"),
            t("暗殺者", "Assassin", "character"),
            t("黒幕", "Mastermind", "character"),
            t("大臣", "the minister", "character", 0.98f),
            t("王女", "the princess", "character"),
            t("先輩の近衛侍女", "Senior Guard-Maid", "character", 0.96f),
            t("ロア", "Roa", "location"),
            t("王都", "Royal Capital", "location", 1f),
            t("短剣", "dagger", "artifact"),
            t("毒", "poison", "other"),
            t("麦畑", "wheat fields", "location"),
            t("道場", "dojo", "location"),
        )
        val r = AnalysisFilter.filter(terms, "")
        assertEquals(listOf("リーリャ", "ゼニス", "ロア", "王都"), r.terms.map { it.source })
        assertEquals(
            setOf("暗殺者", "黒幕", "大臣", "王女", "先輩の近衛侍女"),
            r.droppedFor(Reason.GENERIC_ROLE).map { it.source }.toSet(),
        )
        assertEquals(setOf("短剣", "毒", "麦畑", "道場"), r.droppedFor(Reason.COMMON_NOUN).map { it.source }.toSet())
    }

    @Test
    fun koreanRolesAndSingleSyllableWords() {
        val terms = listOf(
            t("강인호", "Kang Inho", "character", 0.99f, "male"),
            t("그 여자", "that woman", "character", 0.9f),
            t("신", "god", "title", 0.94f),
            t("지구 차원", "Earth dimension", "location"),
        )
        val r = AnalysisFilter.filter(terms, "")
        assertEquals(listOf("강인호", "지구 차원"), r.terms.map { it.source })
    }

    @Test
    fun koreanCommonNounsRenderedAsPlainWordsAreDropped() {
        val terms = listOf(
            t("라나엘", "Ranael", "character", 0.99f, "female"),
            t("두루마리", "scroll", "artifact", 0.95f),
            t("비둘기", "pigeon", "creature"),
            t("환생", "reincarnation", "other"),
            t("차원", "dimension", "location"),
            t("3급 차원관리신", "third-rank dimension-management god", "title"),
            t("니애미 차원", "Your-Mom Dimension", "location"), // left to the prompt
        )
        val r = AnalysisFilter.filter(terms, "")
        assertEquals(listOf("라나엘", "3급 차원관리신", "니애미 차원"), r.terms.map { it.source })
        assertTrue(AnalysisFilter.isPlainWord("swordswoman"))
        assertTrue(AnalysisFilter.isPlainWord("half-elf"))
        assertFalse(AnalysisFilter.isPlainWord("Dou Qi"))
        assertFalse(AnalysisFilter.isPlainWord("healing magic"))
        assertFalse(AnalysisFilter.isPlainWord("Scroll"))
    }

    @Test
    fun doupoBracketedAfterwordArtifactsAreDropped() {
        // The real ending of 斗破苍穹 chapter 1: a bracketed note to readers over two paragraphs.
        val chapter = """
            萧熏儿快步追了上去，与少年并肩而行…
            （土豆新书开张，请各位新老书友点击收藏，另外，别忘记票票哦，谢谢一路跟来的剑圣书友，那时候的大地震，让身在四川的土豆到处躲命
            有了你们的支持，才使得剑圣走过风雨一路，剑圣便将完美结局！）
        """.trimIndent()
        val r = AnalysisFilter.filter(
            listOf(
                t("萧熏儿", "Xiao Xun'er", "character", 1f, "female"),
                t("土豆", "Tudou", "character"),
                t("四川", "Sichuan", "location", 1f),
                t("剑圣", "Jiansheng", "other"),
            ),
            chapter,
        )
        assertEquals(listOf("萧熏儿"), r.terms.map { it.source })
        assertEquals(setOf("土豆", "四川", "剑圣"), r.droppedFor(Reason.AUTHOR_NOTE).map { it.source }.toSet())
    }

    @Test
    fun modelFlagFalseIsDropped() {
        val r = AnalysisFilter.filter(
            listOf(t("식칼", "Kitchen Knife", "artifact", proper = false), t("라나엘", "Ranael", "character", proper = true)),
            "",
        )
        assertEquals(listOf("라나엘"), r.terms.map { it.source })
        assertEquals(Reason.NOT_PROPER, r.dropped.single().reason)
    }

    @Test
    fun shortTermsSurviveWhenTheyLookProperOrAreConfidentAndCapitalised() {
        assertTrue(AnalysisFilter.isVeryShort("毒"))
        assertTrue(AnalysisFilter.isVeryShort("麦畑"))
        assertTrue(AnalysisFilter.isVeryShort("신"))
        assertTrue(AnalysisFilter.isVeryShort("차원"))
        assertFalse(AnalysisFilter.isVeryShort("비둘기"))
        assertFalse(AnalysisFilter.isVeryShort("3급"))
        assertFalse(AnalysisFilter.isVeryShort("乌坦城"))
        assertTrue(AnalysisFilter.looksProper("萧家"))
        assertTrue(AnalysisFilter.looksProper("ロア"))
        assertFalse(AnalysisFilter.looksProper("毒"))

        val r = AnalysisFilter.filter(
            listOf(
                t("魔域", "Demon Domain", "location", 0.5f), // proper suffix 域
                t("丹药", "Pill", "artifact", 0.6f),          // low confidence
                t("天火", "Heavenly Fire", "artifact", 0.95f), // confident, capitalised
            ),
            "",
        )
        assertEquals(listOf("魔域", "天火"), r.terms.map { it.source })
        assertEquals("丹药", r.droppedFor(Reason.COMMON_NOUN).single().source)
    }

    @Test
    fun charactersAreNeverDroppedForBeingShort() {
        val r = AnalysisFilter.filter(listOf(t("林", "Lin", "character", 0.5f)), "")
        assertEquals(listOf("林"), r.terms.map { it.source })
    }

    @Test
    fun authorNoteCheckIsPerChapterAndIgnoresTermsAlsoInTheStory() {
        val ch1 = "萧炎来到乌坦城。\n作者的话：乌坦城是我老家四川的一座小城。"
        val ch2 = "萧炎回到萧家。"
        val r = AnalysisFilter.filter(
            listOf(t("乌坦城", "Wutan City", "location"), t("四川", "Sichuan", "location")),
            listOf(ch1, ch2),
        )
        assertEquals(listOf("乌坦城"), r.terms.map { it.source })
        assertEquals("四川", r.droppedFor(Reason.AUTHOR_NOTE).single().source)
    }

    @Test
    fun duplicatesMergeIntoOneEntryWithAliases() {
        val r = AnalysisFilter.filter(
            listOf(
                t("萧熏儿", "Xiao Xun'er", "character", 0.95f, "female", notes = "Xiao Yan's companion"),
                t("熏儿", "xiao xunER", "character", 0.95f, aliases = listOf("熏儿妹妹")),
                t("萧炎", "Xiao Yan", "character", 0.8f),
                t("萧炎", "Xiao Yan", "character", 0.9f, "male", aliases = listOf("炎儿")),
                t("フィットア領", "Fittoa Region", "location", 0.98f),
                t("フィットア", "the Fittoa region", "location", 0.9f),
                t("罗亚", "Roa", "character"),
                t("ロア", "Roa", "location"), // different category: not merged
            ),
            "",
        )
        val bySource = r.terms.associateBy { it.source }
        assertEquals(setOf("萧熏儿", "萧炎", "フィットア領", "罗亚", "ロア"), bySource.keys)
        val xuner = bySource.getValue("萧熏儿")
        assertEquals(listOf("熏儿", "熏儿妹妹"), xuner.aliases)
        assertEquals("female", xuner.gender)
        assertEquals("Xiao Yan's companion", xuner.notes)
        val yan = bySource.getValue("萧炎")
        assertEquals(0.9f, yan.confidence, 1e-6f)
        assertEquals("male", yan.gender)
        assertEquals(listOf("炎儿"), yan.aliases)
        assertEquals(listOf("フィットア"), bySource.getValue("フィットア領").aliases)
        assertEquals(3, r.droppedFor(Reason.MERGED).size)
        assertTrue(r.dropped.filter { it.reason == Reason.MERGED }.all { it.into != null })
    }

    @Test
    fun termsAlreadyInTheBibleAreNeverDroppedOrMerged() {
        val r = AnalysisFilter.filter(
            listOf(
                t("大臣", "Minister", "character"),         // generic role, but the user keeps it in the bible
                t("宰相", "minister", "title", proper = false), // same English, but the bible one is protected
            ),
            "",
            protectedSources = setOf("大臣"),
        )
        assertEquals(listOf("大臣"), r.terms.map { it.source })
        assertEquals(Reason.NOT_PROPER, r.dropped.single().reason)
    }

    @Test
    fun englishKeyIgnoresCaseArticlesAndPunctuation() {
        assertEquals(AnalysisFilter.englishKey("The Fittoa  Region"), AnalysisFilter.englishKey("fittoa-region"))
        assertEquals(AnalysisFilter.englishKey("Xiao Xun'er"), AnalysisFilter.englishKey("xiao xuner"))
        assertFalse(AnalysisFilter.englishKey("Fittoa Territory") == AnalysisFilter.englishKey("Fittoa Region"))
    }

    @Test
    fun genericRoleHeuristics() {
        assertTrue(AnalysisFilter.isGenericRole("那个老头", "Old Man"))
        assertTrue(AnalysisFilter.isGenericRole("그 남자", "That Man"))
        assertTrue(AnalysisFilter.isGenericRole("例の男", "That Guy"))
        assertTrue(AnalysisFilter.isGenericRole("守卫", "the guard"))
        assertTrue(AnalysisFilter.isGenericRole("守卫", "guard"))
        assertFalse(AnalysisFilter.isGenericRole("萧炎", "Xiao Yan"))
        assertFalse(AnalysisFilter.isGenericRole("이혜정", "Lee Hyejeong"))
        assertNull(AnalysisFilter.filter(listOf(t("萧炎", "Xiao Yan", "character")), "").dropped.firstOrNull())
    }
}
