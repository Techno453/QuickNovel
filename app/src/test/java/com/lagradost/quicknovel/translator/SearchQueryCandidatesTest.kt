package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.SearchQueryTranslator.QueryCandidates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Candidate queries of the translated search: parsing of the model reply and merge order. */
class SearchQueryCandidatesTest {

    // ---- model reply parsing ----

    @Test
    fun oneLineReplyKeepsTheOldContract() {
        assertEquals(listOf("斗破苍穹"), SearchQueryTranslator.parseModelCandidates("斗破苍穹"))
        assertEquals(listOf("斗破苍穹"), SearchQueryTranslator.parseModelCandidates("\"斗破苍穹\"\n"))
    }

    @Test
    fun threeLinesBestFirst() {
        val raw = "高天之上\n天空之上\n在天空上面\n多余的一行"
        assertEquals(listOf("高天之上", "天空之上", "在天空上面"), SearchQueryTranslator.parseModelCandidates(raw))
    }

    @Test
    fun numberingLabelsQuotesAndFencesAreStripped() {
        val raw = "```\n1. Original title: 「高天之上」\n2) Literal translation: 天空之上\n- 天空之上\n```"
        assertEquals(listOf("高天之上", "天空之上"), SearchQueryTranslator.parseModelCandidates(raw))
    }

    @Test
    fun blankReplyGivesNothing() {
        assertEquals(emptyList<String>(), SearchQueryTranslator.parseModelCandidates("  \n \n"))
    }

    // ---- merge order ----

    @Test
    fun aiKnownTitleThenIndexThenLiteral() {
        val c = SearchQueryTranslator.mergeCandidates(
            "Above the sky", "zh",
            aiLines = listOf("高天之上", "天空之上", "天空上方"),
            indexNames = listOf("高天之上", "높은 하늘 위", "高天之上外传"),
            google = "天空之上",
        )
        assertEquals("高天之上", c.known)
        assertEquals(listOf("高天之上外传"), c.indexed)
        assertEquals(listOf("高天之上", "高天之上外传", "天空之上", "天空上方"), c.all)
    }

    @Test
    fun withoutKeyIndexNamesComeBeforeTheLiteral() {
        val c = SearchQueryTranslator.mergeCandidates(
            "Above the sky", "zh", aiLines = emptyList(), indexNames = listOf("高天之上"), google = "天空之上",
        )
        assertNull(c.known)
        assertEquals(listOf("高天之上", "天空之上"), c.all)
    }

    @Test
    fun withoutKeyOrIndexOnlyTheLiteral() {
        val c = SearchQueryTranslator.mergeCandidates("Above the sky", "zh", emptyList(), emptyList(), "天空之上")
        assertEquals(listOf("天空之上"), c.all)
    }

    @Test
    fun indexNamesAreFilteredByScriptAndCapped() {
        val names = listOf("높은 하늘 위", "高天之上", "天上", "天外", "天下")
        val ko = SearchQueryTranslator.mergeCandidates("Above the sky", "ko", emptyList(), names, "하늘 위")
        assertEquals(listOf("높은 하늘 위", "하늘 위"), ko.all)
        val zh = SearchQueryTranslator.mergeCandidates("Above the sky", "zh", emptyList(), names, null)
        assertEquals(SearchQueryTranslator.MAX_INDEX_NAMES, zh.indexed.size)
    }

    @Test
    fun translationPartIsCappedAtThree() {
        val c = SearchQueryTranslator.mergeCandidates(
            "q", "zh", listOf("一", "二", "三", "四"), emptyList(), "五",
        )
        assertEquals(SearchQueryTranslator.MAX_CANDIDATES, c.all.size)
        assertEquals(listOf("一", "五", "二"), c.all)
    }

    @Test
    fun echoesOfTheQueryAreDroppedAndNothingFallsBackToTheQuery() {
        val c = SearchQueryTranslator.mergeCandidates("Above the sky", "zh", listOf("above the sky"), emptyList(), "Above the sky")
        assertEquals(QueryCandidates.untranslated("Above the sky"), c)
    }

    // ---- computeCandidates with fake sources ----

    private class FakeSources(
        val aiReply: String? = null,
        val googleReply: String? = null,
        val index: List<String> = emptyList(),
    ) : SearchQueryTranslator.Sources {
        var calls = 0
        override suspend fun ai(query: String, lang: String): String? = aiReply.also { calls++ }
        override suspend fun google(query: String, lang: String): String? = googleReply.also { calls++ }
        override suspend fun titleIndex(query: String): List<String> = index.also { calls++ }
    }

    @Test
    fun queryAlreadyInScriptIsSentUnchanged() = runBlocking {
        val sources = FakeSources(aiReply = "x", googleReply = "y", index = listOf("z"))
        val c = SearchQueryTranslator.computeCandidates("高天之上", "zh", sources)
        assertEquals(listOf("高天之上"), c.all)
        assertEquals(0, sources.calls)
    }

    @Test
    fun keyFreePathUsesIndexAndGoogle() = runBlocking {
        val c = SearchQueryTranslator.computeCandidates(
            "Above the sky", "zh", FakeSources(googleReply = "天空之上", index = listOf("高天之上")),
        )
        assertEquals(listOf("高天之上", "天空之上"), c.all)
    }

    @Test
    fun aiPathPutsTheKnownTitleFirst() = runBlocking {
        val c = SearchQueryTranslator.computeCandidates(
            "Above the sky", "zh", FakeSources(aiReply = "高天之上\n天空之上", googleReply = "天空之上"),
        )
        assertEquals(listOf("高天之上", "天空之上"), c.all)
        assertEquals("高天之上", c.known)
    }
}
