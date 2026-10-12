package com.lagradost.quicknovel.ui.browse

/**
 * Multi-select genre filter of a source's browse page (docs/browse.md, "Per-source page"). Pure,
 * JVM-tested in `TagFilterTest`.
 *
 * Providers take one tag per request (`MainAPI.loadMainPage(page, mainCategory, orderBy, tag)`),
 * so several selected genres are loaded as one listing per genre (page n of each) and merged by
 * rank: rank 1 of each genre, then rank 2 of each, ... (the round order of the All sources feed),
 * without repeating a URL.
 *
 * A selection is a sorted list of indexes into the provider's `tags`. When index 0 is the
 * provider's "no genre filter" entry ("All", or an empty value), it is never part of a selection:
 * the empty selection stands for it. Otherwise (a site without an all-genre list, e.g. Naver,
 * whose default is Fantasy) the selection is never empty and falls back to index 0.
 */
object TagFilter {
    /** Most genre listings loaded (and merged) at once; further selected genres are not loaded. */
    const val MAX_LOADED = 4

    /** Longest list of genres spelled out in the summary; more are counted. */
    const val SUMMARY_NAMES = 2

    /** Whether the provider's first tag means "no genre filter": an "All" label or an empty value. */
    fun firstIsNoFilter(label: String?, value: String?): Boolean {
        if (label == null) return false
        if (value != null && value.isBlank()) return true
        return label.trim().equals("All", ignoreCase = true)
    }

    /**
     * [selected] made canonical for a provider with [tagCount] tags: valid indexes only, distinct,
     * sorted; without index 0 when it is the no-filter entry, and `[0]` instead of nothing when it
     * is not.
     */
    fun normalize(selected: Collection<Int>, tagCount: Int, firstIsNoFilter: Boolean): List<Int> {
        val valid = selected.filter { it in 0 until tagCount }.distinct().sorted()
        return when {
            firstIsNoFilter -> valid.filter { it != 0 }
            valid.isEmpty() && tagCount > 0 -> listOf(0)
            else -> valid
        }
    }

    /** Selection after tapping chip [index] in the sheet; the no-filter chip clears the others. */
    fun toggle(selected: List<Int>, index: Int, firstIsNoFilter: Boolean): List<Int> = when {
        firstIsNoFilter && index == 0 -> emptyList()
        index in selected -> selected - index
        else -> (selected + index).distinct().sorted()
    }

    /** Tag indexes actually loaded for [selected]: the first [MAX_LOADED], or the default (0). */
    fun loaded(selected: List<Int>): List<Int> =
        selected.distinct().sorted().take(MAX_LOADED).ifEmpty { listOf(0) }

    /** True when more genres are selected than are loaded. */
    fun overCap(selected: List<Int>): Boolean = selected.distinct().size > MAX_LOADED

    /** The tag part of a cache key: indexes sorted, joined with '+' ("3" for one tag, "2+5+7"). */
    fun tagSetKey(tags: Collection<Int>): String =
        tags.distinct().sorted().ifEmpty { listOf(0) }.joinToString("+")

    /**
     * Key of one listing page in the Browse cache (memory and disk). With a single tag it is the
     * key used before multi-select ("v1|api|category,order,tag|page"), so cached pages stay valid.
     */
    fun pageKey(api: String, category: Int, orderBy: Int, tags: Collection<Int>, page: Int): String =
        "v1|$api|$category,$orderBy,${tagSetKey(tags)}|$page"

    /**
     * Interleaves the pages of several genre listings by rank (rank 1 of each list in the given
     * order, then rank 2 of each, ...), skipping URLs already placed.
     */
    fun <T> merge(pages: List<List<T>>, urlOf: (T) -> String): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>(pages.sumOf { it.size })
        val longest = pages.maxOfOrNull { it.size } ?: 0
        for (rank in 0 until longest) {
            for (page in pages) {
                val item = page.getOrNull(rank) ?: continue
                if (seen.add(urlOf(item))) out += item
            }
        }
        return out
    }

    /** How the genre chip on the source page reads. */
    sealed class Summary {
        /** No genre filter: "All genres". */
        object All : Summary()

        /** The provider's no-filter default when it is not a bare "All" (e.g. "Recommended"). */
        data class Default(val label: String) : Summary()

        /** Up to [SUMMARY_NAMES] genres: "Genres: Fantasy, Xianxia". */
        data class Names(val names: List<String>) : Summary()

        /** More genres: "Genres: 3 selected". */
        data class Count(val count: Int) : Summary()
    }

    /** [selected] (normalized) of the provider's tag [labels], summarised for the chip. */
    fun summary(labels: List<String>, selected: List<Int>, firstIsNoFilter: Boolean): Summary {
        if (selected.isEmpty()) {
            val first = labels.firstOrNull()?.trim().orEmpty()
            return if (!firstIsNoFilter || first.isEmpty() || first.equals("All", ignoreCase = true)) Summary.All
            else Summary.Default(shortLabel(first))
        }
        if (selected.size > SUMMARY_NAMES) return Summary.Count(selected.size)
        return Summary.Names(selected.mapNotNull { labels.getOrNull(it) }.map { shortLabel(it) })
    }

    /** "Fantasy (판타지)" → "Fantasy": the bracketed original name is left out of the summary. */
    fun shortLabel(label: String): String = label.substringBefore(" (").trim().ifEmpty { label.trim() }

    /** "category,order,tags" as stored per source ("0,0,3" for one tag, "0,0,2+5" for several). */
    fun encodeListing(category: Int, orderBy: Int, tags: List<Int>): String =
        "$category,$orderBy,${tagSetKey(tags)}"

    /** Reads [encodeListing] (and the older single-tag form); null when malformed. */
    fun decodeListing(raw: String?): Triple<Int, Int, List<Int>>? {
        val parts = raw?.split(',')?.map { it.trim() } ?: return null
        if (parts.size != 3) return null
        val category = parts[0].toIntOrNull() ?: return null
        val orderBy = parts[1].toIntOrNull() ?: return null
        val tags = parts[2].split('+').map { it.trim().toIntOrNull() ?: return null }
        return Triple(category, orderBy, tags)
    }
}
