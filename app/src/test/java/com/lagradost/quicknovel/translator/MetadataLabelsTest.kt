package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.NovelMetadataTranslation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class MetadataLabelsTest {
    @Test
    fun onlyCjkLabelsThatAreNotAlreadyEnglish() {
        assertTrue(MetadataLabels.needsTranslation("第三章 出发"))
        assertTrue(MetadataLabels.needsTranslation("玄幻"))
        assertTrue(MetadataLabels.needsTranslation("ファンタジー"))
        assertTrue(MetadataLabels.needsTranslation("판타지"))
        assertTrue(MetadataLabels.needsTranslation("第12章 Re:开始"))
        // provider genre labels: English with the original in brackets
        assertFalse(MetadataLabels.needsTranslation("Fantasy (판타지)"))
        assertFalse(MetadataLabels.needsTranslation("Chapter 12"))
        assertFalse(MetadataLabels.needsTranslation("BL"))
        assertFalse(MetadataLabels.needsTranslation("   "))
        assertFalse(MetadataLabels.needsTranslation("长".repeat(MetadataLabels.MAX_LABEL_CHARS + 1)))
    }

    @Test
    fun requestsPutTheLatestChapterFirstAndDropDuplicates() {
        val requests = MetadataLabels.requests(
            lang = "zh",
            latest = " 第三章 出发 ",
            tags = listOf("玄幻", "Fantasy (玄幻)", "玄幻 ", "都市", "第三章 出发"),
        )
        assertEquals(listOf("第三章 出发", "玄幻", "都市"), requests.map { it.original })
        assertTrue(requests.all { it.lang == "zh" })
        assertEquals("label:zh/玄幻", requests[1].novelId)
    }

    @Test
    fun noLanguageNoRequests() {
        assertTrue(MetadataLabels.requests(null, "第三章", listOf("玄幻")).isEmpty())
        assertTrue(MetadataLabels.requests("zh", null, null).isEmpty())
    }

    @Test
    fun labelIdsNeverCollideWithNovelIds() {
        val id = MetadataLabels.labelId("ja", "異世界")
        assertTrue(id.startsWith("label:"))
        assertFalse(id == TranslatorNovelId.from("Kakuyomu", "異世界"))
    }

    @Test
    fun atMostMaxLabels() {
        val tags = (1..50).map { "标签$it" }
        assertEquals(MetadataLabels.MAX_LABELS, MetadataLabels.requests("zh", null, tags).size)
    }

    @Test
    fun aPageOfLabelsIsOneBatchedCallAndCached() = runBlocking {
        val store = object : MetadataStore {
            val rows = ConcurrentHashMap<String, NovelMetadataTranslation>()
            override suspend fun load(ids: List<String>) = ids.mapNotNull { rows[it] }
            override suspend fun save(rows: List<NovelMetadataTranslation>) = rows.forEach { this.rows[it.novelId] = it }
        }
        val batches = mutableListOf<List<String>>()
        val backend = object : MetadataBackend {
            override suspend fun policy() = MetadataPolicy(ai = true, google = true)
            override fun aiModel() = "m"
            override suspend fun aiTitles(lang: String, titles: List<String>): List<String?> {
                synchronized(batches) { batches += titles }
                return titles.map { "EN $it" }
            }

            override suspend fun aiNovel(lang: String, novelId: String, title: String, synopsis: String) = null to null
            override suspend fun google(lang: String, text: String): String? = null
        }
        val translator = MetadataTranslator(store, backend, this, batchWindowMs = 30)
        val requests = MetadataLabels.requests("zh", "第三章 出发", listOf("玄幻", "都市", "Fantasy (玄幻)"))

        val first = MetadataLabels.translate(requests) { translator.title(it) }
        assertEquals(mapOf("第三章 出发" to "EN 第三章 出发", "玄幻" to "EN 玄幻", "都市" to "EN 都市"), first)
        assertEquals(1, batches.size)

        // Opening the page again: served from the cache, no second call.
        val second = MetadataLabels.translate(requests) { translator.title(it) }
        assertEquals(first, second)
        assertEquals(1, batches.size)
        assertTrue(store.rows.keys.all { it.startsWith("label:zh/") })
    }
}
