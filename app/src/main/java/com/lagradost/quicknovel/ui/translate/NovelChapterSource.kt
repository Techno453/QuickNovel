package com.lagradost.quicknovel.ui.translate

import android.util.Log
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.imported.ImportedBooks
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.translator.ChapterTextSource
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a novel's chapter list from its translator novel id ("apiName/novelName") so screens
 * that only know the id (the Glossary) can start an analysis job.
 *
 * Fast path: lists remembered by the translate dialog. Fallback: the novel URL from history,
 * bookmarks or downloads, loaded through the provider (APIRepository caches recent loads).
 * Imported books ([ImportedBooks]) are resolved from their stored EPUB.
 */
object NovelChapterSource {
    private const val TAG = "NovelChapterSource"

    data class Entry(
        val apiName: String,
        val novelName: String,
        val author: String?,
        val chapters: List<ChapterData>,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    fun remember(novelId: String, apiName: String, novelName: String, author: String?, chapters: List<ChapterData>) {
        if (chapters.isNotEmpty()) cache[novelId] = Entry(apiName, novelName, author, chapters)
    }

    fun cached(novelId: String): Entry? = cache[novelId]

    /** Provider name of a translator novel id, or null if no provider matches. */
    fun apiNameOf(novelId: String): String? =
        if (ImportedBooks.isImported(novelId)) ImportedBooks.apiName
        else Apis.apis.map { it.name }.filter { novelId.startsWith("$it/") }.maxByOrNull { it.length }

    /**
     * Normalised source language (zh / ja / ko) of a novel, or null if it is not translatable:
     * the provider's declared language, or an imported book's detected / chosen language.
     */
    fun langOf(novelId: String?, apiName: String? = null): String? {
        if (novelId != null && ImportedBooks.isImported(novelId)) return ImportedBooks.lang(novelId)
        return TranslationLanguages.langOfApi(apiName ?: novelId?.let(::apiNameOf))
    }

    /**
     * Chapter sources of a novel for the translation jobs: the imported book's EPUB, or the
     * provider through [loadOnline] (which keeps the service's Cloudflare handling).
     */
    fun textSource(
        ctx: android.content.Context,
        novelId: String,
        apiName: String,
        chapters: List<ChapterData>,
        loadOnline: suspend (ChapterData) -> String?,
    ): ChapterTextSource = ImportedBooks.textSource(ctx, novelId) ?: object : ChapterTextSource {
        override val lang: String? = TranslationLanguages.langOfApi(apiName)
        override val size: Int get() = chapters.size
        override suspend fun load(index: Int): String? = chapters.getOrNull(index)?.let { loadOnline(it) }
    }

    suspend fun resolve(novelId: String): Entry? {
        if (ImportedBooks.isImported(novelId)) {
            // Always from the stored file (cheap; the opened book is cached), so a re-import is seen.
            val ctx = BaseApplication.context ?: return null
            val record = ImportedBooks.record(novelId) ?: return null
            val chapters = withContext(Dispatchers.IO) { ImportedBooks.chapterList(ctx, record) }
            if (chapters.isEmpty()) return null
            return Entry(ImportedBooks.apiName, record.name, record.author, chapters)
        }
        cache[novelId]?.let { return it }
        val apiName = apiNameOf(novelId) ?: return null
        val novelName = novelId.removePrefix("$apiName/")
        val (url, author) = findUrl(apiName, novelName) ?: return null
        return try {
            val api = Apis.getApiFromNameOrNull(apiName) ?: return null
            val response = (api.load(url) as? Resource.Success)?.value as? StreamResponse ?: return null
            Entry(apiName, novelName, response.author ?: author, response.data).also { cache[novelId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not load chapters for $novelId", e)
            null
        }
    }

    private fun findUrl(apiName: String, novelName: String): Pair<String, String?>? {
        for (folder in listOf(HISTORY_FOLDER, RESULT_BOOKMARK)) {
            for (key in BaseApplication.getKeys(folder).orEmpty()) {
                val r = BaseApplication.getKey<ResultCached>(key) ?: continue
                if (r.apiName == apiName && r.name == novelName) return r.source to r.author
            }
        }
        for (key in BaseApplication.getKeys(DOWNLOAD_FOLDER).orEmpty()) {
            val d = BaseApplication.getKey<DownloadFragment.DownloadData>(key) ?: continue
            if (d.apiName == apiName && d.name == novelName) return d.source to d.author
        }
        return null
    }
}
