package com.lagradost.quicknovel.ui.search

import com.lagradost.quicknovel.translator.TranslationLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * How one source is searched with several candidate queries, and where its section goes in the
 * results (docs/search.md). Pure, JVM-tested.
 */
object CandidateSearch {
    /** Most candidates that returned (non-matching) results before a source gives up trying more. */
    const val MAX_ANSWERED = 3

    /**
     * Result of searching one source. [items] are de-duplicated by URL, the matching candidate's
     * results first, then the others in candidate order; [answeredBy] is the candidate whose
     * results matched the title, else the first candidate that returned results (null if none
     * did); [tried] the candidates actually sent; [error] the first failure, set only when every
     * candidate failed.
     */
    data class Outcome<T>(
        val items: List<T>,
        val answeredBy: String?,
        val tried: List<String>,
        val error: Throwable?,
    )

    /**
     * Sends [candidates] to one source one after another ([pauseMs] between requests, the
     * source's rate limit). Stops at the first candidate with a title match (a result whose
     * [nameOf] contains the candidate, see [titleMatches]), or after [MAX_ANSWERED] candidates
     * returned results without one. Sites with fuzzy search answer almost any query, so a wrong
     * first guess from the title lookup must not hide the right second candidate. Everything
     * returned is merged, the matching candidate's results first, de-duplicated by [urlOf]. A
     * failing candidate does not stop the others.
     */
    suspend fun <T> run(
        candidates: List<String>,
        urlOf: (T) -> String,
        nameOf: (T) -> String,
        pauseMs: Long = 0,
        search: suspend (String) -> List<T>,
    ): Outcome<T> {
        val answered = ArrayList<Pair<String, List<T>>>()
        val tried = ArrayList<String>()
        var firstError: Throwable? = null
        var anySuccess = false
        var matched: String? = null
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
            if (found.isEmpty()) continue
            answered += candidate to found
            if (found.any { titleMatches(nameOf(it), candidate) }) {
                matched = candidate
                break
            }
            if (answered.size >= MAX_ANSWERED) break
        }
        val ordered = if (matched == null) answered
        else answered.filter { it.first == matched } + answered.filter { it.first != matched }
        val seen = HashSet<String>()
        val items = ArrayList<T>()
        for ((_, found) in ordered) for (item in found) if (seen.add(urlOf(item))) items += item
        val answeredBy = matched ?: answered.firstOrNull()?.first
        return Outcome(items, answeredBy, tried, if (anySuccess) null else firstError)
    }

    /** True if [name] contains [candidate], ignoring case and whitespace. */
    fun titleMatches(name: String, candidate: String): Boolean {
        val needle = squash(candidate)
        return needle.isNotEmpty() && squash(name).contains(needle)
    }

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

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
