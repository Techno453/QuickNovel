package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationEngineHelpersTest {
    private val engine = TranslationEngine.Companion

    // ---------------- sentence splitting ----------------

    @Test
    fun chineseSentencesSplitOnCjkTerminators() {
        val text = "他走了。她笑了！真的吗？是的；好吧……然后呢"
        val sentences = engine.splitSentences(text)
        assertEquals(listOf("他走了。", "她笑了！", "真的吗？", "是的；", "好吧……", "然后呢"), sentences)
        assertEquals(text, sentences.joinToString(""))
    }

    @Test
    fun japaneseClosingQuotesStayWithSentence() {
        val text = "「行くぞ。」彼は言った。『本当に？』「はい」「いいえ」"
        val sentences = engine.splitSentences(text)
        assertEquals(listOf("「行くぞ。」", "彼は言った。", "『本当に？』", "「はい」", "「いいえ」"), sentences)
        assertEquals(text, sentences.joinToString(""))
    }

    @Test
    fun koreanUsesLatinPunctuationBeforeWhitespace() {
        val text = "그는 웃었다. \"정말?\" 그녀가 물었다! 3.14는 숫자다"
        val sentences = engine.splitSentences(text)
        assertEquals(listOf("그는 웃었다. ", "\"정말?\" ", "그녀가 물었다! ", "3.14는 숫자다"), sentences)
        assertEquals(text, sentences.joinToString(""))
    }

    // ---------------- chunking ----------------

    @Test
    fun shortParagraphsArePackedAndJoinedWithBlankLines() {
        val chunks = engine.splitIntoChunks("第一段。\n第二段。\n\n第三段。", maxChars = 2000)
        assertEquals(listOf("第一段。\n\n第二段。\n\n第三段。"), chunks)
    }

    @Test
    fun chunksRespectMaxSizeAtParagraphBoundaries() {
        val para = "这是一个句子。".repeat(10) // 70 chars
        val text = List(5) { para }.joinToString("\n")
        val chunks = engine.splitIntoChunks(text, maxChars = 150)
        assertTrue(chunks.all { it.length <= 150 })
        assertEquals(3, chunks.size)
        assertEquals(text.replace("\n", ""), chunks.joinToString("").replace("\n", ""))
    }

    @Test
    fun longChineseParagraphSplitsAtSentenceEnds() {
        val text = "他走了很远很远的路。".repeat(50) // 500 chars, single paragraph
        val chunks = engine.splitIntoChunks(text, maxChars = 120)
        assertTrue(chunks.all { it.length <= 120 })
        assertTrue(chunks.all { it.endsWith("。") })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun longJapaneseParagraphKeepsQuotesTogether() {
        val text = "「待ってくれ、すぐに行くから。」".repeat(20)
        val chunks = engine.splitIntoChunks(text, maxChars = 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertTrue(chunks.all { it.startsWith("「") && it.endsWith("」") })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun longKoreanParagraphSplitsOnLatinPunctuation() {
        val text = "그는 천천히 걸어갔다. ".repeat(40).trim()
        val chunks = engine.splitIntoChunks(text, maxChars = 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertTrue(chunks.all { it.endsWith(".") })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun unpunctuatedTextIsHardSplit() {
        val text = "无".repeat(1000)
        val chunks = engine.splitIntoChunks(text, maxChars = 300)
        assertTrue(chunks.all { it.length <= 300 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun koreanUsesLargerChunks() {
        assertTrue(engine.chunkSizeFor("ko") > engine.chunkSizeFor("zh"))
        assertEquals(engine.chunkSizeFor("zh"), engine.chunkSizeFor("ja"))
    }

    @Test
    fun splitInHalfPrefersParagraphBreak() {
        val a = "第一段。".repeat(20)
        val b = "第二段。".repeat(20)
        val (first, second) = engine.splitInHalf("$a\n\n$b")
        assertEquals(a, first)
        assertEquals(b, second)
    }

    @Test
    fun splitInHalfFallsBackToSentences() {
        val text = "一二三四五。".repeat(10)
        val (first, second) = engine.splitInHalf(text)
        assertTrue(first.endsWith("。"))
        assertEquals(text, first + second)
        assertTrue(first.length in 24..36)
    }

    // ---------------- [NEW:] markers ----------------

    @Test
    fun extractsNewTermsWithAllArrowVariants() {
        val text = "He met [NEW: 林动 → Lin Dong] at [NEW: 青阳镇 -> Qingyang Town]. " +
                "[NEW: 魔王 ＞ Demon King] and [NEW：김독자 => Kim Dokja] and ［NEW: 剣聖 ⇒ Sword Saint］."
        val terms = engine.extractNewTerms(text)
        assertEquals(
            listOf(
                "林动" to "Lin Dong",
                "青阳镇" to "Qingyang Town",
                "魔王" to "Demon King",
                "김독자" to "Kim Dokja",
                "剣聖" to "Sword Saint",
            ),
            terms
        )
    }

    @Test
    fun stripsNewTermMarkersKeepingSuggestion() {
        val text = "He met [NEW: 林动 → Lin Dong] at [NEW: 青阳镇 -> Qingyang Town]."
        assertEquals("He met Lin Dong at Qingyang Town.", engine.stripNewTermMarkers(text))
    }

    @Test
    fun noMarkersMeansNoTerms() {
        assertTrue(engine.extractNewTerms("Nothing [new] here -> really.").isEmpty())
    }

    // ---------------- output cleanup ----------------

    @Test
    fun cleanRemovesCodeFencesAndEchoedHeading() {
        val raw = "## Translation:\n```markdown\nFirst paragraph.\n\nSecond paragraph.\n```"
        assertEquals("First paragraph.\n\nSecond paragraph.", engine.cleanModelOutput(raw))
    }

    @Test
    fun cleanRemovesInlineTranslationLabel() {
        assertEquals("He smiled.", engine.cleanModelOutput("**Translation:** He smiled."))
        assertEquals("He smiled.", engine.cleanModelOutput("Translation: He smiled."))
    }

    @Test
    fun cleanKeepsProseStartingWithTranslation() {
        val text = "Translation of the ancient scroll took three days."
        assertEquals(text, engine.cleanModelOutput(text))
    }

    @Test
    fun paragraphHtmlToleratesSingleNewlines() {
        val html = engine.buildParagraphHtml(listOf("One.\nTwo.", "Three.\n\n\nFour.\nEnd of Chapter"))
        assertEquals("<p>One.</p>\n<p>Two.</p>\n<p>Three.</p>\n<p>Four.</p>", html)
    }

    @Test
    fun htmlSourceBecomesParagraphs() {
        val text = engine.htmlToPlainText("<div><p>第一段。</p><p>第二段<br>第三行</p></div>")
        assertEquals("第一段。\n\n第二段\n\n第三行", text)
    }

    @Test
    fun plainSourcePassesThrough() {
        val text = "第一段。\n第二段 a < b"
        assertEquals(text, engine.htmlToPlainText(text))
    }

    // ---------------- languages / config ----------------

    @Test
    fun translatableLanguages() {
        assertTrue(TranslationLanguages.isTranslatable("zh"))
        assertTrue(TranslationLanguages.isTranslatable("ja"))
        assertTrue(TranslationLanguages.isTranslatable("ko"))
        assertTrue(TranslationLanguages.isTranslatable("zh-TW"))
        assertFalse(TranslationLanguages.isTranslatable("en"))
        assertFalse(TranslationLanguages.isTranslatable(null))
        assertEquals("zh-CN", TranslationLanguages.googleCode("zh"))
    }

    @Test
    fun baseUrlNormalization() {
        assertEquals(LlmConfig.DEFAULT_BASE_URL, LlmConfig.normalizeBaseUrl("  "))
        assertEquals("https://api.deepseek.com/v1", LlmConfig.normalizeBaseUrl("api.deepseek.com/v1/"))
        assertEquals(
            "http://192.168.1.5:1234/v1",
            LlmConfig.normalizeBaseUrl("http://192.168.1.5:1234/v1/chat/completions")
        )
        assertEquals("https://openrouter.ai/api/v1/chat/completions", LlmConfig.chatCompletionsUrl("https://openrouter.ai/api/v1"))
    }

    @Test
    fun promptsAreLanguageSpecific() {
        assertTrue(PromptTemplates.systemPrompt("zh").contains("xianxia"))
        assertTrue(PromptTemplates.systemPrompt("ja").contains("honorific"))
        assertTrue(PromptTemplates.systemPrompt("ko").contains("hyung"))
        val user = PromptTemplates.buildUserPrompt("本文", emptyList(), "ja")
        assertTrue(user.contains("## Japanese Text:"))
        assertTrue(user.contains("[NEW:"))
    }
}
