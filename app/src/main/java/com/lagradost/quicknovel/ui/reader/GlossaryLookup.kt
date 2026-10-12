package com.lagradost.quicknovel.ui.reader

import com.lagradost.quicknovel.translator.TermAliases
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm

/**
 * Finds the Story Bible term under a long-press or a text selection in the reader. Pure (no
 * Android), unit tested in GlossaryLookupTest.
 *
 * A term is found by its English rendering (case-insensitive, whole words, so "Lin" never matches
 * inside "Linda") or by its source string / source aliases (exact, for untranslated CJK text).
 * The longest occurrence covering the pressed character wins, so pressing "Feng" in "Lin Feng"
 * selects the whole name. Possessives ("Lilia's", "Lilia’s", "Lilias'") are included in the
 * pressed area but not in the term. When no full rendering covers the press, a single word of a
 * multi-word character name ("Lilia" for "Lilia Rose") is accepted as a weaker match.
 */
object GlossaryLookup {
    /** A term found in the text: [start] until [end] is the matched rendering (no possessive). */
    data class Match(
        val term: GlossaryTerm,
        val start: Int,
        val end: Int,
        val form: String,
        /** False for the weaker "one word of a multi-word name" match. */
        val exact: Boolean = true,
    )

    /** How far around the press we look for occurrences (longest names are far shorter). */
    private const val WINDOW = 200

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    /** Ideographs, kana and hangul: no word boundaries in those scripts. */
    internal fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    /** Scripts whose characters run together into one word: katakana (with ー・) and hangul. */
    private fun scriptRun(c: Char): Int? {
        if (c == 'ー' || c == '・') return 1
        return when (Character.UnicodeBlock.of(c)) {
            Character.UnicodeBlock.KATAKANA, Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS -> 1
            Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> 2
            else -> null
        }
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’'

    /** Length of a possessive suffix ("'s", "’s", or a bare "'" after s) at [at], else 0. */
    internal fun possessiveLength(text: CharSequence, at: Int): Int {
        if (at >= text.length || !isApostrophe(text[at])) return 0
        if (at + 1 < text.length && (text[at + 1] == 's' || text[at + 1] == 'S') &&
            (at + 2 >= text.length || !isWordChar(text[at + 2]))
        ) return 2
        if (at > 0 && (text[at - 1] == 's' || text[at - 1] == 'S') &&
            (at + 1 >= text.length || !isWordChar(text[at + 1]))
        ) return 1
        return 0
    }

    /**
     * The word around [offset]: letters/digits, with apostrophes inside a word ("didn't") kept;
     * a trailing possessive is not part of the word. One CJK character for CJK text.
     */
    fun wordAt(text: CharSequence, offset: Int): IntRange? {
        if (text.isEmpty()) return null
        var i = offset.coerceIn(0, text.length - 1)
        if (!isWordChar(text[i])) {
            // pressed just after a word (on its apostrophe or end): step back onto it
            if (i > 0 && isWordChar(text[i - 1])) i-- else return null
        }
        if (isCjk(text[i])) {
            // katakana names and hangul words form runs; a kanji/hanzi is taken on its own
            val run = scriptRun(text[i]) ?: return i..i
            var s = i
            while (s > 0 && scriptRun(text[s - 1]) == run) s--
            var e = i + 1
            while (e < text.length && scriptRun(text[e]) == run) e++
            return s until e
        }
        var start = i
        while (start > 0 && (isWordChar(text[start - 1]) && !isCjk(text[start - 1]) ||
                    isApostrophe(text[start - 1]) && start - 2 >= 0 && isWordChar(text[start - 2]))
        ) start--
        var end = i + 1
        while (end < text.length) {
            val c = text[end]
            if (isWordChar(c) && !isCjk(c)) {
                end++
            } else if (isApostrophe(c) && end + 1 < text.length && isWordChar(text[end + 1]) &&
                possessiveLength(text, end) == 0
            ) {
                end++
            } else break
        }
        // pressed on the "s" of a possessive: the word ends before the apostrophe
        if (end - 2 > start && possessiveLength(text, end - 2) == 2) end -= 2
        return start until end
    }

    /** Every form a term can appear as: English rendering, source, source aliases. */
    private fun formsOf(term: GlossaryTerm): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        term.english.trim().takeIf { it.isNotEmpty() }?.let { out += it to true }
        for (f in TermAliases.formsOf(term)) out += f to false
        return out
    }

    private fun boundaryOk(text: CharSequence, start: Int, end: Int, form: String): Boolean {
        // CJK forms (and forms that start/end with CJK) need no word boundary
        val first = form.first()
        val last = form.last()
        if (!isCjk(first) && isWordChar(first) && start > 0 && isWordChar(text[start - 1]) && !isCjk(text[start - 1])) return false
        if (!isCjk(last) && isWordChar(last) && end < text.length && isWordChar(text[end]) && !isCjk(text[end])) return false
        return true
    }

