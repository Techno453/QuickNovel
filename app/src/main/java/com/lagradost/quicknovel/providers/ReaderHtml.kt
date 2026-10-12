package com.lagradost.quicknovel.providers

import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Builds the chapter HTML the reader and the AI translator expect from the raw CJK providers:
 * one `<p>` per paragraph, inline illustrations as standalone `<p><img src></p>`, paragraphs
 * separated by a blank line so plain-text consumers can split on "\n\n" (same shape as the
 * Kakuyomu / Novelpia / Biquge parsers).
 *
 * Pure Jsoup, no Android calls.
 */
object ReaderHtml {
    private sealed class Line {
        data class Text(val text: String) : Line()
        data class Image(val src: String) : Line()
    }

    private fun isPad(c: Char) = c.isWhitespace() || c == '　' || c == ' ' || c == ' '

    /** Trims ASCII, ideographic (U+3000), em and no-break spaces. */
    fun trimLine(s: String): String = s.trim { isPad(it) }

    /** Escapes and wraps already-split lines, dropping blank ones. */
    fun fromLines(lines: List<String>): String? =
        lines.map { trimLine(it) }.filter { it.isNotEmpty() }
            .joinToString(SEPARATOR) { "<p>${Entities.escape(it)}</p>" }
            .ifEmpty { null }

    /**
     * Walks [root] and splits it into paragraphs on `<br>`, block elements (`p`, `div`, headings,
     * `li`) and, when [newlinesAreBreaks] is set, on literal newlines inside text nodes (for sites
     * that put pre-formatted text inside a single `<p>`). `<img>` becomes its own paragraph with
     * an absolute src (`data-src` preferred for lazy-loaded images).
     */
    fun fromElement(root: Element, newlinesAreBreaks: Boolean = false): String? =
        collect(root, newlinesAreBreaks).joinToString(SEPARATOR) {
            when (it) {
                is Line.Text -> "<p>${Entities.escape(it.text)}</p>"
                is Line.Image -> "<p><img src=\"${Entities.escape(it.src)}\"></p>"
            }
        }.ifEmpty { null }

    /** Plain text lines of [root] (same splitting rules as [fromElement], images dropped). */
    fun lines(root: Element, newlinesAreBreaks: Boolean = false): List<String> =
        collect(root, newlinesAreBreaks).mapNotNull { (it as? Line.Text)?.text }

    private fun collect(root: Element, newlinesAreBreaks: Boolean): List<Line> {
        val out = ArrayList<Line>()
        val sb = StringBuilder()
        fun flush() {
            val text = trimLine(sb.toString())
            if (text.isNotEmpty()) out.add(Line.Text(text))
            sb.setLength(0)
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> {
                    val text = node.wholeText
                    if (newlinesAreBreaks && text.contains('\n')) {
                        text.replace("\r\n", "\n").split('\n').forEachIndexed { i, part ->
                            if (i > 0) flush()
                            sb.append(part)
                        }
                    } else {
                        sb.append(text.replace('\n', ' ').replace('\r', ' '))
                    }
                }

                is Element -> when (val tag = node.normalName()) {
                    "br" -> flush()
                    "img" -> {
                        flush()
                        val src = node.absUrl("data-src").ifBlank { node.absUrl("src") }
                            .ifBlank { node.attr("data-src").ifBlank { node.attr("src") } }
                            .let { if (it.startsWith("//")) "https:$it" else it }
                        if (src.isNotBlank() && !src.startsWith("data:")) out.add(Line.Image(src))
                    }

                    "script", "style", "noscript", "rt", "rp" -> Unit
                    else -> {
                        val block = tag in BLOCK_TAGS || (tag.length == 2 && tag[0] == 'h' && tag[1].isDigit())
                        if (block) flush()
                        node.childNodes().forEach { walk(it) }
                        if (block) flush()
                    }
                }
            }
        }
        root.childNodes().forEach { walk(it) }
        flush()
        return out
    }

    private const val SEPARATOR = "\n\n"
    private val BLOCK_TAGS = setOf("p", "div", "li", "section", "article", "blockquote")
}
