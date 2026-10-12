package com.lagradost.quicknovel.ui.translate

import android.util.Log
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a novel's chapter list from its translator novel id ("apiName/novelName") so screens
 * that only know the id (the Glossary) can start an analysis job.
 *
 * Fast path: lists remembered by the translate dialog. Fallback: the novel URL from history,
 * bookmarks or downloads, loaded through the provider (APIRepository caches recent loads).
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
        Apis.apis.map { it.name }.filter { novelId.startsWith("$it/") }.maxByOrNull { it.length }

    suspend fun resolve(novelId: String): Entry? {
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
