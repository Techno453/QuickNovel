package com.lagradost.quicknovel.imported

/**
 * Splits an imported .txt book into chapters.
 *
 * 1. **Headings.** A short line that is a chapter heading starts a chapter:
 *    - 第X章 / 第X回 / 第X話 / 第X话 / 第X节 / 第X節 / 第X卷 / 第X集 / 第X篇 / 第X幕 / 第X部 with Arabic,
 *      full-width or Chinese numerals (第一百二十三章, 第１２話, 第12回), optionally followed by a title;
 *    - Korean 제X화 / 제X장 / X화 (제 1 화, 1화, 제12장);
 *    - "Chapter 12", "CHAPTER XII";
 *    - stand-alone 序章, 序幕, 楔子, 引子, 前言, 尾声, 尾聲, 终章, 終章, 后记, 後記, あとがき,
 *      プロローグ, エピローグ, 番外 (+ title), 프롤로그, 에필로그, 외전 (+ title).
 *    A heading must be at most [MAX_HEADING_LENGTH] characters, so a sentence that merely
 *    mentions "第三章" is not one.
 * 2. Text before the first heading becomes a first chapter when it has real content
 *    (more than [MIN_FRONT_MATTER] characters), titled by its first line.
 * 3. Headings with no text after them (a table of contents at the top of the file) are dropped.
 * 4. **Fallback** when fewer than [MIN_HEADINGS] headings are found: parts of about
 *    [TARGET_PART_CHARS] characters, cut at paragraph ends ("Part 1", "Part 2", …).
 *
 * Very long chapters are kept whole: the translation engine already cuts every chapter into
 * request-sized chunks.
 */
object TxtChapterSplitter {
    data class Chapter(val title: String, val paragraphs: List<String>) {
        val length: Int get() = paragraphs.sumOf { it.length }
    }

    const val MAX_HEADING_LENGTH = 40
    const val MIN_HEADINGS = 2
    const val MIN_FRONT_MATTER = 30
    const val TARGET_PART_CHARS = 6000

    private const val NUM = "[0-9０-９零〇○一二两兩三四五六七八九十百千万萬壹贰貳叁參肆伍陆陸柒捌玖拾佰仟]+"

    private val HEADINGS = listOf(
        Regex("""^第\s*$NUM\s*[章回話话节節卷集篇幕部](?:[\s:：、.．·\-—_]*.*)?$"""),
        Regex("""^제?\s*[0-9０-９]+\s*[화장](?:[\s:：.\-—]+.*)?$"""),
        Regex("""^chapter\s+([0-9]+|[ivxlcdm]+)\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:序章|序幕|楔子|引子|前言|尾声|尾聲|终章|終章|后记|後記|あとがき|プロローグ|エピローグ|프롤로그|에필로그)(?:[\s:：、.．·\-—]+.*)?$"""),
        Regex("""^(?:番外|외전)(?:[\s:：、.．·\-—0-9０-９一二三四五六七八九十]+.*)?$"""),
    )

    /** Leading/trailing ASCII, ideographic and no-break spaces (CJK TXT indents with 　　). */
    private fun clean(line: String): String = line.trim { it.isWhitespace() || it == '　' || it == ' ' || it == '﻿' }

    fun isHeading(line: String): Boolean {
        val l = clean(line)
        if (l.isEmpty() || l.length > MAX_HEADING_LENGTH) return false
        return HEADINGS.any { it.matches(l) }
    }

    fun split(text: String): List<Chapter> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map(::clean)

        val headingAt = lines.indices.filter { isHeading(lines[it]) }
        if (headingAt.size < MIN_HEADINGS) return splitBySize(lines.filter { it.isNotEmpty() })

        val chapters = mutableListOf<Chapter>()
        val front = lines.subList(0, headingAt.first()).filter { it.isNotEmpty() }
        if (front.sumOf { it.length } > MIN_FRONT_MATTER) {
            chapters += Chapter(front.first().take(MAX_HEADING_LENGTH), front)
        }
        for ((n, start) in headingAt.withIndex()) {
            val end = headingAt.getOrNull(n + 1) ?: lines.size
            val body = lines.subList(start + 1, end).filter { it.isNotEmpty() }
            if (body.isEmpty()) continue // table of contents entry or a heading right after a heading
            chapters += Chapter(lines[start], body)
        }
        return chapters.ifEmpty { splitBySize(lines.filter { it.isNotEmpty() }) }
    }

    /** Parts of about [target] characters, cut after a paragraph. */
    fun splitBySize(paragraphs: List<String>, target: Int = TARGET_PART_CHARS): List<Chapter> {
        if (paragraphs.isEmpty()) return emptyList()
        val parts = mutableListOf<MutableList<String>>(mutableListOf())
        var size = 0
        for (p in paragraphs) {
            if (size > 0 && size + p.length > target) {
                parts += mutableListOf<String>()
                size = 0
            }
            parts.last() += p
            size += p.length
        }
        if (parts.size == 1) return listOf(Chapter(paragraphs.first().take(MAX_HEADING_LENGTH), parts[0]))
        return parts.mapIndexed { i, ps -> Chapter("Part ${i + 1}", ps) }
    }
}
