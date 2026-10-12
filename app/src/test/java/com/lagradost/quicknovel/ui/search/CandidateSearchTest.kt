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
        CandidateSearch.run(candidates, urlOf = Hit::url) { q ->
            @Suppress("UNCHECKED_CAST")
            when (val a = answers[q]) {
                is Throwable -> throw a
                null -> emptyList()
                else -> a as List<Hit>
            }
        }

    @Test
    fun stopsAtTheFirstCandidateWithResults() = runBlocking {
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
