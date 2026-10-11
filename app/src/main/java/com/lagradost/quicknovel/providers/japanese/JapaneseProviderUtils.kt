package com.lagradost.quicknovel.providers.japanese

import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

/**
 * Helpers for Japanese web-novel providers (Kakuyomu).
 *
 * Kept free of Android dependencies so they can be covered by JVM unit tests.
 */
object JapaneseProviderUtils {
    private fun isKanaReading(reading: String): Boolean =
        reading.all { it in '぀'..'ゟ' || it == 'ー' || it == '・' || it.isWhitespace() }

    /**
     * Flattens `<ruby>` markup in place.
     *
     * Plain furigana (a hiragana reading over kanji) is dropped since it only aids pronunciation
     * and confuses machine translation. Any other reading (katakana gloss, alternate word,
     * English) carries meaning the author intended, so it is kept as `base《reading》`, which is
     * also how both sites render ruby in their own plain-text exports.
     */
    fun flattenRuby(root: Element) {
        for (ruby in root.select("ruby")) {
            val reading = ruby.select("rt").text().trim()
            ruby.select("rt, rp").remove()
            val base = ruby.text().trim()
            val replacement = if (reading.isEmpty() || isKanaReading(reading)) {
                base
            } else {
                "$base《$reading》"
            }
            ruby.replaceWith(TextNode(replacement))
        }
    }

    /**
     * Converts a container of `<p>` lines into clean reader HTML: one `<p>` per non-blank line,
     * attributes stripped, ruby flattened, inline illustrations kept as absolute `<img>` tags.
     * Paragraphs are separated by a blank line so plain-text consumers can split on "\n\n".
     */
    fun paragraphsToHtml(container: Element): String {
        flattenRuby(container)
        val lines = container.select("p").ifEmpty { listOf(container) }
        return lines.mapNotNull { p ->
            val images = p.select("img[src]").map { it.absUrl("src").ifBlank { fixProtocol(it.attr("src")) } }
            val text = p.text().trim { it.isWhitespace() || it == '　' }
            when {
                images.isNotEmpty() -> images.joinToString("") { "<p><img src=\"${Entities.escape(it)}\"></p>" } +
                        if (text.isNotEmpty()) "<p>${Entities.escape(text)}</p>" else ""

                text.isNotEmpty() -> "<p>${Entities.escape(text)}</p>"
                else -> null
            }
        }.joinToString("\n\n")
    }

    fun fixProtocol(url: String): String = if (url.startsWith("//")) "https:$url" else url
}
