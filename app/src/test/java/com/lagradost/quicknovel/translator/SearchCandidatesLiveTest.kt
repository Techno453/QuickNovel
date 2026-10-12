package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.bench.BenchEnv
import com.lagradost.quicknovel.ui.search.SearchLanguage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Live checks of the translated search for "Above the sky" → 高天之上 (docs/search.md). Skipped
 * unless `-Dlive=1` / `LIVE=1`:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*SearchCandidatesLiveTest*' -Dlive=1
 *
 * - AI path: needs `OPENAI_API_KEY` in the environment or the repo-root `bench.local.properties`
 *   (loaded like the benchmark harness; the key is never printed). One short call, fractions of
 *   a cent. Skipped without a key.
 * - NovelUpdates path: plain client, no key. Skipped (not failed) when NovelUpdates blocks or
 *   challenges this network.
 */
class SearchCandidatesLiveTest {
    private val live: Boolean
        get() = (System.getProperty("live") ?: System.getenv("LIVE"))?.trim() in setOf("1", "true", "yes")

    private val query = "Above the sky"
    private val target = SearchLanguage.CHINESE.code!!

    @Test
    fun aiCandidatesContainTheOriginalTitle() = runBlocking {
        assumeTrue("live tests off (-Dlive=1)", live)
        val env = BenchEnv.load()
        val key = env.key()
        assumeTrue("no OPENAI_API_KEY in the environment or bench.local.properties", key != null)
        val model = System.getProperty("live.searchModel")?.takeIf { it.isNotBlank() } ?: LlmConfig.DEFAULT_MODEL
        val engine = TranslationEngine(apiKey = key!!, model = model, usageSink = UsageSink { })
        val sources = object : SearchQueryTranslator.Sources {
            override suspend fun ai(query: String, lang: String) =
                engine.completeShort(query, PromptTemplates.searchQuerySystemPrompt(lang))
            override suspend fun google(query: String, lang: String): String? = null
            override suspend fun titleIndex(query: String): List<String> = emptyList()
        }
        val candidates = SearchQueryTranslator.computeCandidates(query, target, sources)
        println("[live] AI candidates ($model) for '$query' → ${candidates.all}")
        assertTrue("AI candidates ${candidates.all} lack 高天之上", "高天之上" in candidates.all)
    }

    @Test
    fun novelUpdatesGivesTheOriginalTitleOrSkips() = runBlocking {
        assumeTrue("live tests off (-Dlive=1)", live)
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val fetcher = NovelUpdatesIndex.Fetcher { url ->
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            client.newCall(request).execute().use { r ->
                NovelUpdatesIndex.Page(r.code, { r.header(it) }, r.body?.string().orEmpty())
            }
        }
        val result = NovelUpdatesIndex.lookup(query, fetcher)
        println("[live] NovelUpdates '$query' → ${result.names} (failure: ${result.failure})")
        assumeTrue("NovelUpdates unreachable from here: ${result.failure}", result.failure == null)
        val zh = NovelUpdatesParser.namesForLanguage(result.names, target)
        assertTrue("NovelUpdates names $zh lack 高天之上", "高天之上" in zh)
    }
}
