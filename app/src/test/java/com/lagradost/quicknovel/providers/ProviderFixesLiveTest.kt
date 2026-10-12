package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Live checks for the 2026-10 provider fixes: Quanben chapter names line up with the chapter
 * pages, Syosetu has real episode titles across table-of-contents pages, and status/tags come
 * through for 69shuba, Quanben and Syosetu. Skipped unless run with `-Dlive=1` (or `LIVE=1`):
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ProviderFixesLiveTest*' -Dlive=1
 */
class ProviderFixesLiveTest {
    companion object {
        private val enabled: Boolean
            get() = (System.getProperty("live") ?: System.getenv("LIVE"))
                ?.let { it.isNotBlank() && it != "0" && !it.equals("false", true) } == true

        @BeforeClass
        @JvmStatic
        fun initHttp() = CjkProvidersLiveSmokeTest.initHttp(force = false)
    }

    private fun log(msg: String) = println("[live fixes] $msg")

    @Test
    fun quanbenChapterNamesMatchTheirPages() = runBlocking {
        assumeTrue(enabled)
        val api = QuanbenProvider()
        val load = api.load("https://www.quanben.io/n/douluodalu/") as StreamResponse
        log("Quanben douluodalu: ${load.data.size} chapters, status ${load.status}, tags ${load.tags}")
        assertTrue("expected the full list, got ${load.data.size}", load.data.size >= 688)
        assertEquals(ReleaseStatus.Completed, load.status)
        // The heading of chapter pages 1, 2, 300 and the last must be the name we list for them.
        for (index in listOf(0, 1, 299, load.data.lastIndex)) {
            val chapter = load.data[index]
            val heading = MainActivity.app.get(chapter.url).document.selectFirst("h1")?.text()?.trim()
            log("  #${index + 1} '${chapter.name}' -> page heading '$heading' (${chapter.url})")
            assertEquals(chapter.url, chapter.name, heading)
            delay(300)
        }
    }

    @Test
    fun syosetuRealTitlesStatusAndTags() = runBlocking {
        assumeTrue(enabled)
        val api = SyosetuProvider()
        val done = api.load("https://ncode.syosetu.com/n9669bk/") as StreamResponse
        log("Syosetu n9669bk: ${done.data.size} chapters, status ${done.status}, tags ${done.tags}")
        assertEquals(286, done.data.size)
        assertEquals(ReleaseStatus.Completed, done.status)
        assertTrue(done.tags.orEmpty().isNotEmpty())
        assertTrue(done.data.none { it.name.matches(Regex("""Episode \d+""")) })
        for (index in listOf(0, 150, 285)) {
            val chapter = done.data[index]
            val heading = MainActivity.app.get(chapter.url).document.selectFirst("h1.p-novel__title")?.text()?.trim()
            log("  #${index + 1} '${chapter.name}' -> page heading '$heading'")
            assertEquals(chapter.name, heading)
            delay(300)
        }

        val serialized = api.load("https://ncode.syosetu.com/n3159fs/") as StreamResponse
        log("Syosetu n3159fs: ${serialized.data.size} chapters, status ${serialized.status}, last '${serialized.data.lastOrNull()?.name}'")
        assertEquals(ReleaseStatus.Ongoing, serialized.status)
        assertTrue(serialized.data.size > 500)
        assertTrue(serialized.data.last().url.endsWith("/${serialized.data.size}/"))

        val oneShot = api.load("https://ncode.syosetu.com/n7399mv/") as StreamResponse
        val text = api.loadHtml(oneShot.data.single().url).orEmpty()
        log("Syosetu n7399mv (短編): ${oneShot.data.size} chapter, status ${oneShot.status}, ${text.length} chars")
        assertEquals(ReleaseStatus.Completed, oneShot.status)
        assertTrue(text.length > 1000)
    }

    @Test
    fun shubaStatusAndTags() = runBlocking {
        assumeTrue(enabled)
        val api = BiqugeProvider()
        val load = api.load("https://www.69shuba.com/book/90435.htm") as StreamResponse
        log("69shuba 90435: ${load.data.size} chapters, status ${load.status}, tags ${load.tags}")
        assertTrue(load.status != null)
        assertTrue(load.tags.orEmpty().size >= 2)
        assertTrue(load.data.isNotEmpty())
    }
}
