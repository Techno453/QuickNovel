package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.GlossaryTerm

/** Result of the post-translation checks for one chunk (no API call involved). */
data class ChunkQuality(
    /** Bible terms present in the source whose English rendering is missing from the output. */
    val violatedTerms: List<GlossaryTerm> = emptyList(),
    /** Runs of Han / Hiragana / Katakana / Hangul left in the English output. */
    val leakedFragments: List<String> = emptyList(),
    val inputParagraphs: Int = 0,
    val outputParagraphs: Int = 0,
) {
    /** True when the output has fewer than 70% of the input paragraphs (likely omissions). */
    val paragraphShortfall: Boolean
        get() = QualityChecks.isParagraphShortfall(inputParagraphs, outputParagraphs)

    /** Total issue count, used to pick the better of two attempts. */
    val issueCount: Int
        get() = violatedTerms.size + leakedFragments.size + (if (paragraphShortfall) 1 else 0)

    val hasIssues: Boolean get() = issueCount > 0
}

object QualityChecks {
    const val MIN_PARAGRAPH_RATIO = 0.7

    /** Sources shorter than this are not checked (single characters like 气 are too ambiguous). */
    const val MIN_CHECKED_SOURCE_LENGTH = 2

    private val CJK_RUN = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]+""")
    private const val MASK = '\u0000'

    /**
     * Bible terms whose source (or an alias) occurs in [chunk]. Longer forms are matched first and
     * masked, so 气 does not "occur" inside 灵气 when 灵气 is itself a term.
     */
    fun termsInSource(chunk: String, terms: List<GlossaryTerm>): List<GlossaryTerm> {
        if (chunk.isEmpty() || terms.isEmpty()) return emptyList()
        val forms = terms.flatMap { term -> TermAliases.formsOf(term).map { it to term } }
            .sortedByDescending { it.first.length }
        val working = StringBuilder(chunk)
        val found = LinkedHashSet<GlossaryTerm>()
        for ((form, term) in forms) {
            var idx = working.indexOf(form)
            if (idx < 0) continue
            found += term
            while (idx >= 0) {
                for (i in idx until idx + form.length) working.setCharAt(i, MASK)
                idx = working.indexOf(form, idx + form.length)
            }
        }
        // Keep the bible's order (stable output for prompts and tests).
        return terms.filter { it in found }
    }

    /**
     * True if [english] appears in [translation]: case-insensitive, whole words, any whitespace or
     * hyphen between words, optional possessive ('s / ’s / s') and plural (s / es / y→ies).
     */
    fun containsRendering(translation: String, english: String): Boolean {
        val target = english.trim()
        if (target.isEmpty()) return true
        return renderingRegex(target).containsMatchIn(translation)
    }

    private fun renderingRegex(english: String): Regex {
        val words = english.split(Regex("""[\s\-]+""")).filter { it.isNotEmpty() }
        val body = words.joinToString("""[\s\-]+""") { Regex.escape(it) }
        val last = words.last()
        val iesAlt = if (last.length > 1 && last.endsWith("y", ignoreCase = true) && last[last.length - 2].lowercaseChar() !in "aeiou") {
            val stem = (words.dropLast(1) + last.dropLast(1)).joinToString("""[\s\-]+""") { Regex.escape(it) }
            "|$stem" + "ies"
        } else ""
        val pattern = """(?<![\p{L}\p{N}])(?:$body(?:['’]s|['’]|es|s)?$iesAlt)(?![\p{L}\p{N}])"""
        return Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /** Terms present in [sourceChunk] whose rendering is missing from [translation]. */
    fun findViolations(sourceChunk: String, translation: String, terms: List<GlossaryTerm>): List<GlossaryTerm> =
        termsInSource(sourceChunk, terms)
            .filter { term -> TermAliases.formsOf(term).any { it.length >= MIN_CHECKED_SOURCE_LENGTH } }
            .filter { it.english.isNotBlank() && !containsRendering(translation, it.english) }

    /**
     * Untranslated CJK runs in [translation]. Pass only the translation part of the model output
     * (the ===TERMS=== / ===SUMMARY=== tail legitimately contains source text).
     */
    fun findCjkLeaks(translation: String): List<String> =
        CJK_RUN.findAll(translation).map { it.value }.toList()

    /** Non-blank lines; the reader treats every line as a paragraph. */
    fun countParagraphs(text: String): Int = text.lineSequence().count { it.isNotBlank() }

    fun isParagraphShortfall(input: Int, output: Int): Boolean =
        input > 0 && output < input * MIN_PARAGRAPH_RATIO

    /** Below this output/input paragraph ratio, refusal phrases mark the output as a refusal. */
    const val REFUSAL_MAX_FIDELITY = 0.5

    /** How far into the output refusal phrases are looked for. */
    const val REFUSAL_SCAN_CHARS = 300

    private val REFUSAL_PHRASES = listOf(
        "i can't", "i can’t", "i cannot", "unable to", "copyright", "i won't", "i won’t",
    )

    /**
     * True if [translation] looks like a refusal or an abridgement rather than a translation of
     * [sourceChunk]: paragraph fidelity (output / input paragraphs) below 0.5 AND a refusal phrase
     * ("I can't", "I cannot", "unable to", "copyright", "I won't") within the first 300 characters.
     */
    fun looksLikeRefusal(sourceChunk: String, translation: String): Boolean {
        val input = countParagraphs(sourceChunk)
        if (input == 0) return false
        if (countParagraphs(translation).toDouble() / input >= REFUSAL_MAX_FIDELITY) return false
        val head = translation.take(REFUSAL_SCAN_CHARS).lowercase()
        return REFUSAL_PHRASES.any { it in head }
    }

    fun check(sourceChunk: String, translation: String, terms: List<GlossaryTerm>): ChunkQuality =
        ChunkQuality(
            violatedTerms = findViolations(sourceChunk, translation, terms),
            leakedFragments = findCjkLeaks(translation),
            inputParagraphs = countParagraphs(sourceChunk),
            outputParagraphs = countParagraphs(translation),
        )
}
