package com.lagradost.quicknovel.util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.translator.StoryBibleJson
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.NovelContext
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.ui.glossary.PresetTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Model classes that the release build maps with Jackson by reflection (see proguard-rules.pro),
 * pushed through the app's real mappers. This runs unshrunk; the release mapping
 * (app/build/outputs/mapping/release/mapping.txt) is what shows R8 kept the same classes and
 * fields unrenamed (docs/ci.md).
 */
class JsonModelsRoundTripTest {
    @Test
    fun githubReleaseFromApiResponse() {
        // Trimmed shape of api.github.com/repos/<repo>/releases/latest.
        val json = """
            {"url":"https://api.github.com/repos/Techno453/QuickNovel/releases/1","tag_name":"v3.8.1",
             "target_commitish":"master","name":"QuickNovel 3.8.1","prerelease":false,"body":"notes",
             "assets":[
               {"name":"QuickNovel-3.8.1-mapping.txt","size":1234,"content_type":"text/plain",
                "browser_download_url":"https://github.com/Techno453/QuickNovel/releases/download/v3.8.1/QuickNovel-3.8.1-mapping.txt"},
               {"name":"QuickNovel-3.8.1-arm64-v8a.apk","size":30000000,"content_type":"application/vnd.android.package-archive",
                "browser_download_url":"https://github.com/Techno453/QuickNovel/releases/download/v3.8.1/QuickNovel-3.8.1-arm64-v8a.apk","download_count":3},
               {"name":"QuickNovel-3.8.1-universal.apk","size":60000000,"content_type":"application/vnd.android.package-archive",
                "browser_download_url":"https://github.com/Techno453/QuickNovel/releases/download/v3.8.1/QuickNovel-3.8.1-universal.apk"}
             ]}
        """.trimIndent()
        val release = InAppUpdater.parseRelease(json)
        assertEquals("v3.8.1", release.tag_name)
        assertEquals("master", release.target_commitish)
        assertEquals("notes", release.body)
        assertEquals(3, release.assets.size)
        assertEquals(30000000, release.assets[1].size)
        assertEquals("application/vnd.android.package-archive", release.assets[1].content_type)
        val update = InAppUpdater.stableUpdate(release, "3.8.0", listOf("arm64-v8a", "armeabi-v7a"))
        assertTrue(update.shouldUpdate)
        assertTrue(update.updateURL!!.endsWith("/QuickNovel-3.8.1-arm64-v8a.apk"))
    }

    @Test
    fun storyBibleExportImportKeepsEveryField() {
        val term = GlossaryTerm(
            id = 7, novelId = "n", source = "林风", english = "Lin Feng",
            category = GlossaryTerm.CATEGORY_CHARACTER, description = "desc", aliases = """["小风"]""",
            gender = GlossaryTerm.GENDER_MALE, notes = "the hero", firstChapter = 3, autoAdded = true, locked = true,
        )
        val context = NovelContext(
            novelId = "n", premise = "A premise.", styleNotes = "terse", nameStyle = "pinyin", honorificStyle = "keep",
            updatedAt = 5, analyzedAt = 99, analysisChapters = 4,
        )
        val json = StoryBibleJson.export(listOf(term), context)
        // The JSON key is the historical "chinese" (@JsonProperty on a renamed Kotlin property).
        assertTrue(json, json.contains("\"chinese\" : \"林风\""))
        val imported = StoryBibleJson.import(json, "other")
        assertEquals(listOf(term.copy(id = 0, novelId = "other")), imported.terms)
        // analyzedAt / analysisChapters are @JsonIgnore'd: not exported.
        assertEquals(context.copy(novelId = "other", analyzedAt = 0, analysisChapters = 0), imported.context)
    }

    @Test
    fun bundledGlossaryPresetsParse() {
        val file = listOf("src/main/assets/common_cultivation_terms.json", "app/src/main/assets/common_cultivation_terms.json")
            .map(::File).first { it.exists() }
        val presets: List<PresetTerm> = jacksonObjectMapper().readValue(file.readText())
        assertTrue(presets.size > 10)
        assertTrue(presets.all { it.chinese.isNotBlank() && it.english.isNotBlank() && it.category.isNotBlank() })
    }

    @Test
    fun persistedLibraryDataRoundTrips() {
        // DownloadData is stored per novel in SharedPreferences through DataStore.mapper.
        val data = DownloadFragment.DownloadData(
            source = "https://example.com/novel", name = "Novel", author = "Author", posterUrl = null,
            rating = 80, peopleVoted = 10, views = 1000, synopsis = "s", tags = listOf("a", "b"),
            apiName = "RoyalRoad", lastUpdated = 1L, lastDownloaded = 2L, posterHeaders = mapOf("Referer" to "x"),
        )
        val back: DownloadFragment.DownloadData = DataStore.mapper.readValue(DataStore.mapper.writeValueAsString(data))
        assertEquals(data, back)

        // ResultCached (history and bookmarks) has no @JsonProperty: Jackson binds its
        // constructor by the parameter names in its kotlin.Metadata.
        val stored = """
            {"source":"https://example.com/novel","name":"Novel","apiName":"RoyalRoad","id":42,"author":null,
             "poster":"https://example.com/p.jpg","tags":["a"],"rating":90,"totalChapters":120,"cachedTime":123,
             "synopsis":"s","posterHeaders":null,"imported":false,"currentTotalChapters":120,"lastChapterRead":0}
        """.trimIndent()
        val cached: ResultCached = DataStore.mapper.readValue(stored)
        assertEquals(
            ResultCached(
                source = "https://example.com/novel", name = "Novel", apiName = "RoyalRoad", id = 42, author = null,
                poster = "https://example.com/p.jpg", tags = listOf("a"), rating = 90, totalChapters = 120,
                cachedTime = 123, synopsis = "s", posterHeaders = null,
            ).toString(),
            cached.toString(),
        )
    }
}
