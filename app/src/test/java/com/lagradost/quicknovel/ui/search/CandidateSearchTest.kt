package com.lagradost.quicknovel.ui.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CandidateSearchTest {
    private data class Hit(val title: String, val url: String)

    private suspend fun run(candidates: List<String>, answers: Map<String, Any>) =
        CandidateSearch.run(candidates, urlOf = Hit::url, nameOf = Hit::title) { q ->
            @Suppress("UNCHECKED_CAST")
            when (val a = answers[q]) {
                is Throwable -> throw a
                null -> emptyList()
                else -> a as List<Hit>
            }
        }

    @Test
    fun stopsAtTheFirstCandidateWhoseResultsMatchTheTitle() = runBlocking {
        val out = run(
            listOf("高天之上", "天空之上", "Above the sky"),
            mapOf("高天之上" to listOf(Hit("高天之上", "/a")), "天空之上" to listOf(Hit("天空之上", "/b"))),
        )
        assertEquals(listOf("/a"), out.items.map { it.url })
        assertEquals("高天之上", out.answeredBy)
        assertEquals(listOf("高天之上"), out.tried)
        assertNull(out.error)
    }

    @Test
    fun aWrongFirstGuessDoesNotHideTheRightSecondCandidate() = runBlocking {
        // A fuzzy site answers the model's wrong "known title" with junk; the right candidate comes next.
        val out = run(
            listOf("天穹之上", "高天之上", "天空之上"),
            mapOf(
                "天穹之上" to listOf(Hit("天穹", "/junk1"), Hit("苍穹之上", "/junk2")),
                "高天之上" to listOf(Hit("高天之上", "/a"), Hit("高天之上外传", "/a2")),
                "天空之上" to listOf(Hit("天空之上", "/b")),
            ),
        )
        assertEquals(listOf("/a", "/a2", "/junk1", "/junk2"), out.items.map { it.url })
        assertEquals("高天之上", out.answeredBy)
        assertEquals(listOf("天穹之上", "高天之上"), out.tried)
        assertNull(out.error)
    }

    @Test
    fun givesUpAfterThreeAnsweringCandidatesWithoutAMatch() = runBlocking {
        val out = run(
            listOf("a", "b", "c", "d"),
            mapOf("a" to listOf(Hit("x", "/1")), "b" to listOf(Hit("y", "/2")), "c" to listOf(Hit("z", "/3")), "d" to listOf(Hit("d", "/4"))),
        )
        assertEquals(listOf("a", "b", "c"), out.tried)
        assertEquals(listOf("/1", "/2", "/3"), out.items.map { it.url })
        assertEquals("a", out.answeredBy)
    }

    @Test
    fun titleMatchIgnoresCaseAndWhitespace() {
        assertTrue(CandidateSearch.titleMatches("나 혼자만 레벨업 (완결)", "나혼자만 레벨업"))
        assertTrue(CandidateSearch.titleMatches("Lord of the Mysteries", "lord of the mysteries"))
        assertTrue(!CandidateSearch.titleMatches("天穹之上", "高天之上"))
        assertTrue(!CandidateSearch.titleMatches("anything", "  "))
    }

    @Test
    fun emptyCandidatesFallThroughAndResultsAreDeduplicatedByUrl() = runBlocking {
        val out = run(
            listOf("x", "y"),
            mapOf("y" to listOf(Hit("A", "/a"), Hit("A again", "/a"), Hit("B", "/b"))),
        )
        assertEquals(listOf("A", "B"), out.items.map { it.title })
        assertEquals("y", out.answeredBy)
        assertEquals(listOf("x", "y"), out.tried)
    }

    @Test
    fun aFailingCandidateDoesNotStopTheOthers() = runBlocking {
        val out = run(listOf("x", "y"), mapOf("x" to IOException("timeout"), "y" to listOf(Hit("A", "/a"))))
        assertEquals(1, out.items.size)
        assertNull(out.error)
    }

    @Test
    fun errorOnlyWhenEveryCandidateFailed() = runBlocking {
        val failed = run(listOf("x", "y"), mapOf("x" to IOException("a"), "y" to IOException("b")))
        assertEquals("a", failed.error?.message)
        val empty = run(listOf("x", "y"), mapOf("x" to IOException("a")))
        assertNull(empty.error)
        assertTrue(empty.items.isEmpty())
        assertNull(empty.answeredBy)
    }

    @Test
    fun rawCjkSectionsComeFirstThenEnglishThenOthersAndEmptyLast() {
        data class Row(val name: String, val lang: String?, val hasResults: Boolean)
        val rows = listOf(
            Row("Royal", "en", true), Row("Ranobes", "ru", true), Row("Ixdzs", "zh", false),
            Row("Quanben", "zh", true), Row("Syosetu", "ja", true), Row("Upstream", null, true),
            Row("Novelpia", "ko", false),
        )
        val ordered = rows.sortedBy { CandidateSearch.sectionRank(it.lang, it.hasResults) }.map { it.name }
        assertEquals(
            listOf("Quanben", "Syosetu", "Royal", "Upstream", "Ranobes", "Ixdzs", "Novelpia"),
            ordered,
        )
    }
}
