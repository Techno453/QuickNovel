package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.NovelMetadataTranslation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MetadataTranslatorTest {
    private class MapStore : MetadataStore {
        val rows = ConcurrentHashMap<String, NovelMetadataTranslation>()
        override suspend fun load(ids: List<String>) = ids.mapNotNull { rows[it] }
        override suspend fun save(rows: List<NovelMetadataTranslation>) = rows.forEach { this.rows[it.novelId] = it }
    }

    private class FakeBackend(var policy: MetadataPolicy = MetadataPolicy(ai = true, google = true)) : MetadataBackend {
        var aiFails = false
        var googleFails = false
        val aiTitleBatches = mutableListOf<List<String>>()
        val aiNovelCalls = AtomicInteger()
        val googleCalls = AtomicInteger()

        override suspend fun policy() = policy
        override fun aiModel() = "gpt-test"
        override suspend fun aiTitles(lang: String, titles: List<String>): List<String?> {
            synchronized(aiTitleBatches) { aiTitleBatches += titles }
            if (aiFails) throw IllegalStateException("HTTP 500")
            return titles.map { "AI $it" }
        }

        override suspend fun aiNovel(lang: String, novelId: String, title: String, synopsis: String): Pair<String?, String?> {
            aiNovelCalls.incrementAndGet()
            if (aiFails) throw IllegalStateException("HTTP 500")
            return "AI $title" to (if (synopsis.isBlank()) null else "AI $synopsis")
        }

        override suspend fun google(lang: String, text: String): String? {
            googleCalls.incrementAndGet()
            return if (googleFails) null else "G $text"
        }
    }

    private fun req(i: Int, title: String = "標題$i") = MetadataTranslator.TitleRequest("api/$i", "zh", title)

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aGoogleTitleIsUpgradedOnceAndTheAttemptIsPersisted() = runBlocking {
        var clock = 1_000_000_000L
        val store = MapStore()
        val backend = FakeBackend(MetadataPolicy(ai = false, google = true))
        fun session() = MetadataTranslator(store, backend, this, batchWindowMs = 10, now = { clock })
        assertEquals("G 標題1", session().title(req(1)))
        assertEquals(0L, store.rows["api/1"]!!.titleUpgradeTriedAt)

        // AI becomes available but fails: one attempt, recorded in the row, Google title kept.
        backend.policy = MetadataPolicy(ai = true, google = true)
        backend.aiFails = true
        val second = session()
        assertEquals("G 標題1", second.title(req(1)))
        assertEquals("G 標題1", second.title(req(1)))
        assertEquals(1, backend.aiTitleBatches.size)
        val row = store.rows["api/1"]!!
        assertEquals("google", row.titleEngine)
        assertEquals(clock, row.titleUpgradeTriedAt)

        // Later launches (new translator over the same store) do not ask again, even with AI working.
        backend.aiFails = false
        clock += 29 * day
        assertEquals("G 標題1", session().title(req(1)))
        assertEquals("G 標題1", session().title(req(1)))
        assertEquals(1, backend.aiTitleBatches.size)
        assertEquals(1, backend.googleCalls.get())

        // After 30 days it is tried once more, and this time it works.
        clock += 2 * day
        assertEquals("AI 標題1", session().title(req(1)))
        assertEquals(2, backend.aiTitleBatches.size)
        assertEquals("ai", store.rows["api/1"]!!.titleEngine)
        assertEquals("AI 標題1", session().title(req(1)))
        assertEquals(2, backend.aiTitleBatches.size)
    }

    @Test
    fun anUnusableAiReplyCountsAsAnAttempt() = runBlocking {
        val store = MapStore()
        val backend = object : MetadataBackend {
            var policy = MetadataPolicy(ai = false, google = true)
            var aiCalls = 0
            override suspend fun policy() = policy
            override fun aiModel() = "gpt-test"
            override suspend fun aiTitles(lang: String, titles: List<String>): List<String?> {
                aiCalls++
                return titles // echoed back: nothing usable
            }
            override suspend fun aiNovel(lang: String, novelId: String, title: String, synopsis: String) = null to null
            override suspend fun google(lang: String, text: String) = "G $text"
        }
        assertEquals("G 標題1", MetadataTranslator(store, backend, this, batchWindowMs = 10).title(req(1)))
        backend.policy = MetadataPolicy(ai = true, google = true)
        repeat(3) {
            assertEquals("G 標題1", MetadataTranslator(store, backend, this, batchWindowMs = 10).title(req(1)))
        }
        assertEquals(1, backend.aiCalls)
        assertTrue(store.rows["api/1"]!!.titleUpgradeTriedAt > 0)
    }

    @Test
    fun aTitleWithoutAnyTranslationIsRetriedOnTheNextLaunch() = runBlocking {
        val store = MapStore()
        val backend = FakeBackend().apply { aiFails = true; googleFails = true }
        assertNull(MetadataTranslator(store, backend, this, batchWindowMs = 10).title(req(1)))
        assertTrue(store.rows.isEmpty())
        backend.aiFails = false
        assertEquals("AI 標題1", MetadataTranslator(store, backend, this, batchWindowMs = 10).title(req(1)))
        assertEquals(2, backend.aiTitleBatches.size)
    }

    @Test
    fun aGoogleSynopsisIsUpgradedOnceAndTheAttemptIsPersisted() = runBlocking {
        var clock = 1_000_000_000L
        val store = MapStore()
        val backend = FakeBackend(MetadataPolicy(ai = false, google = true))
        fun session() = MetadataTranslator(store, backend, this, batchWindowMs = 10, now = { clock })
        val google = MetadataTranslator.Translated("G 題名", "G あらすじ")
        assertEquals(google, session().novel("api/1", "ja", "題名", "あらすじ"))

        backend.policy = MetadataPolicy(ai = true, google = true)
        backend.aiFails = true
        val second = session()
        assertEquals(google, second.novel("api/1", "ja", "題名", "あらすじ"))
        assertEquals(google, second.novel("api/1", "ja", "題名", "あらすじ"))
        assertEquals(1, backend.aiNovelCalls.get())
        val row = store.rows["api/1"]!!
        assertEquals("google", row.synopsisEngine)
        assertEquals(clock, row.titleUpgradeTriedAt)
        assertEquals(clock, row.synopsisUpgradeTriedAt)

        backend.aiFails = false
        clock += 29 * day
        assertEquals(google, session().novel("api/1", "ja", "題名", "あらすじ"))
        // The listing path sees the same recorded attempt for the title.
        assertEquals("G 題名", session().title(MetadataTranslator.TitleRequest("api/1", "ja", "題名")))
        assertEquals(1, backend.aiNovelCalls.get())
        assertTrue(backend.aiTitleBatches.isEmpty())

        clock += 2 * day
        assertEquals(MetadataTranslator.Translated("AI 題名", "AI あらすじ"), session().novel("api/1", "ja", "題名", "あらすじ"))
        assertEquals(2, backend.aiNovelCalls.get())
        assertEquals("ai", store.rows["api/1"]!!.synopsisEngine)
        assertEquals(MetadataTranslator.Translated("AI 題名", "AI あらすじ"), session().novel("api/1", "ja", "題名", "あらすじ"))
        assertEquals(2, backend.aiNovelCalls.get())
        assertEquals(2, backend.googleCalls.get())
    }

    @Test
    fun aStoredSynopsisIsKeptWhenANovelIsOpenedWithoutOne() = runBlocking {
        val store = MapStore()
        val backend = FakeBackend(MetadataPolicy(ai = false, google = true))
        MetadataTranslator(store, backend, this, batchWindowMs = 10).novel("api/1", "ja", "題名", "あらすじ")
        backend.policy = MetadataPolicy(ai = true, google = true)
        // The title is upgraded (and the row rewritten) by a call that has no synopsis.
        val noSynopsis = MetadataTranslator(store, backend, this, batchWindowMs = 10).novel("api/1", "ja", "題名", null)
        assertEquals("AI 題名", noSynopsis.title)
        assertEquals("G あらすじ", store.rows["api/1"]!!.translatedSynopsis)
        assertEquals("google", store.rows["api/1"]!!.synopsisEngine)
    }

    @Test
    fun aPageOfTitlesIsOneCallPerTwentyTitles() = runBlocking {
        val store = MapStore()
        val backend = FakeBackend()
        val translator = MetadataTranslator(store, backend, this, batchWindowMs = 40)
        val results = (1..25).map { i -> async { translator.title(req(i)) } }.awaitAll()

        assertEquals((1..25).map { "AI 標題$it" }, results)
        assertEquals(2, backend.aiTitleBatches.size)
        assertTrue(backend.aiTitleBatches.all { it.size <= MetadataTranslator.MAX_TITLES_PER_CALL })
        assertEquals(25, backend.aiTitleBatches.sumOf { it.size })
        assertEquals(0, backend.googleCalls.get())
        val row = store.rows["api/1"]!!
        assertEquals("ai", row.titleEngine)
        assertEquals("gpt-test", row.model)
        assertEquals("zh", row.sourceLang)
    }

    @Test
    fun concurrentRequestsForTheSameTitleShareOneTranslation() = runBlocking {
        val backend = FakeBackend()
        val translator = MetadataTranslator(MapStore(), backend, this, batchWindowMs = 40)
        val results = (1..5).map { async { translator.title(req(7)) } }.awaitAll()
        assertTrue(results.all { it == "AI 標題7" })
        assertEquals(listOf(listOf("標題7")), backend.aiTitleBatches)
    }

    @Test
    fun cachedForeverAndRetranslatedOnlyWhenTheSourceChanges() = runBlocking {
        val store = MapStore()
        val backend = FakeBackend()
        MetadataTranslator(store, backend, this, batchWindowMs = 10).title(req(1))
        assertEquals(1, backend.aiTitleBatches.size)

        // A new session (new instance) reads the database, no call.
        val second = MetadataTranslator(store, backend, this, batchWindowMs = 10)
        assertEquals("AI 標題1", second.title(req(1)))
        assertEquals("AI 標題1", second.cachedTitle("api/1", "標題1"))
        assertEquals(1, backend.aiTitleBatches.size)

        // The site renamed the novel: the hash differs, so it is translated again.
        assertNull(second.cachedTitle("api/1", "新標題"))
        assertEquals("AI 新標題", second.title(req(1, "新標題")))
        assertEquals(2, backend.aiTitleBatches.size)
    }

    @Test
    fun aiFailureFallsBackToGoogleAndUpgradesOncePerSession() = runBlocking {
        val store = MapStore()
        val backend = FakeBackend().apply { aiFails = true }
        val translator = MetadataTranslator(store, backend, this, batchWindowMs = 10)
        assertEquals("G 標題1", translator.title(req(1)))
        assertEquals("google", store.rows["api/1"]!!.titleEngine)

        // AI works again: the next session replaces the Google title once.
        backend.aiFails = false
        val next = MetadataTranslator(store, backend, this, batchWindowMs = 10)
        assertEquals("AI 標題1", next.title(req(1)))
        assertEquals("ai", store.rows["api/1"]!!.titleEngine)
        val calls = backend.aiTitleBatches.size
        assertEquals("AI 標題1", next.title(req(1)))
        assertEquals(calls, backend.aiTitleBatches.size)
    }

    @Test
    fun googleRowsAreNotUpgradedWithoutAi() = runBlocking {
        val store = MapStore()
        val backend = FakeBackend(MetadataPolicy(ai = false, google = true))
        val translator = MetadataTranslator(store, backend, this, batchWindowMs = 10)
        assertEquals("G 標題1", translator.title(req(1)))
        assertEquals("G 標題1", MetadataTranslator(store, backend, this, batchWindowMs = 10).title(req(1)))
        assertEquals(1, backend.googleCalls.get())
        assertTrue(backend.aiTitleBatches.isEmpty())
    }

    @Test
    fun nothingHappensWhenTranslationIsOff() = runBlocking {
        val backend = FakeBackend(MetadataPolicy.NONE)
        val translator = MetadataTranslator(MapStore(), backend, this, batchWindowMs = 10)
        assertNull(translator.title(req(1)))
        assertTrue(backend.aiTitleBatches.isEmpty())
        assertEquals(0, backend.googleCalls.get())
    }

    @Test
    fun failuresAreNotRetriedInTheSameSession() = runBlocking {
        val backend = FakeBackend().apply { aiFails = true; googleFails = true }
        val translator = MetadataTranslator(MapStore(), backend, this, batchWindowMs = 10)
        assertNull(translator.title(req(1)))
        assertNull(translator.title(req(1)))
        assertEquals(1, backend.aiTitleBatches.size)
        assertEquals(1, backend.googleCalls.get())
    }

    @Test
    fun novelTranslatesTitleAndSynopsisInOneCallAndCachesThem() = runBlocking {
        val store = MapStore()
        val backend = FakeBackend()
        val translator = MetadataTranslator(store, backend, this, batchWindowMs = 10)
        val first = translator.novel("api/1", "ja", "題名", "あらすじ")
        assertEquals(MetadataTranslator.Translated("AI 題名", "AI あらすじ"), first)
        assertEquals(1, backend.aiNovelCalls.get())

        val again = MetadataTranslator(store, backend, this, batchWindowMs = 10).novel("api/1", "ja", "題名", "あらすじ")
        assertEquals(first, again)
        assertEquals(1, backend.aiNovelCalls.get())

        // The listing title translated earlier is reused; only a changed synopsis is redone.
        val changed = translator.novel("api/1", "ja", "題名", "新しいあらすじ")
        assertEquals(MetadataTranslator.Translated("AI 題名", "AI 新しいあらすじ"), changed)
        assertEquals(2, backend.aiNovelCalls.get())
        assertEquals("AI 題名", translator.cachedTitle("api/1", "題名"))
        assertEquals("AI 新しいあらすじ", translator.cachedSynopsis("api/1", "新しいあらすじ"))
    }

    @Test
    fun titleFromAListingIsKeptWhenTheSynopsisIsTranslatedLater() = runBlocking {
        val store = MapStore()
        val backend = FakeBackend(MetadataPolicy(ai = false, google = true))
        val translator = MetadataTranslator(store, backend, this, batchWindowMs = 10)
        translator.title(MetadataTranslator.TitleRequest("api/9", "ko", "제목"))
        val novel = translator.novel("api/9", "ko", "제목", "줄거리")
        assertEquals(MetadataTranslator.Translated("G 제목", "G 줄거리"), novel)
        assertEquals(2, backend.googleCalls.get())
        val row = store.rows["api/9"]!!
        assertEquals("google", row.synopsisEngine)
        assertEquals("ko", row.sourceLang)
    }

    @Test
    fun promptsAndReplies() {
        assertTrue(MetadataPrompts.titlesSystemPrompt("zh").contains("Chinese"))
        assertEquals("[\"一\",\"二\"]", MetadataPrompts.titlesUserPrompt(listOf("一", "二")))
        assertEquals(
            listOf("One", "Two"),
            MetadataPrompts.parseTitles("```json\n{\"titles\": [\"One\", \" Two \"]}\n```", 2)
        )
        assertEquals(listOf("A", null), MetadataPrompts.parseTitles("Here: [\"A\", \"\"]", 2))
        assertEquals(listOf(null, null, null), MetadataPrompts.parseTitles("{\"titles\": [\"A\"]}", 3))
        assertEquals(listOf(null), MetadataPrompts.parseTitles("no json", 1))

        assertEquals(
            "Title" to "Line 1\nLine 2",
            MetadataPrompts.parseNovel("{\"title\": \"Title\", \"synopsis\": \"Line 1\\nLine 2\"}")
        )
        assertEquals("T" to null, MetadataPrompts.parseNovel("Sure! {\"title\":\"T\"}"))
        assertEquals(null to null, MetadataPrompts.parseNovel("[]"))
        val user = MetadataPrompts.novelUserPrompt("題", "x".repeat(MetadataPrompts.MAX_SYNOPSIS_CHARS + 50))
        assertTrue(user.length < MetadataPrompts.MAX_SYNOPSIS_CHARS + 40)
    }

    @Test
    fun googleFallbackRunsAFewRequestsAtATime() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val backend = object : MetadataBackend {
            override suspend fun policy() = MetadataPolicy(ai = false, google = true)
            override fun aiModel() = "none"
            override suspend fun aiTitles(lang: String, titles: List<String>): List<String?> = error("no AI")
            override suspend fun aiNovel(lang: String, novelId: String, title: String, synopsis: String) = error("no AI")
            override suspend fun google(lang: String, text: String): String {
                val now = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                kotlinx.coroutines.delay(30)
                running.decrementAndGet()
                return "G $text"
            }
        }
        val translator = MetadataTranslator(MapStore(), backend, this, batchWindowMs = 20)
        val results = (1..12).map { i -> async { translator.title(req(i)) } }.awaitAll()
        assertEquals((1..12).map { "G 標題$it" }, results)
        assertTrue("peak ${peak.get()}", peak.get() in 2..MetadataTranslator.GOOGLE_PARALLEL)
    }

    @Test
    fun aFullQueueDoesNotCancelTheBatchInFlight() = runBlocking {
        val backend = object : MetadataBackend {
            val batches = mutableListOf<List<String>>()
            override suspend fun policy() = MetadataPolicy(ai = true, google = false)
            override fun aiModel() = "gpt-test"
            override suspend fun aiTitles(lang: String, titles: List<String>): List<String?> {
                batches += titles
                kotlinx.coroutines.delay(100)
                return titles.map { "AI $it" }
            }
            override suspend fun aiNovel(lang: String, novelId: String, title: String, synopsis: String) = null to null
            override suspend fun google(lang: String, text: String): String? = null
        }
        val translator = MetadataTranslator(MapStore(), backend, this, batchWindowMs = 20)
        // A small batch goes out after the window ...
        val first = (1..3).map { i -> async { translator.title(req(i)) } }
        kotlinx.coroutines.delay(50)
        // ... and a full batch arrives while it is in flight.
        val second = (4..25).map { i -> async { translator.title(req(i)) } }
        assertEquals((1..25).map { "AI 標題$it" }, (first + second).awaitAll())
        assertEquals(25, backend.batches.sumOf { it.size })
    }
}
