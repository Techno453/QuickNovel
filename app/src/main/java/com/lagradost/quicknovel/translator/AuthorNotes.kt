package com.lagradost.quicknovel.translator

/**
 * Detects author's notes in a chapter's plain text: afterwords, "PS" lines, update notices,
 * requests for votes / tickets / donations, thanks for readers' rewards and plugs for other
 * books. Used by the "Analyze novel" pre-pass, which (a) leaves them out of the excerpt it sends
 * and (b) drops extracted terms that occur only inside them (pen names, real places mentioned in
 * an afterword), and by translation, which drops the terms the model reports from them (reader
 * usernames, recommended books, platforms). Translation input is never stripped.
 *
 * Two kinds of markers, matched near the start of a paragraph (after brackets, dashes or
 * symbols):
 * - block markers ([BLOCK_MARKERS]) open a note that runs to the end of the chapter, e.g.
 *   "作者的话：…", "【あとがき】", "작가의 말";
 * - line markers ([LINE_PATTERNS]) make just that paragraph a note, e.g. "PS：…", "求月票！",
 *   "感謝XX的盟主打賞", "후원해 주신 분들 감사합니다";
 * - a bracketed aside to readers ([BRACKET_NOTE_KEYWORDS]) is a note up to its closing bracket,
 *   e.g. "（土豆新书开张……请各位书友点击收藏……）" (the real ending of 斗破苍穹 chapter 1).
 *
 * Chinese markers are listed in Simplified and Traditional forms (Taiwanese sites serve the same
 * books in Traditional characters).
 *
 * Pen names cannot be known in advance, so they are only caught when they appear inside such a
 * block. The lists are deliberately small; the prompts also tell the model to ignore author's
 * notes.
 */
object AuthorNotes {
    /** A marker that starts an author's note running to the end of the chapter. */
    val BLOCK_MARKERS = listOf(
        // Chinese
        "作者的话", "作者有话说", "作者有话要说", "作者感言", "作者留言", "作者按",
        "作者的話", "作者有話說", "作者有話要說",
        // Japanese
        "あとがき", "後書き", "作者コメント", "作者より",
        // Korean
        "작가의 말", "작가의말", "작가 후기", "작가후기",
    )

    /** A paragraph matching one of these (anywhere for the vote/ticket pleas) is a note by itself. */
    val LINE_PATTERNS = listOf(
        // "PS:", "ps：", "P.S.", "PS." at the start of the paragraph
        Regex("""^(?:P\.?S\.?|ps)\s*[:：.。,，]""", RegexOption.IGNORE_CASE),
        // Chinese pleas for votes, monthly tickets, bookmarks, subscriptions, tips; addressing readers
        Regex("""求月票|求推荐|求推薦|求收藏|求订阅|求訂閱|求打赏|求打賞|推荐票|推薦票|月票|票票|书友|書友"""),
        // Thanks for readers' rewards ("感謝XX的盟主", "感谢XX打赏") and bonus chapters for them
        // (加更). A reward tier counts only as "…的盟主" after thanks: 盟主 alone is also the wuxia
        // "alliance leader" ("他感谢武林盟主").
        Regex("""(?:感谢|感謝|多谢|多謝|谢谢|謝謝)[^。！？!?]{0,30}(?:打赏|打賞|万赏|萬賞|的(?:盟主|舵主|堂主|白银|白銀))|加更"""),
        // Plugs for other books: "推荐一本书《…》", "新书《…》"
        Regex("""(?:推荐|推薦|推书|推書|新书|新書)[^。！？!?]{0,20}《"""),
        // Korean donation / sponsorship thanks (not the in-story 후원자 "sponsor")
        Regex("""^후원|후원\s*감사|후원해\s*주신|후원해주신"""),
    )

    /**
     * A paragraph that opens with a bracket and mentions one of these is an author's aside that
     * runs to the matching closing bracket, possibly over several paragraphs, e.g.
     * "（土豆新书开张，……请各位新老书友点击收藏……" … "……不会留有任何烂尾之嫌！）".
     */
    val BRACKET_NOTE_KEYWORDS =
        Regex("""书友|書友|新书|新書|收藏|票票|推荐票|推薦票|月票|求票|码字|碼字|打赏|打賞|订阅|訂閱|加更""")

    // Round brackets only: 【…】 and [ … ] are in-story system messages in many novels.
    private const val OPEN_BRACKETS = "（("
    private const val CLOSE_BRACKETS = "）)"

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

    /** True if [paragraph] opens a bracketed aside addressed to readers ([BRACKET_NOTE_KEYWORDS]). */
    fun startsBracketNote(paragraph: String): Boolean {
        val p = paragraph.trim()
        return p.isNotEmpty() && p.first() in OPEN_BRACKETS && BRACKET_NOTE_KEYWORDS.containsMatchIn(p)
    }

    /**
     * Splits one chapter's plain text. Apply per chapter: a block runs to the end of [chapterText].
     * A note line ending in a colon ("感謝以下書友的打賞：") also takes the short paragraphs right
     * after it (at most [LIST_ITEM_MAX] characters each: a list of usernames).
     */
    fun split(chapterText: String): Split {
        val story = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var inBlock = false
        var inBracket = false
        var inList = false
        for (p in paragraphs(chapterText)) {
            if (!inBlock && startsBlock(p)) inBlock = true
            if (!inBlock && !inBracket && startsBracketNote(p)) inBracket = true
            if (inList && p.length > LIST_ITEM_MAX) inList = false
            val noteLine = isNoteLine(p)
            if (inBlock || inBracket || inList || noteLine) notes += p else story += p
            if (noteLine && p.last() in LIST_COLONS) inList = true
            if (inBracket && p.last() in CLOSE_BRACKETS) inBracket = false
        }
        return Split(story.joinToString("\n\n"), notes.joinToString("\n\n"))
    }

    private const val LIST_COLONS = ":："

    /** Longest paragraph still taken as an item of a list after "感謝以下…：" (a username). */
    const val LIST_ITEM_MAX = 20

    /** [chapterText] without author's notes. */
    fun strip(chapterText: String): String = split(chapterText).story
}
