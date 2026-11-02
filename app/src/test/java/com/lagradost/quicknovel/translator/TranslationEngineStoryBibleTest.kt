package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.TermCandidate
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Drives [TranslationEngine.translateChapter] against a scripted fake endpoint (no network). */
class TranslationEngineStoryBibleTest {
    private class FakeLlm(private val replies: List<String>) {
        val prompts = CopyOnWriteArrayList<String>()

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            val body = jacksonObjectMapper().readTree(buffer.readUtf8())
            val user = body.path("messages").last().path("content").asText()
            val index = prompts.size
            prompts += user
            val content = replies.getOrElse(index) { replies.last() }
            val json = jacksonObjectMapper().writeValueAsString(
                mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop")))
            )
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    }

    private fun engine(fake: FakeLlm) =
        TranslationEngine(apiKey = "test", model = "test-model", baseUrl = "http://localhost/v1", sourceLang = "zh", client = fake.client)

    private val linFeng = GlossaryTerm(novelId = "n", source = "林风", english = "Lin Feng", category = "character", gender = "male")

    @Test
    fun strictModeRetriesOnceAndKeepsTheBetterAttempt() = runBlocking {
        val fake = FakeLlm(
            listOf(
                "Brother Lin laughed.\n===TERMS===\n[]\n===SUMMARY===\nHe laughed.\nCharacters: 林风",
                "Lin Feng laughed.\n===TERMS===\n[]\n===SUMMARY===\nLin Feng laughed.\nCharacters: 林风",
            )
        )
        val result = engine(fake).translateChapter("林风笑了。", "n", 0, listOf(linFeng))
        assertEquals(2, fake.prompts.size)
        assertTrue(fake.prompts[1].contains("林风 → Lin Feng"))
        assertTrue(fake.prompts[1].contains("IMPORTANT"))
        assertEquals("<p>Lin Feng laughed.</p>", result.englishText)
        assertEquals(0, result.glossaryViolations)
        assertEquals(1, result.retries)
        assertEquals("Lin Feng laughed.", result.chapterSummary)
        assertEquals(listOf("林风"), result.charactersPresent)
    }

    @Test
    fun strictModeOffReportsViolationsWithoutRetry() = runBlocking {
        val fake = FakeLlm(listOf("Brother Lin laughed at 天罡.\n===TERMS===\n[]\n===SUMMARY===\nHe laughed."))
        val result = engine(fake).translateChapter(
            "林风笑了。", "n", 0, listOf(linFeng), options = TranslationOptions(strictMode = false)
        )
        assertEquals(1, fake.prompts.size)
        assertEquals(1, result.glossaryViolations)
        assertEquals(1, result.untranslatedFragments)
        assertEquals(listOf("林风 → Lin Feng"), result.violatedTerms)
    }

    @Test
    fun discoveredNamesAreUsedByTheNextChunkOfTheSameChapter() = runBlocking {
        val p1 = "苏婉看着远方。" + "风很大。".repeat(400) // > one chunk
        val p2 = "苏婉转身离开。" + "天很冷。".repeat(400)
        val fake = FakeLlm(
            listOf(
                "Su Wan looked into the distance. The wind was strong.\n===TERMS===\n" +
                    "[{\"source\":\"苏婉\",\"english\":\"Su Wan\",\"category\":\"character\",\"gender\":\"female\",\"notes\":\"heroine\",\"confidence\":0.9}]\n" +
                    "===SUMMARY===\nSu Wan watches.\nCharacters: 苏婉",
                "Su Wan turned and left. It was cold.\n===TERMS===\n[]\n===SUMMARY===\nShe leaves.\nCharacters: 苏婉",
            )
        )
        val result = engine(fake).translateChapter(
            "$p1\n$p2", "n", 4, emptyList(),
            context = StoryContext(premise = "A cold story."),
            options = TranslationOptions(strictMode = false)
        )
        assertEquals(2, result.chunkCount)
        assertTrue(fake.prompts[1].contains("苏婉 → Su Wan (female; heroine)"))
        // Continuity: premise and the previous chunk's English go to the second chunk.
        assertTrue(fake.prompts[1].contains("A cold story."))
        assertTrue(fake.prompts[1].contains("Su Wan looked into the distance."))
        // Seen in one chapter so far: a candidate, not a glossary entry.
        assertTrue(result.autoAddedTerms.isEmpty())
        val candidate = result.newCandidates.single()
        assertEquals("苏婉", candidate.source)
        assertEquals(4, candidate.chapterNum)
        assertTrue(result.pendingTerms.isEmpty())
        assertEquals("Su Wan watches. She leaves.", result.chapterSummary)
    }

    @Test
    fun aCandidateFromAnotherChapterIsAddedWhenItRecurs() = runBlocking {
        val fake = FakeLlm(listOf("Su Wan smiled.\n===TERMS===\n[]\n===SUMMARY===\nShe smiles.\nCharacters: 苏婉"))
        val suWan = TermCandidate(
            novelId = "n", source = "苏婉", english = "Su Wan", category = "character", gender = "female",
            chapterNum = 2, confidence = 0.9f,
        )
        val unsure = TermCandidate(novelId = "n", source = "黑风寨", english = "Black Wind Fort", category = "location", chapterNum = 1, confidence = 0.3f)
        val elsewhere = TermCandidate(novelId = "n", source = "青云宗", english = "Azure Cloud Sect", category = "sect", chapterNum = 1, confidence = 0.9f)
        val result = engine(fake).translateChapter(
            "苏婉在黑风寨笑了。", "n", 6, emptyList(),
            options = TranslationOptions(strictMode = false),
            candidates = listOf(suWan, unsure, elsewhere),
        )
        // Recurs and is confident: in the prompt from the first chunk, added with its first chapter.
        assertTrue(fake.prompts[0].contains("苏婉 → Su Wan (female)"))
        val added = result.autoAddedTerms.single()
        assertEquals("苏婉", added.source)
        assertEquals(2, added.firstChapter)
        assertTrue(added.autoAdded)
        // Recurs but not confident: review, not the prompt.
        assertFalse(fake.prompts[0].contains("黑风寨 →"))
        assertEquals("黑风寨", result.pendingTerms.single().source)
        // Not in this chapter: stays a candidate.
        assertTrue(result.newCandidates.isEmpty())
    }