    /** All occurrences of all terms intersecting [from, to) (with a margin for long names). */
    private fun occurrences(text: CharSequence, from: Int, to: Int, terms: List<GlossaryTerm>): List<Match> {
        val winStart = (from - WINDOW).coerceAtLeast(0)
        val winEnd = (to + WINDOW).coerceAtMost(text.length)
        val window = text.subSequence(winStart, winEnd).toString()
        val out = ArrayList<Match>()
        for (term in terms) {
            for ((form, english) in formsOf(term)) {
                var idx = window.indexOf(form, 0, ignoreCase = english)
                while (idx >= 0) {
                    val s = winStart + idx
                    val e = s + form.length
                    if (e > from && s < to && boundaryOk(text, s, e, form)) {
                        out += Match(term, s, e, form)
                    }
                    idx = window.indexOf(form, idx + 1, ignoreCase = english)
                }
            }
        }
        return out
    }

    /** End of a match including a possessive suffix right after it. */
    private fun extendedEnd(text: CharSequence, m: Match) = m.end + possessiveLength(text, m.end)

    /** Term under a long-press at character [offset] of [text], or null. */
    fun findAt(text: CharSequence, offset: Int, terms: List<GlossaryTerm>): Match? {
        if (text.isEmpty() || terms.isEmpty()) return null
        val at = offset.coerceIn(0, text.length - 1)
        // from at - 2: a press on the possessive suffix ("'s") still counts
        occurrences(text, (at - 2).coerceAtLeast(0), at + 1, terms)
            .filter { at >= it.start && at < extendedEnd(text, it) }
            .maxWithOrNull(compareBy<Match>({ it.end - it.start }, { -it.start }))
            ?.let { return it }
        val word = wordAt(text, at) ?: return null
        return partialNameMatch(text, word.first, word.last + 1, terms)
    }

    /**
     * Term for a selection [start, end). Prefers a rendering that covers the whole selection
     * (selecting "Feng" in "Lin Feng" gives "Lin Feng"), then the longest rendering inside it.
     */
    fun findForSelection(text: CharSequence, start: Int, end: Int, terms: List<GlossaryTerm>): Match? {
        if (text.isEmpty() || terms.isEmpty()) return null
        var s = start.coerceIn(0, text.length)
        var e = end.coerceIn(s, text.length)
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return null
        // "Lilia's" selected: ignore the possessive
        for (cut in listOf(2, 1)) {
            if (e - cut > s && possessiveLength(text, e - cut) == cut) {
                e -= cut
                break
            }
        }
        val found = occurrences(text, s, e, terms)
        found.filter { it.start <= s && it.end >= e }
            .minByOrNull { it.end - it.start }
            ?.let { return it }
        found.filter { it.start >= s && it.end <= e }
            .maxWithOrNull(compareBy<Match>({ it.end - it.start }, { -it.start }))
            ?.let { return it }
        found.maxByOrNull { minOf(it.end, e) - maxOf(it.start, s) }?.let { return it }
        return partialNameMatch(text, s, e, terms)
    }

    /** One word of a multi-word character name, e.g. "Lilia" for "Lilia Rose" (not "of", "the"). */
    private fun partialNameMatch(text: CharSequence, start: Int, end: Int, terms: List<GlossaryTerm>): Match? {
        val word = text.subSequence(start, end).toString()
        if (word.length < 3 || !word.first().isUpperCase()) return null
        return terms.asSequence()
            .filter { it.isCharacter }
            .filter { t -> t.english.trim().split(' ', '-').size > 1 }
            .firstOrNull { t -> t.english.trim().split(' ', '-').any { it.equals(word, ignoreCase = true) } }
            ?.let { Match(it, start, end, word, exact = false) }
    }

    /**
     * The text to prefill "Add to glossary" with when nothing matched: the selection or the
     * pressed word, without a possessive and surrounding punctuation.
     */
    fun englishForNewTerm(text: CharSequence, start: Int, end: Int): String {
        var s = start.coerceIn(0, text.length)
        var e = end.coerceIn(s, text.length)
        while (s < e && !isWordChar(text[s])) s++
        while (e > s && !isWordChar(text[e - 1])) e--
        if (e - 2 > s && possessiveLength(text, e - 2) == 2) e -= 2
        return text.subSequence(s, e).toString().trim()
    }

    /**
     * Pending suggestions concerning [term]: a conflicting rendering of the same source, a
     * suggestion for one of its aliases, a different source flagged as "same English as existing
     * <source>", or the same English rendering.
     */
    fun pendingFor(term: GlossaryTerm, pending: List<PendingTerm>): List<PendingTerm> {
        val aliases = TermAliases.formsOf(term).toSet()
        return pending.filter { p ->
            p.novelId == term.novelId && (
                    p.source == term.source || p.source in aliases ||
                            p.notes.startsWith("same English as existing ${term.source} ") ||
                            p.notes == "same English as existing ${term.source}" ||
                            (term.english.isNotBlank() && p.suggestedEnglish.trim().equals(term.english.trim(), ignoreCase = true))
                    )
        }
    }
}
