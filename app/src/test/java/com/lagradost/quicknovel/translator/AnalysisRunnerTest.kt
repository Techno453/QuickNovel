package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisRunnerTest {
    private val ch0 = AnalysisRunner.Chapter(0, "萧炎站在测验魔石碑前。\n萧媚冷笑。\n作者的话：土豆求收藏！")
    private val ch1 = AnalysisRunner.Chapter(1, "萧炎回到萧家。\n熏儿在等他。")

    @Test
    fun segmentsStripAuthorNotesButKeepRawChapters() {
        val segments = AnalysisRunner.segment(listOf(ch0, ch1))
        assertEquals(1, segments.size)
        val s = segments.single()
        assertEquals(0, s.firstIndex)
        assertEquals(1, s.lastIndex)
        assertFalse(s.text.contains("土豆"))
        assertEquals(listOf(ch0.text, ch1.text), s.rawChapters)
        assertEquals("chapters 1-2", s.label)
    }

    @Test
    fun segmentsRespectTheSizeLimit() {
        val long = AnalysisRunner.Chapter(0, (1..30).joinToString("\n") { "第${it}段" + "字".repeat(20) })
        val segments = AnalysisRunner.segment(listOf(long, ch1), limit = 200)
        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.text.length <= 200 })
        assertEquals("chapter 1", segments.first().label)
        assertEquals(1, segments.last().lastIndex)
    }

    @Test
    fun runFiltersSavesAndReportsSuccess() = runBlocking {
        val bible = listOf(GlossaryTerm(novelId = "n", source = "萧炎", english = "Xiao Yan", category = "character", locked = true))
        val seenKnown = mutableListOf<Set<String>>()
        val saved = mutableListOf<GlossaryTerm>()
        val pending = mutableListOf<PendingTerm>()
        val result = AnalysisRunner.run(
            novelId = "n",
            chapters = listOf(ch0, ch1),
            bible = bible,
            previousPremise = "",
            extractor = { excerpt, known, _, _ ->
                assertFalse("author's note must not be sent", excerpt.contains("土豆"))
                seenKnown += known.toSet()
                BibleExtraction(
                    premise = "Xiao Yan, a fallen genius, is humiliated at the testing stone.",
                    terms = listOf(
                        DiscoveredTerm("萧炎", "Xiao Yan", "character", "male", confidence = 1f),
                        DiscoveredTerm("萧媚", "Xiao Mei", "character", "female", confidence = 1f),
                        DiscoveredTerm("熏儿", "Xun'er", "character", "female", confidence = 0.9f),
                        DiscoveredTerm("萧家", "Xiao Family", "sect", confidence = 1f),
                        DiscoveredTerm("萧家族", "xiao family", "sect", confidence = 0.6f, aliases = listOf("萧氏")),
                        DiscoveredTerm("土豆", "Tudou", "character", confidence = 0.99f), // only in the note
                        DiscoveredTerm("测验人", "examiner", "character", confidence = 0.72f),
                        DiscoveredTerm("小炎子", "Xiao Yan", "character", "male", confidence = 0.7f), // same English as the bible
                    ),
                )
            },
            saveTerms = { saved += it; it.size },
            savePending = { pending += it; it.size },
        )
        assertTrue(result.succeeded)
        assertTrue(result.premiseChanged)
        assertEquals(2, result.chaptersAnalyzed)
        assertEquals(setOf("萧炎"), seenKnown.single())
        assertEquals(setOf("萧媚", "萧家", "熏儿"), saved.map { it.source }.toSet())
        assertTrue(saved.all { it.autoAdded && !it.locked })
        assertEquals(listOf("萧家族", "萧氏"), TermAliases.parse(saved.single { it.source == "萧家" }.aliases))
        assertEquals(1, saved.single { it.source == "熏儿" }.firstChapter)
        // 小炎子 is not in the excerpt, so it is skipped rather than queued.
        assertTrue(pending.isEmpty())
        assertEquals(3, result.termsAdded)
        val reasons = result.dropped.map { it.term.source to it.reason }.toMap()
        assertEquals(AnalysisFilter.Reason.AUTHOR_NOTE, reasons["土豆"])
        assertEquals(AnalysisFilter.Reason.GENERIC_ROLE, reasons["测验人"])
        assertEquals(AnalysisFilter.Reason.MERGED, reasons["萧家族"])
    }

    @Test
    fun sameEnglishAsAnExistingEntryGoesToReview() = runBlocking {
        val bible = listOf(GlossaryTerm(novelId = "n", source = "萧炎", english = "Xiao Yan", category = "character"))
        val pending = mutableListOf<PendingTerm>()
        AnalysisRunner.run(
            novelId = "n",
            chapters = listOf(AnalysisRunner.Chapter(0, "炎儿笑了。")),
            bible = bible,
            previousPremise = "old",
            extractor = { _, _, _, _ -> BibleExtraction("", listOf(DiscoveredTerm("炎儿", "Xiao Yan", "character", confidence = 0.9f))) },
            saveTerms = { fail("nothing new to save"); 0 },
            savePending = { pending += it; it.size },
        ).also {
            assertFalse(it.premiseChanged)
            assertEquals("old", it.premise)
            assertEquals(1, it.pendingAdded)
        }
        assertEquals("炎儿", pending.single().source)
        assertTrue(pending.single().notes.startsWith("same English as existing 萧炎"))
    }

    @Test
    fun failedCallsAreCountedAndAllFailedMeansNotAnalyzed() = runBlocking {
        val result = AnalysisRunner.run(
            novelId = "n",
            chapters = listOf(ch0),
            bible = emptyList(),
            previousPremise = "",
            extractor = { _, _, _, _ -> throw TranslationApiException("HTTP 503", retryable = true) },
            saveTerms = { it.size },
        )
        assertFalse(result.succeeded)
        assertEquals(1, result.callsFailed)
        assertEquals("HTTP 503", result.lastError)
    }

    @Test
    fun invalidApiKeyIsRethrown() = runBlocking {
        try {
            AnalysisRunner.run(
                novelId = "n",
                chapters = listOf(ch0),
                bible = emptyList(),
                previousPremise = "",
                extractor = { _, _, _, _ -> throw TranslationApiException("Invalid API key (401)") },
                saveTerms = { it.size },
            )
            fail("expected the invalid-key error to propagate")
        } catch (e: TranslationApiException) {
            assertTrue(e.message!!.startsWith("Invalid API key"))
        }
    }
}