    @Test
    fun termsFromChapterTitlesAndAuthorsNotesAreDropped() = runBlocking {
        val source = "第3章 視界\n\n伊恩睜開眼睛，看見了霧。\n\n感謝沐風之歌的盟主打賞！"
        val fake = FakeLlm(
            listOf(
                "Chapter 3: Sight\n\nIan opened his eyes and saw the mist.\n\nThanks to Mu Feng Zhi Ge for the League Master reward!\n" +
                    "===TERMS===\n[" +
                    "{\"source\":\"視界\",\"english\":\"Sight\",\"category\":\"other\",\"confidence\":0.9}," +
                    "{\"source\":\"伊恩\",\"english\":\"Ian\",\"category\":\"character\",\"gender\":\"male\",\"confidence\":0.9}," +
                    "{\"source\":\"沐風之歌\",\"english\":\"Mu Feng Zhi Ge\",\"category\":\"character\",\"confidence\":0.8}," +
                    "{\"source\":\"盟主\",\"english\":\"League Master\",\"category\":\"title\",\"confidence\":0.8}]\n" +
                    "===SUMMARY===\nIan wakes.\nCharacters: 伊恩",
            )
        )
        val result = engine(fake).translateChapter(source, "n", 2, emptyList(), options = TranslationOptions(strictMode = false))
        assertEquals(listOf("伊恩"), result.newCandidates.map { it.source })
        assertTrue(result.pendingTerms.isEmpty())
    }

    @Test
    fun carryContextOffSendsNoStoryContext() = runBlocking {
        val fake = FakeLlm(listOf("Lin Feng laughed.\n===TERMS===\n[]\n===SUMMARY===\nHe laughed."))
        val result = engine(fake).translateChapter(
            "林风笑了。", "n", 1, listOf(linFeng),
            context = StoryContext(premise = "PREMISE", previousChapterSummary = "PREVIOUS", previousEnglishTail = "TAIL"),
            options = TranslationOptions(carryStoryContext = false)
        )
        assertFalse(fake.prompts[0].contains("PREMISE"))
        assertFalse(fake.prompts[0].contains("PREVIOUS"))
        assertFalse(fake.prompts[0].contains("TAIL"))
        assertTrue(fake.prompts[0].contains("林风 → Lin Feng (male)"))
        // The summary still comes from the same response's tail (no extra call).
        assertEquals("He laughed.", result.chapterSummary)
        assertEquals(1, fake.prompts.size)
    }

    @Test
    fun previewTranslatesWithoutGlossaryTermsOrContext() = runBlocking {
        val p1 = "苏婉看着远方。" + "风很大。".repeat(400) // > one chunk
        val p2 = "苏婉转身离开。" + "天很冷。".repeat(400)
        val fake = FakeLlm(
            listOf(
                "Su Wan looked into the distance. The wind was strong.\n===TERMS===\n" +
                    "[{\"source\":\"苏婉\",\"english\":\"Su Wan\",\"category\":\"character\",\"gender\":\"female\",\"confidence\":0.9}," +
                    "{\"source\":\"远方\",\"english\":\"Afar\",\"category\":\"place\",\"confidence\":0.2}]\n" +
                    "===SUMMARY===\nSu Wan watches.\nCharacters: 苏婉",
                "Su Wan turned and left. It was cold.\n===TERMS===\n[]\n===SUMMARY===\nShe leaves.\nCharacters: 苏婉",
            )
        )
        // Even if a caller passed a bible-less but context-rich input, preview options send no context.
        val result = engine(fake).translateChapter(
            "$p1\n$p2", "n", 4, emptyList(),
            context = StoryContext(premise = "PREMISE", previousChapterSummary = "PREVIOUS", previousEnglishTail = "TAIL"),
            options = TranslationOptions.preview(),
        )
        assertEquals(2, result.chunkCount)
        // The term reported by chunk 1 is not folded into chunk 2's prompt, nor kept anywhere.
        assertFalse(fake.prompts[1].contains("苏婉 → Su Wan"))
        assertTrue(result.autoAddedTerms.isEmpty())
        assertTrue(result.newCandidates.isEmpty())
        assertTrue(result.updatedTerms.isEmpty())
        assertTrue(result.pendingTerms.isEmpty())
        // No story context, and the previous chunk's English is not carried either.
        for (prompt in fake.prompts) {
            assertFalse(prompt.contains("PREMISE"))
            assertFalse(prompt.contains("PREVIOUS"))
            assertFalse(prompt.contains("TAIL"))
        }
        assertFalse(fake.prompts[1].contains("Su Wan looked into the distance."))
        assertEquals(0, result.glossaryViolations)
        assertTrue(result.englishText.contains("Su Wan looked into the distance."))
        assertTrue(result.englishText.contains("Su Wan turned and left."))
    }
}
