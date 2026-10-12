package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.imported.ImportedBookId
import com.lagradost.quicknovel.translator.db.TranslatedChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository's decisions (serve stored, fetch, source hash, stale handling, translate and
 * store) are the same for an imported book as for an online novel: only the chapter source
 * differs. Same script, same chapter texts, one online id and one imported id: same events.
 */
class ImportedChapterPipelineTest {
    /** In-memory translated_chapters. */
    private class FakeStore : ChapterTranslationRepository.ChapterStore {
        val rows = HashMap<Pair<String, Int>, TranslatedChapter>()
        override suspend fun entry(novelId: String, chapterIndex: Int) = rows[novelId to chapterIndex]
        override suspend fun markSourceState(novelId: String, chapterIndex: Int, hash: String, stale: Boolean) {
            rows[novelId to chapterIndex]?.let { rows[novelId to chapterIndex] = it.copy(sourceHash = hash, stale = stale) }
        }
    }

    /** A chapter source whose texts can be edited (a re-imported file / a changed web page). */
    private class FakeSource(val chapters: MutableList<String>, override val lang: String?) : ChapterTextSource {
        var loads = 0
        override val size get() = chapters.size
        override suspend fun load(index: Int): String? {
            loads++
            return chapters.getOrNull(index)
        }
    }

    private fun runScript(novelId: String, source: FakeSource): List<String> = runBlocking {
        val store = FakeStore()
        val events = mutableListOf<String>()
        suspend fun translate(chapter: Int, retranslateStale: Boolean = false): String? =
            ChapterTranslationRepository.translateIfMissingCore(
                store, novelId, chapter, retranslateStale,
                fetchSource = { source.load(chapter) },
                beforeTranslate = { events += "analyze-before #$chapter" },
            ) { text, hash ->
                assertEquals(SourceHash.of(text), hash)
                val english = "EN(" + TranslationEngine.htmlToPlainText(text) + ")"
                store.rows[novelId to chapter] = TranslatedChapter(novelId, chapter, english, sourceLang = source.lang!!, sourceHash = hash)
                events += "translated #$chapter"
                english
            }

        events += "1: " + translate(0)
        events += "2: " + translate(0) + " loads=${source.loads}" // served from the store, no fetch
        events += "3: " + translate(1)

        // The source of chapter 1 changes (edited file re-imported / page edited).
        source.chapters[1] = "<p>夜里下起了大雨。</p>"
        events += "check: " + ChapterTranslationRepository.checkSourceHashCore(store, novelId, 1, source.load(1)!!)
        events += "stale=${store.rows[novelId to 1]!!.stale}"
        events += "4: " + translate(1) // still served (stale is never re-translated automatically)
        events += "5: " + translate(1, retranslateStale = true)
        events += "stale=${store.rows[novelId to 1]!!.stale}"

        // Stale, but the source went back to the translated text: kept, no paid call.
        store.markSourceState(novelId, 0, store.rows[novelId to 0]!!.sourceHash, stale = true)
        events += "6: " + translate(0, retranslateStale = true)
        events += "stale=${store.rows[novelId to 0]!!.stale}"

        // A chapter that cannot be loaded.
        events += "7: " + translate(9)
        events
    }

    private fun chapters() = mutableListOf("<p>清晨的山路上起了薄雾。</p>", "<p>夜里下起了雨。</p>")

    @Test
    fun importedChaptersGoThroughTheSamePipeline() {
        val online = runScript("Ixdzs/星河旅人", FakeSource(chapters(), "zh"))
        val imported = runScript(ImportedBookId.of("星河旅人", null), FakeSource(chapters(), "zh"))
        assertEquals(online, imported)

        // and the script did what the pipeline promises
        assertEquals("1: EN(清晨的山路上起了薄雾。)", imported[2])
        assertTrue(imported[3].startsWith("2: EN(清晨的山路上起了薄雾。) loads=1"))
        assertTrue("check: CHANGED" in imported)
        assertTrue("4: EN(夜里下起了雨。)" in imported)
        assertTrue("5: EN(夜里下起了大雨。)" in imported)
        assertEquals("chapter 0 translated once (6 kept it)", 1, imported.count { it == "translated #0" })
        assertFalse("stale=true" == imported.last { it.startsWith("stale=") })
        assertEquals("7: null", imported.last())
    }
}
