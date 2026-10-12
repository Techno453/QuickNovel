package com.lagradost.quicknovel.ui.search

import com.lagradost.quicknovel.translator.TranslationLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * How one source is searched with several candidate queries, and where its section goes in the
 * results (docs/search.md). Pure, JVM-tested.
 */
object CandidateSearch {
    /**
     * Result of searching one source. [items] are de-duplicated by URL, in candidate order;
     * [answeredBy] is the candidate that returned results (null if none did); [tried] the
     * candidates actually sent; [error] the first failure, set only when every candidate failed.
     */
    data class Outcome<T>(
        val items: List<T>,
        val answeredBy: String?,
        val tried: List<String>,
        val error: Throwable?,
    )

    /**
     * Sends [candidates] to one source one after another ([pauseMs] between requests, the
     * source's rate limit) and stops after the first candidate that returns results. Results
     * are merged and de-duplicated by [urlOf]. A failing candidate does not stop the others.
     */
    suspend fun <T> run(
        candidates: List<String>,
        urlOf: (T) -> String,
        pauseMs: Long = 0,
        search: suspend (String) -> List<T>,
    ): Outcome<T> {
        val seen = HashSet<String>()
        val items = ArrayList<T>()
        val tried = ArrayList<String>()
        var firstError: Throwable? = null
        var anySuccess = false
        for ((i, candidate) in candidates.withIndex()) {
            if (i > 0 && pauseMs > 0) delay(pauseMs)
            tried += candidate
            val found = try {
                search(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                continue
            }
            anySuccess = true
            for (item in found) if (seen.add(urlOf(item))) items += item
            if (found.isNotEmpty()) return Outcome(items, candidate, tried, null)
        }
        return Outcome(items, null, tried, if (anySuccess) null else firstError)
    }

    /**
     * Sort key of a source's section: raw CJK sources first, then English, then other
     * languages; sections with results above empty or failed ones. Stable within a group.
     */
    fun sectionRank(sourceLang: String?, hasResults: Boolean): Int {
        val group = when {
            TranslationLanguages.normalize(sourceLang) != null -> 0
            SearchLanguage.baseLang(sourceLang) == "en" -> 1
            else -> 2
        }
        return if (hasResults) group else 10 + group
    }
}
