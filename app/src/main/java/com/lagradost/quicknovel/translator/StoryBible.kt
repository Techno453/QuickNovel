package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm
import com.lagradost.quicknovel.translator.db.TermCandidate

/**
 * Continuity context for one chapter. Everything is optional; the engine only renders what is
 * non-blank, and nothing at all when [TranslationOptions.carryStoryContext] is off.
 */
data class StoryContext(
    /** Story summary from the "Analyze novel" pre-pass. */
    val premise: String = "",
    /** Summary of the closest earlier translated chapter. */
    val previousChapterSummary: String = "",
    /** Last ~500 characters of the previous chapter's English (seed for the first chunk). */
    val previousEnglishTail: String = "",
    /** Source names of characters present in the previous chapter (always included in the bible block). */
    val recentCharacters: List<String> = emptyList(),
    /** Name / honorific style; null = defaults for the source language. */
    val style: TranslationStyle? = null,
)

/** Behaviour switches for [TranslationEngine.translateChapter]. */
data class TranslationOptions(
    /** Re-request a chunk once when the adherence / leak / paragraph checks fail. */
    val strictMode: Boolean = true,
    /**
     * Terms that recur in a later chapter go into the bible (and confident new terms are used for
     * the rest of their chapter); off = recurring terms go to pending review instead.
     */
    val autoAddTerms: Boolean = true,
    /** Send premise, previous chapter summary and previous English tail with every chunk. */
    val carryStoryContext: Boolean = true,
    /** Minimum model confidence for auto-adding a discovered term. */
    val autoAddMinConfidence: Float = DEFAULT_AUTO_ADD_CONFIDENCE,
    /**
     * [PromptProfile] id for the primary model, overriding the engine's own (from its
     * [EngineConfig.promptProfile]); null = the engine's. Never applied to the fallback model,
     * which always uses its own profile.
     */
    val promptProfile: String? = null,
    /**
     * Fold the terms the model reports into the bible (candidates, auto-added, updated or pending).
     * Off = the reported terms are discarded: no candidates, auto-added, updated or pending terms.
     */
    val collectTerms: Boolean = true,
) {
    companion object {
        const val DEFAULT_AUTO_ADD_CONFIDENCE = 0.6f

        /**
         * Preview (result page "Preview", docs/translation-pipeline.md "Preview"): a plain chapter
         * translation without the glossary. Keeps [base]'s strict mode and prompt profile; no
         * auto-added or pending terms and no story context carried between chunks or chapters.
         */
        fun preview(base: TranslationOptions = TranslationOptions()): TranslationOptions = base.copy(
            autoAddTerms = false,
            carryStoryContext = false,
            collectTerms = false,
        )
    }
}

/** A term reported by the model in the ===TERMS=== tail (or by the analysis pre-pass). */
data class DiscoveredTerm(
    val source: String,
    val english: String,
    val category: String = GlossaryTerm.CATEGORY_OTHER,
    val gender: String = GlossaryTerm.GENDER_UNKNOWN,
    val notes: String = "",
    val confidence: Float = 0.5f,
    val aliases: List<String> = emptyList(),
    /**
     * Analysis pre-pass only: the model's own verdict on whether this is a proper noun or
     * setting-specific term (true) or an ordinary word / unnamed role (false). null = not given
     * (older prompts, the translation tail); [AnalysisFilter] drops entries marked false.
     */
    val proper: Boolean? = null,
)

/** JSON helpers for the `aliases` column (a JSON array of source-language strings). */
object TermAliases {
    private val mapper = jacksonObjectMapper()

    fun parse(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            mapper.readValue(json, List::class.java).filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encode(aliases: List<String>): String =
        mapper.writeValueAsString(aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct())

    /** "a, b，c、d" → JSON array (UI input). */
    fun fromCommaSeparated(text: String): String = encode(text.split(',', '，', '、', ';', '；'))

    fun toCommaSeparated(json: String?): String = parse(json).joinToString(", ")

    /** Source plus aliases, longest first. */
    fun formsOf(term: GlossaryTerm): List<String> =
        (listOf(term.source) + parse(term.aliases)).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .sortedByDescending { it.length }
}

/**
 * Whole-word, case-sensitive replacement of a glossary rendering in stored translations. A
 * possessive or plural suffix the text attached to the old rendering stays on the new one:
 * "Lin Feng's" → "Lin Fen's", "spirit stones" → "spirit crystals".
 */
object NameReplacer {
    private fun pattern(name: String) =
        Regex("""(?<![\p{L}\p{N}])${Regex.escape(name)}(['’]s|['’]|s)?(?![\p{L}\p{N}])""")

