package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.NovelContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryBibleTest {
    private fun term(
        source: String,
        english: String,
        category: String = "character",
        gender: String = "unknown",
        notes: String = "",
        aliases: List<String> = emptyList(),
        locked: Boolean = false,
        autoAdded: Boolean = false,
        id: Long = 0,
    ) = GlossaryTerm(
        id = id, novelId = "n", source = source, english = english, category = category, gender = gender,
        notes = notes, aliases = TermAliases.encode(aliases), locked = locked, autoAdded = autoAdded
    )

    // ---------------- adherence ----------------

    @Test
    fun renderingMatchToleratesCasePossessiveAndPlural() {
        assertTrue(QualityChecks.containsRendering("Lin Feng's sword gleamed.", "Lin Feng"))
        assertTrue(QualityChecks.containsRendering("LIN FENG shouted.", "Lin Feng"))
        assertTrue(QualityChecks.containsRendering("Two Spirit Beasts appeared.", "Spirit Beast"))
        assertTrue(QualityChecks.containsRendering("The Elders’ decision", "Elder"))
        assertTrue(QualityChecks.containsRendering("three Sword Bodies", "Sword Body"))
        assertTrue(QualityChecks.containsRendering("a golden-core cultivator", "Golden Core"))
        assertFalse(QualityChecks.containsRendering("Lin Fengyu arrived.", "Lin Feng"))
        assertFalse(QualityChecks.containsRendering("Brother Lin arrived.", "Lin Feng"))
    }

    @Test
    fun violationsOnlyForTermsPresentInSource() {
        val bible = listOf(term("林风", "Lin Feng"), term("苏婉", "Su Wan"), term("青云宗", "Azure Cloud Sect", "sect"))
        val violations = QualityChecks.findViolations("林风走进青云宗。", "Lin Feng entered the Qingyun Sect.", bible)
        assertEquals(listOf("Azure Cloud Sect"), violations.map { it.english })
    }

    @Test
    fun aliasesTriggerTheCheck() {
        val bible = listOf(term("林风", "Lin Feng", aliases = listOf("小风")))
        assertEquals(1, QualityChecks.findViolations("小风笑了。", "Xiaofeng laughed.", bible).size)
        assertEquals(0, QualityChecks.findViolations("小风笑了。", "Lin Feng laughed.", bible).size)
    }

    @Test
    fun longerTermsMaskShorterOnesAndSingleCharactersAreSkipped() {
        val bible = listOf(term("灵气", "spiritual energy", "other"), term("气", "qi", "other"))
        // 气 only occurs inside 灵气, so only 灵气 counts as present.
        assertEquals(listOf("灵气"), QualityChecks.termsInSource("灵气充沛", bible).map { it.source })
        // A standalone 气 is present but single-character sources are not checked.
        assertTrue(QualityChecks.findViolations("一口气", "in one breath", bible).isEmpty())
    }

    // ---------------- CJK leaks / paragraphs ----------------

    @Test
    fun detectsCjkLeaksButIgnoresTheTail() {
        val raw = "He used the 天罡剑法 and shouted さあ and 가자!\n===TERMS===\n[{\"source\":\"天罡剑法\",\"english\":\"Heavenly Sword Art\"}]\n===SUMMARY===\nFight.\nCharacters: 林风"
        val parsed = TranslationProtocol.parse(raw)
        assertEquals(listOf("天罡剑法", "さあ", "가자"), QualityChecks.findCjkLeaks(parsed.translation))
        assertTrue(QualityChecks.findCjkLeaks("Clean English text — with “quotes” and 123.").isEmpty())
    }

    @Test
    fun paragraphShortfallBelowSeventyPercent() {
        val source = (1..10).joinToString("\n\n") { "第${it}段。" }
        val seven = (1..7).joinToString("\n\n") { "Paragraph $it." }
        val six = (1..6).joinToString("\n\n") { "Paragraph $it." }
        assertFalse(QualityChecks.check(source, seven, emptyList()).paragraphShortfall)
        val q = QualityChecks.check(source, six, emptyList())
        assertTrue(q.paragraphShortfall)
        assertEquals(10, q.inputParagraphs)
        assertEquals(6, q.outputParagraphs)
        assertEquals(1, q.issueCount)
    }

    // ---------------- prompt rendering ----------------

    @Test
    fun bibleRendersCharactersFirstWithGenderNotesAndAliases() {
        val bible = listOf(
            term("青云宗", "Azure Cloud Sect", "sect"),
            term("林风", "Lin Feng", gender = "male", notes = "protagonist; outer disciple", aliases = listOf("小风")),
            term("筑基期", "Foundation Establishment", "rank"),
            term("苏婉", "Su Wan", gender = "female"),
            term("老鬼", "Old Ghost"),
        )
        val block = PromptTemplates.renderBible(bible)
        val characters = block.indexOf("### Characters")
        val sects = block.indexOf("### Sects")
        val ranks = block.indexOf("### Ranks")
        assertTrue(characters in 0 until sects && sects < ranks)
        assertTrue(block.contains("- 林风 → Lin Feng (male; protagonist; outer disciple) [also written: 小风]"))
        assertTrue(block.contains("- 苏婉 → Su Wan (female)"))
        assertTrue(block.contains("- 老鬼 → Old Ghost (gender unknown)"))
        assertTrue(block.contains("- 青云宗 → Azure Cloud Sect\n"))
    }

    @Test
    fun styleInstructionsPerLanguage() {
        val zh = PromptTemplates.renderStyle("zh", null)
        assertTrue(zh.contains("Hanyu Pinyin for all proper nouns"))
        assertTrue(zh.contains("Senior Brother")) // adapt is the zh default

        val zhMixed = PromptTemplates.renderStyle("zh", TranslationStyle("mixed", "keep", "British spelling"))
        assertTrue(zhMixed.contains("translate titles, sects, techniques and items by meaning"))
        assertTrue(zhMixed.contains("shixiong"))
        assertTrue(zhMixed.contains("Reader's instructions: British spelling"))

        val ja = PromptTemplates.renderStyle("ja", null)
        assertTrue(ja.contains("family name first (Japanese order)"))
        assertTrue(ja.contains("keep suffixes"))
        val jaWestern = PromptTemplates.renderStyle("ja", TranslationStyle("given-family", "drop"))
        assertTrue(jaWestern.contains("given name first"))
        assertTrue(jaWestern.contains("drop suffixes"))

        val ko = PromptTemplates.renderStyle("ko", null)
        assertTrue(ko.contains("Kim Dokja"))
        assertTrue(ko.contains("keep hyung"))
    }

    @Test
    fun styleResolutionFallsBackToDefaults() {
        assertEquals(TranslationStyle("pinyin", "adapt"), TranslationStyle.resolve("zh", "", null))
        assertEquals(TranslationStyle("family-given", "keep"), TranslationStyle.resolve("ja", "pinyin", "bogus"))
        assertEquals("meaning", TranslationStyle.resolve("zh", "", "", fallbackName = "meaning").nameStyle)
        assertEquals("mixed", TranslationStyle.resolve("zh", "mixed", "", fallbackName = "meaning").nameStyle)
    }

    @Test
    fun userPromptHasContextBibleStyleAndParagraphCount() {
        val ctx = StoryContext(premise = "A boy joins a sect.", previousChapterSummary = "Lin Feng passed the test.")
        val prompt = PromptTemplates.buildUserPrompt(
            "第一段。\n\n第二段。", listOf(term("林风", "Lin Feng", gender = "male")), "zh", ctx,
            precedingEnglish = "x".repeat(800)
        )
        assertTrue(prompt.indexOf("## Story so far") < prompt.indexOf("## Story Bible"))
        assertTrue(prompt.contains("## Previous chapter\nLin Feng passed the test."))
        assertTrue(prompt.contains("…" + "x".repeat(PromptTemplates.CONTEXT_TAIL_CHARS)))
        assertFalse(prompt.contains("x".repeat(PromptTemplates.CONTEXT_TAIL_CHARS + 1)))
        assertTrue(prompt.contains("## Chinese Text: (2 paragraphs)"))
        assertTrue(prompt.contains("Translate all 2 paragraphs"))

        val bare = PromptTemplates.buildUserPrompt("本文", emptyList(), "ja")
        assertFalse(bare.contains("## Story so far"))
        assertFalse(bare.contains("## Story Bible"))
        assertTrue(bare.contains("## Translation style"))
    }

    @Test
    fun systemPromptDefinesTheTailProtocol() {
        for (lang in listOf("zh", "ja", "ko")) {
            val s = PromptTemplates.systemPrompt(lang)
            assertTrue(s.contains(TranslationProtocol.TERMS_MARKER))
            assertTrue(s.contains(TranslationProtocol.SUMMARY_MARKER))
            assertTrue(s.contains("Translate EVERY paragraph"))
            assertTrue(s.contains("stated gender"))
        }
    }

    @Test
    fun reminderListsEveryProblem() {
        val q = ChunkQuality(
            violatedTerms = listOf(term("林风", "Lin Feng")),
            leakedFragments = listOf("天罡"),
            inputParagraphs = 10,
            outputParagraphs = 5,
        )
        val r = PromptTemplates.buildReminder(q, "zh")
        assertTrue(r.contains("林风 → Lin Feng"))
        assertTrue(r.contains("「天罡」"))
        assertTrue(r.contains("Translate ALL 10 paragraphs"))
    }

    // ---------------- merging discovered terms ----------------

    @Test
    fun confidentNewTermsAreAutoAddedOthersPending() {
        val chunk = "苏婉和黑风寨"
        val d = BibleMerger.decide(
            listOf(
                DiscoveredTerm("苏婉", "Su Wan", "character", "female", "senior sister", 0.9f),
                DiscoveredTerm("黑风寨", "Black Wind Fort", "location", confidence = 0.4f),
                DiscoveredTerm("不存在", "Ghost Term", confidence = 0.95f), // not in the chunk
                DiscoveredTerm("Su Wan", "Su Wan", confidence = 0.95f), // not source-language text
            ),
            bible = emptyList(), novelId = "n", chapterNum = 3, sourceChunk = chunk
        )
        val added = d.autoAdd.single()
        assertEquals("Su Wan", added.english)
        assertEquals("female", added.gender)
        assertTrue(added.autoAdded)
        assertFalse(added.locked)
        assertEquals(3, added.firstChapter)
        assertEquals(setOf("黑风寨", "不存在"), d.pending.map { it.source }.toSet())
    }

    @Test
    fun conflictsGoToPendingAndLockedTermsAreNeverChanged() {
        val locked = term("林风", "Lin Feng", gender = "unknown", locked = true, id = 1)
        val d = BibleMerger.decide(
            listOf(
                DiscoveredTerm("林风", "Lin Fung", "character", "male", confidence = 0.99f),
            ),
            bible = listOf(locked), novelId = "n", chapterNum = 0, sourceChunk = "林风"
        )
        assertTrue(d.autoAdd.isEmpty())
        assertTrue(d.updates.isEmpty())
        assertEquals("conflicts with existing Lin Feng", d.pending.single().notes)

        // Same rendering + known gender: only unlocked auto-added terms are updated.
        val same = listOf(DiscoveredTerm("林风", "lin feng", "character", "male", confidence = 0.9f))
        assertTrue(BibleMerger.decide(same, listOf(locked), "n", 0, "林风").updates.isEmpty())
        val auto = locked.copy(locked = false, autoAdded = true)
        assertEquals("male", BibleMerger.decide(same, listOf(auto), "n", 0, "林风").updates.single().gender)
    }

    @Test
    fun aliasMatchesAndAutoAddCanBeDisabled() {
        val bible = listOf(term("林风", "Lin Feng", aliases = listOf("小风")))
        val alias = BibleMerger.decide(listOf(DiscoveredTerm("小风", "Lin Feng", confidence = 0.9f)), bible, "n", 0, "小风")
        assertTrue(alias.autoAdd.isEmpty() && alias.pending.isEmpty())

        val off = BibleMerger.decide(
            listOf(DiscoveredTerm("苏婉", "Su Wan", confidence = 0.9f)), emptyList(), "n", 0, "苏婉",
            TranslationOptions(autoAddTerms = false)
        )
        assertTrue(off.autoAdd.isEmpty())
        assertEquals("苏婉", off.pending.single().source)
    }

    // ---------------- rename ----------------

    @Test
    fun renameIsWholeWordAndCaseSensitive() {
        val html = "<p>Lin Feng's sword. Lin Fengyu laughed. lin feng?</p>"
        assertEquals(1, NameReplacer.countOccurrences(html, "Lin Feng"))
        assertEquals(
            "<p>Lin Fen's sword. Lin Fengyu laughed. lin feng?</p>",
            NameReplacer.replace(html, "Lin Feng", "Lin Fen")
        )
        assertEquals("Price is \$5", NameReplacer.replace("Price is X", "X", "\$5"))
    }

    @Test
    fun renameKeepsPossessiveAndPluralSuffixes() {
        val text = "The spirit stones glowed. One spirit stone. Lin Feng’s spirit stone's edge. Lin Fengs?"
        assertEquals(3, NameReplacer.countOccurrences(text, "spirit stone"))
        assertEquals(
            "The spirit crystals glowed. One spirit crystal. Lin Feng’s spirit crystal's edge. Lin Fengs?",
            NameReplacer.replace(text, "spirit stone", "spirit crystal"),
        )
        // A plural-looking suffix on a name is kept too; the possessive apostrophe forms survive.
        assertEquals("Lin Fen’s blade and the Lin Fens", NameReplacer.replace("Lin Feng’s blade and the Lin Fengs", "Lin Feng", "Lin Fen"))
        assertEquals("same", NameReplacer.replace("same", "x", "x"))
    }

    // ---------------- JSON export / import ----------------

    @Test
    fun exportImportRoundTripKeepsNewFields() {
        val terms = listOf(
            term("林风", "Lin Feng", gender = "male", notes = "protagonist", aliases = listOf("小风"), locked = true, id = 7)
                .copy(firstChapter = 2),
            term("天水城", "Tianshui City", "location", autoAdded = true, id = 8),
        )
        val context = NovelContext(novelId = "old", premise = "A boy.", styleNotes = "US spelling", nameStyle = "mixed", honorificStyle = "keep")
        val json = StoryBibleJson.export(terms, context)
        assertTrue(json.contains("\"chinese\""))
        assertTrue(json.contains("\"format\" : \"quicknovel-story-bible\""))

        val imported = StoryBibleJson.import(json, "new")
        assertEquals(2, imported.terms.size)
        val lin = imported.terms.first { it.source == "林风" }
        assertEquals(0L, lin.id)
        assertEquals("new", lin.novelId)
        assertEquals("male", lin.gender)
        assertEquals("protagonist", lin.notes)
        assertEquals(listOf("小风"), TermAliases.parse(lin.aliases))
        assertEquals(2, lin.firstChapter)
        assertTrue(lin.locked)
        assertTrue(imported.terms.first { it.source == "天水城" }.autoAdded)
        assertEquals("new", imported.context?.novelId)
        assertEquals("mixed", imported.context?.nameStyle)
        assertEquals("A boy.", imported.context?.premise)
    }

    @Test
    fun oldFormatImportStillLoads() {
        val old = """
            [
              {"id": 3, "novelId": "x", "chinese": "林风", "english": "Lin Feng", "category": "character",
               "description": "hero", "aliases": "[]"},
              {"chinese": "筑基期", "english": "Foundation Establishment", "category": "rank", "somethingNew": 1},
              {"chinese": "", "english": "dropped"}
            ]
        """.trimIndent()
        val imported = StoryBibleJson.import(old, "n")
        assertNull(imported.context)
        assertEquals(listOf("林风", "筑基期"), imported.terms.map { it.source })
        val lin = imported.terms.first()
        assertEquals("unknown", lin.gender)
        assertEquals("hero", lin.effectiveNotes)
        assertFalse(lin.autoAdded)
        assertFalse(lin.locked)
        assertNull(lin.firstChapter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidImportThrows() {
        StoryBibleJson.import("{\"hello\": 1}", "n")
    }

    @Test
    fun analysisEstimateIsInputHeavy() {
        val e = CostEstimator.estimateAnalysis(5, "gpt-4o-mini", "zh")
        // 5 x 4,500 = 22,500 chars * 0.97 (o200k zh) + 3 calls * 900 overhead = 24525 input, 0.3x output
        assertEquals(24525, e.inputTokens)
        assertEquals(7358, e.outputTokens)
        assertEquals(0, CostEstimator.estimateAnalysis(0, "gpt-4o-mini").totalTokens)
    }

    @Test
    fun categoryAndGenderNormalization() {
        assertEquals("sect", GlossaryTerm.normalizeCategory("Faction"))
        assertEquals("character", GlossaryTerm.normalizeCategory("person"))
        assertEquals("artifact", GlossaryTerm.normalizeCategory("weapon"))
        assertEquals("other", GlossaryTerm.normalizeCategory("???"))
        assertEquals("female", GlossaryTerm.normalizeGender("F"))
        assertEquals("unknown", GlossaryTerm.normalizeGender(""))
        assertEquals("♂", GlossaryTerm.genderBadge("male"))
    }
}
