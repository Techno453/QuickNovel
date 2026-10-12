package com.lagradost.quicknovel.ui.browse

import java.text.Normalizer

/**
 * The "All sources" feed (pure, JVM-tested; docs/browse.md): every source's default listing
 * interleaved by rank (rank 1 of each source, then rank 2 of each, ...). No cross-site score is
 * invented, the sites' numbers are not comparable.
 *
 * - Sources load independently. A source joins the feed when its first page arrives (its rank 1
 *   goes into the next round); a source that fails drops out and is counted in [unavailable]
 *   without blocking the others.
 * - [fill] appends whole rounds and never reorders what is already in the feed. A round is only
 *   placed when every loaded source still has an item (or is finished / failed); otherwise the
 *   sources that ran out are returned by [plan] so their next page is fetched first.
 * - Duplicates (aggregators mirroring the same catalogue) are merged on the normalised title
 *   plus the author when both copies have one; the copy with the most chapters is kept (the
 *   first one seen when unknown) and [Entry.alsoOn] lists the other sources.
 */
class CombinedBrowse<T>(
    /** Source names in feed order (the order inside every round). */
    sourceOrder: List<String>,
    private val title: (T) -> String,
    private val author: (T) -> String? = { null },
    private val chapters: (T) -> Int? = { null },
    /** Folds script variants before comparing titles (e.g. Traditional to Simplified Chinese). */
    private val fold: (String) -> String = { it },
) {
    data class Entry<T>(
        val item: T,
        val source: String,
        /** Other sources with the same novel (shown as "also on N other sources"). */
        val alsoOn: List<String> = emptyList(),
    )

    enum class Status { LOADING, READY, FAILED, FINISHED }

    private class SourceState<T>(val name: String) {
        var status = Status.LOADING
        val items = ArrayList<T>()
        var cursor = 0
        /** Last page received (1-based); 0 = none yet. */
        var page = 0
        var fetching = true
        /** Index of the first round this source took part in. */
        var joinedRound = -1
    }

    private val sources = LinkedHashMap<String, SourceState<T>>().apply {
        sourceOrder.distinct().forEach { put(it, SourceState(it)) }
    }
    private val entries = ArrayList<Entry<T>>()
    private val byKey = HashMap<String, MutableList<Int>>()
    private var rounds = 0

    val feed: List<Entry<T>> get() = entries

    /** Sources that failed (their items already in the feed stay). */
    val unavailable: Int get() = sources.values.count { it.status == Status.FAILED }

    /** True when no source can add anything any more. */
    val isComplete: Boolean
        get() = sources.values.all {
            it.status == Status.FAILED || (it.status == Status.FINISHED && it.cursor >= it.items.size)
        }

    /** True while some source has not answered its first page yet. */
    val isLoadingFirstPages: Boolean get() = sources.values.any { it.status == Status.LOADING }

    fun statusOf(source: String): Status? = sources[source]?.status

    /** Page [page] of [source] arrived; an empty page means the source has nothing more. */
    fun onPage(source: String, page: Int, items: List<T>) {
        val state = sources[source] ?: return
        state.fetching = false
        if (state.status == Status.FAILED) return
        state.page = maxOf(state.page, page)
        if (items.isEmpty()) {
            state.status = Status.FINISHED
            return
        }
        state.items.addAll(items)
        state.status = Status.READY
    }

    /** [source] failed or timed out: it drops out of the feed (what it already gave stays). */
    fun onFailure(source: String) {
        val state = sources[source] ?: return
        state.fetching = false
        state.status = Status.FAILED
    }

    /**
     * Next pages to fetch: (source, page) for every ready source whose items are all placed and
     * that is not already fetching. Callers mark the fetch with [onFetchStarted].
     */
    fun plan(): List<Pair<String, Int>> = sources.values
        .filter { it.status == Status.READY && !it.fetching && it.cursor >= it.items.size }
        .map { it.name to it.page + 1 }

    fun onFetchStarted(source: String) {
        sources[source]?.fetching = true
    }

    /**
     * Appends rounds until the feed has at least [target] entries or a ready source needs its
     * next page. Returns the number of entries added. While some source has not answered its
     * first page nothing is placed unless [allowPartial] (the caller's grace period is over):
     * otherwise the first source to answer would fill the whole first screen on its own.
     * Sources answering later join at the next round.
     */
    fun fill(target: Int, allowPartial: Boolean = false): Int {
        if (!allowPartial && isLoadingFirstPages) return 0
        val before = entries.size
        while (entries.size < target) {
            val active = sources.values.filter { it.status == Status.READY || (it.status == Status.FINISHED && it.cursor < it.items.size) }
            if (active.isEmpty()) break
            // Wait for a source that ran out but has more pages: placing the round without it
            // would push its next items below lower-ranked ones from the others.
            if (active.any { it.status == Status.READY && it.cursor >= it.items.size }) break
            for (state in active) {
                if (state.cursor >= state.items.size) continue
                if (state.joinedRound < 0) state.joinedRound = rounds
                place(state.items[state.cursor], state.name)
                state.cursor++
            }
            rounds++
        }
        return entries.size - before
    }

    private fun place(item: T, source: String) {
        val key = normalize(fold(title(item)))
        if (key.isEmpty()) {
            entries += Entry(item, source)
            return
        }
        val candidates = byKey.getOrPut(key) { mutableListOf() }
        val existingIndex = candidates.firstOrNull { sameNovel(entries[it].item, item) }
        if (existingIndex == null) {
            candidates += entries.size
            entries += Entry(item, source)
            return
        }
        val existing = entries[existingIndex]
        if (existing.source == source) return // the same source listed it twice
        val newer = chapters(item)
        val kept = chapters(existing.item)
        entries[existingIndex] = if (newer != null && (kept == null || newer > kept)) {
            Entry(item, source, (existing.alsoOn + existing.source).distinct())
        } else {
            existing.copy(alsoOn = (existing.alsoOn + source).distinct())
        }
    }

    /** Whether two items count as the same novel (title, plus author when both have one). */
    fun sameNovel(a: T, b: T): Boolean {
        if (normalize(fold(title(a))) != normalize(fold(title(b)))) return false
        val authorA = author(a)?.let { normalize(fold(it)) }?.ifEmpty { null }
        val authorB = author(b)?.let { normalize(fold(it)) }?.ifEmpty { null }
        return authorA == null || authorB == null || authorA == authorB
    }

    companion object {
        /** NFKC, lower case, only letters and digits (drops spaces, punctuation and brackets). */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
                .filter { it.isLetterOrDigit() }

        /** First number in a "latest chapter" text such as "384회", "第120章" or "120 episodes". */
        fun chapterCount(text: String?): Int? =
            text?.let { Regex("""\d{1,6}""").find(it.replace(",", ""))?.value?.toIntOrNull() }
    }
}