    fun countOccurrences(text: String, name: String): Int =
        if (name.isBlank()) 0 else pattern(name).findAll(text).count()

    /** Replaces whole-word occurrences of [old] with [new], keeping a possessive or plural suffix. */
    fun replace(text: String, old: String, new: String): String =
        if (old.isBlank() || old == new) text else pattern(old).replace(text) { m -> new + m.groupValues[1] }
}

/** What to do with the terms a chunk reported. */
data class TermDecisions(
    /** Terms reported for the first time: candidates ([TermCandidates]), not glossary entries yet. */
    val candidates: List<TermCandidate> = emptyList(),
    /** Existing unlocked auto-added terms that gained information (gender / notes). */
    val updates: List<GlossaryTerm> = emptyList(),
    /** Terms for manual review (conflicts, a possible alias of an existing entry). */
    val pending: List<PendingTerm> = emptyList(),
)

/**
 * Decides what the terms a passage reported (already cleaned by [AnalysisFilter]) do to the
 * Story Bible.
 *
 * - Known source term (or alias) with a different English rendering → pending with notes
 *   "conflicts with existing X". The existing term is never overwritten.
 * - Known source term with the same rendering → ignored, except that an unlocked auto-added
 *   term with unknown gender takes a confident gender (and notes if it had none).
 * - Already a candidate → ignored (its first rendering stands).
 * - Dropped: a source shorter than [QualityChecks.MIN_CHECKED_SOURCE_LENGTH] or not in the
 *   passage (it could never be matched), and a known term plus ordinary words
 *   ([AnalysisFilter.compoundOf]: "redwood marsh" when Redwood is known).
 * - Same English as an existing glossary entry → pending (probably an alias); as a candidate or
 *   another new term of this chapter → dropped.
 * - Otherwise → a new [TermCandidate]: used for the rest of the chapter when confident, and
 *   added to the bible only when it turns up in another chapter ([TermCandidates]).
 */
object BibleMerger {
    private val CJK = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]""")

    fun containsCjk(text: String): Boolean = CJK.containsMatchIn(text)

    /**
     * @param bible the entries in use for this chapter: the glossary, plus (negative ids)
     *   promoted and in-chapter candidates
     * @param candidates every candidate known so far (stored and reported earlier in this chapter)
     */
    fun decide(
        discovered: List<DiscoveredTerm>,
        bible: List<GlossaryTerm>,
        novelId: String,
        chapterNum: Int,
        sourceChunk: String,
        options: TranslationOptions = TranslationOptions(),
        candidates: List<TermCandidate> = emptyList(),
    ): TermDecisions {
        val bySource = HashMap<String, GlossaryTerm>()
        for (term in bible) for (form in TermAliases.formsOf(term)) bySource.putIfAbsent(form, term)
        val byEnglish = HashMap<String, GlossaryTerm>()
        for (term in bible) byEnglish.putIfAbsent(AnalysisFilter.englishKey(term.english), term)
        val candidateSources = candidates.mapTo(HashSet()) { it.source }
        val candidateEnglish = candidates.mapTo(HashSet()) { AnalysisFilter.englishKey(it.english) }
        val known = (bible + candidates.map { TermCandidates.toTerm(it) }).toMutableList()

        val newCandidates = mutableListOf<TermCandidate>()
        val updates = LinkedHashMap<Long, GlossaryTerm>()
        val pending = mutableListOf<PendingTerm>()
        val context = sourceChunk.take(200)

        val reported = discovered
            .map { it.copy(source = it.source.trim(), english = it.english.trim()) }
            .filter { isPlausible(it) }
            .sortedByDescending { it.confidence }
            .distinctBy { it.source }

        for (d in reported) {
            fun toPending(notes: String) = PendingTerm(
                novelId = novelId,
                source = d.source,
                suggestedEnglish = d.english,
                category = GlossaryTerm.normalizeCategory(d.category),
                context = context,
                chapterNum = chapterNum,
                gender = GlossaryTerm.normalizeGender(d.gender),
                notes = notes,
                confidence = d.confidence,
            )

            val existing = bySource[d.source]
            if (existing != null) {
                if (existing.english.trim().equals(d.english, ignoreCase = true)) {
                    val gender = GlossaryTerm.normalizeGender(d.gender)
                    if (!existing.locked && existing.autoAdded &&
                        existing.gender == GlossaryTerm.GENDER_UNKNOWN && gender != GlossaryTerm.GENDER_UNKNOWN &&
                        d.confidence >= options.autoAddMinConfidence
                    ) {
                        val base = updates[existing.id] ?: existing
                        updates[existing.id] = base.copy(gender = gender, notes = base.notes.ifBlank { d.notes.trim() })
                    }
                } else if (existing.id >= 0) { // stored entries only: a term of this chapter keeps its first rendering
                    val note = "conflicts with existing ${existing.english}" +
                        (if (d.notes.isNotBlank()) "; ${d.notes.trim()}" else "")
                    pending += toPending(note)
                }
                continue
            }
            if (d.source in candidateSources) continue
            if (d.source.length < QualityChecks.MIN_CHECKED_SOURCE_LENGTH || !sourceChunk.contains(d.source)) continue
            if (AnalysisFilter.compoundOf(d.source, d.english, known) != null) continue

            val key = AnalysisFilter.englishKey(d.english)
            val sameEnglish = byEnglish[key]
            if (sameEnglish != null && sameEnglish.id >= 0) { // a stored entry (in-chapter ones have negative ids)
                pending += toPending(
                    "same English as existing ${sameEnglish.source} (alias?)" +
                        (if (d.notes.isNotBlank()) "; ${d.notes.trim()}" else "")
                )
                continue
            }
            if (sameEnglish != null || key in candidateEnglish) continue

            val candidate = TermCandidate(
                novelId = novelId,
                source = d.source,
                english = d.english,
                category = GlossaryTerm.normalizeCategory(d.category),
                gender = GlossaryTerm.normalizeGender(d.gender),
                notes = d.notes.trim(),
                aliases = TermAliases.encode(d.aliases.filter { it != d.source }),
                chapterNum = chapterNum,
                confidence = d.confidence,
            )
            newCandidates += candidate
            known += TermCandidates.toTerm(candidate)
            candidateSources += d.source
            candidateEnglish += key
        }
        return TermDecisions(newCandidates, updates.values.toList(), pending)
    }

    private fun isPlausible(d: DiscoveredTerm): Boolean =
        d.source.isNotEmpty() && d.english.isNotEmpty() &&
            d.source.length <= 40 && d.english.length <= 80 &&
            containsCjk(d.source) && !containsCjk(d.english)
}

/**
 * Terms found while translating (docs/translation-pipeline.md): a term the model reports is only
 * a [TermCandidate] at first. The glossary exists to keep a name the same across chapters, so a
 * candidate becomes a glossary entry when its source turns up in another chapter, with the
 * rendering it was first given (the earlier chapter already uses it). Terms seen in one chapter
 * only, most of what models report, never reach the glossary.
 */
object TermCandidates {
    /** True if [c] is used in the prompt for the rest of its own chapter, and auto-added when it recurs. */
    fun confident(c: TermCandidate, options: TranslationOptions): Boolean =
        options.autoAddTerms && c.confidence >= options.autoAddMinConfidence

    /** [c] as an auto-added, unlocked bible entry first seen in its chapter. */
    fun toTerm(c: TermCandidate, id: Long = 0): GlossaryTerm = GlossaryTerm(
        id = id,
        novelId = c.novelId,
        source = c.source,
        english = c.english,
        category = c.category,
        gender = c.gender,
        notes = c.notes,
        aliases = c.aliases,
        firstChapter = c.chapterNum,
        autoAdded = true,
        locked = false,
    )

    /** [c] for review (low confidence or auto-add off), with [context] from the chapter it recurred in. */
    fun toPending(c: TermCandidate, context: String): PendingTerm = PendingTerm(
        novelId = c.novelId,
        source = c.source,
        suggestedEnglish = c.english,
        category = c.category,
        context = context,
        chapterNum = c.chapterNum,
        gender = c.gender,
        notes = c.notes,
        confidence = c.confidence,
    )

    /** Candidates whose source occurs in a chapter, split by where they were first reported. */
    data class Sightings(
        /** First reported in another chapter: they recur, so they are promoted. */
        val recurring: List<TermCandidate>,
        /** First reported in this chapter (it is being translated again): reused, still candidates. */
        val sameChapter: List<TermCandidate>,
    )

    /** [candidates] whose source or an alias occurs in [storyText] (the chapter without author's notes). */
    fun sightings(candidates: List<TermCandidate>, chapterNum: Int, storyText: String): Sightings {
        val seen = candidates.filter { c -> TermAliases.formsOf(toTerm(c)).any { storyText.contains(it) } }
        val (same, other) = seen.partition { it.chapterNum == chapterNum }
        return Sightings(recurring = other, sameChapter = same)
    }
}
