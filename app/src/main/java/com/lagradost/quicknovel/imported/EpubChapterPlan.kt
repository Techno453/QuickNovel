package com.lagradost.quicknovel.imported

/**
 * Which spine items make up each chapter of an EPUB. Shared by the reader ([com.lagradost.quicknovel.RegularBook]),
 * the result page's chapter list and the translation pipeline, so a chapter index means the same
 * text everywhere (the index is the translation key).
 *
 * - The table of contents (flattened, depth-first) gives the chapters when it has more than one
 *   entry that points into the spine. Chapter i runs from its entry's spine item up to (not
 *   including) the next entry's item; non-linear items after the first are skipped.
 * - Otherwise every linear spine item is a chapter, titled by its resource title or "Chapter n".
 *
 * Very long spine items stay one chapter (the translation engine chunks long chapters itself).
 * Two TOC entries pointing into the same spine item (anchors inside one file) give the first an
 * empty chapter, as the reader always did; empty chapters are never translated.
 */
object EpubChapterPlan {
    /** A TOC entry: its title and the spine index of its resource (-1 = not in the spine). */
    data class TocEntry(val title: String?, val spineIndex: Int, val children: List<TocEntry> = emptyList())

    /** A spine item: its resource title and whether it is linear. */
    data class SpineItem(val title: String?, val linear: Boolean)

    data class Chapter(val title: String, val spineIndices: List<Int>)

    fun plan(toc: List<TocEntry>, spine: List<SpineItem>): List<Chapter> {
        val flat = mutableListOf<TocEntry>()
        fun flatten(entries: List<TocEntry>) {
            for (e in entries) {
                if (e.spineIndex !in spine.indices) continue // broken entry (and its children)
                flat += e
                flatten(e.children)
            }
        }
        flatten(toc)

        if (flat.size <= 1) {
            return spine.withIndex().filter { it.value.linear }.mapIndexed { n, (i, item) ->
                Chapter(item.title ?: "Chapter ${n + 1}", listOf(i))
            }
        }

        return flat.mapIndexed { n, entry ->
            val start = entry.spineIndex
            val next = flat.getOrNull(n + 1)?.spineIndex ?: -1
            val end = if (next == -1) spine.size else next
            val indices = (start until end).filter { i -> i == start || spine[i].linear }
            Chapter(entry.title ?: "Chapter ${n + 1}", indices)
        }
    }
}
