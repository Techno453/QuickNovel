package com.lagradost.quicknovel.translator

/**
 * Detects author's notes in a chapter's plain text: afterwords, "PS" lines, update notices and
 * requests for votes / tickets / donations. Used only by the "Analyze novel" pre-pass, which
 * (a) leaves them out of the excerpt it sends and (b) drops extracted terms that occur only
 * inside them (pen names, real places mentioned in an afterword). Translation input is never
 * stripped.
 *
 * Two kinds of markers, matched near the start of a paragraph (after brackets, dashes or
 * symbols):
 * - block markers ([BLOCK_MARKERS]) open a note that runs to the end of the chapter, e.g.
 *   "作者的话：…", "【あとがき】", "작가의 말";
 * - line markers ([LINE_PATTERNS]) make just that paragraph a note, e.g. "PS：…", "求月票！",
 *   "후원해 주신 분들 감사합니다".
 *
 * Pen names cannot be known in advance, so they are only caught when they appear inside such a
 * block. The lists are deliberately small; the analysis prompt also tells the model to ignore
 * author's notes.
 */
object AuthorNotes {
    /** A marker that starts an author's note running to the end of the chapter. */
    val BLOCK_MARKERS = listOf(
        // Chinese
        "作者的话", "作者有话说", "作者有话要说", "作者感言", "作者留言", "作者按",
        // Japanese
        "あとがき", "後書き", "作者コメント", "作者より",
        // Korean
        "작가의 말", "작가의말", "작가 후기", "작가후기",
    )

    /** A paragraph matching one of these (anywhere for the vote/ticket pleas) is a note by itself. */
    val LINE_PATTERNS = listOf(
        // "PS:", "ps：", "P.S.", "PS." at the start of the paragraph
        Regex("""^(?:P\.?S\.?|ps)\s*[:：.。,，]""", RegexOption.IGNORE_CASE),
        // Chinese pleas for votes, monthly tickets, bookmarks, subscriptions, tips
        Regex("""求月票|求推荐|求收藏|求订阅|求打赏|推荐票|月票"""),
        // Korean donation / sponsorship thanks (not the in-story 후원자 "sponsor")
        Regex("""^후원|후원\s*감사|후원해\s*주신|후원해주신"""),
    )

    /** Leading characters skipped before looking for a marker: brackets, dashes, stars, spaces. */
    private val LEADING_NOISE = Regex("""^[\s\p{Ps}\p{Pd}\p{So}*#=~～_·•「『【〔（(\[<《〈]+""")

    /** How far into a paragraph (after [LEADING_NOISE]) a block marker may start. */
    private const val MARKER_WINDOW = 4

    /** A chapter's text split into story paragraphs and author's-note paragraphs. */
    data class Split(
        /** The text without author's notes (paragraphs joined by blank lines). */
        val story: String,
        /** The author's notes (paragraphs joined by blank lines); "" when there are none. */
        val notes: String,
    ) {
        val hasNotes: Boolean get() = notes.isNotEmpty()
    }

    private fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** True if [paragraph] opens an author's note that runs to the end of the chapter. */
    fun startsBlock(paragraph: String): Boolean {
        val head = paragraph.trim().replace(LEADING_NOISE, "")
        return BLOCK_MARKERS.any { marker ->
            val at = head.indexOf(marker)
            at in 0..MARKER_WINDOW
        }
    }

    /** True if [paragraph] is an author's note on its own (PS line, vote plea, donation thanks). */
    fun isNoteLine(paragraph: String): Boolean {
        val head = paragraph.trim().replace(LEADING_NOISE, "")
        return LINE_PATTERNS.any { it.containsMatchIn(head) }
    }

    /** Splits one chapter's plain text. Apply per chapter: a block runs to the end of [chapterText]. */
    fun split(chapterText: String): Split {
        val story = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var inBlock = false
        for (p in paragraphs(chapterText)) {
            if (!inBlock && startsBlock(p)) inBlock = true
            if (inBlock || isNoteLine(p)) notes += p else story += p
        }
        return Split(story.joinToString("\n\n"), notes.joinToString("\n\n"))
    }

    /** [chapterText] without author's notes. */
    fun strip(chapterText: String): String = split(chapterText).story
}
