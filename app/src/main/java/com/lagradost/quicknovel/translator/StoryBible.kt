package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm

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
    /** Insert confident new terms into the bible immediately (otherwise everything goes to pending). */
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
) {
    companion object {
        const val DEFAULT_AUTO_ADD_CONFIDENCE = 0.6f
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

/** Whole-word, case-sensitive name replacement for applying a rename to stored translations. */
object NameReplacer {
    private fun pattern(name: String) = Regex("""(?<![\p{L}\p{N}])${Regex.escape(name)}(?![\p{L}\p{N}])""")

    fun countOccurrences(text: String, name: String): Int =
        if (name.isBlank()) 0 else pattern(name).findAll(text).count()

    /** Replaces whole-word occurrences of [old] with [new]; possessives ("Lin Feng's") are kept. */
    fun replace(text: String, old: String, new: String): String =
        if (old.isBlank() || old == new) text else text.replace(pattern(old), Regex.escapeReplacement(new))
}

/** What to do with the terms a chunk reported. */
data class TermDecisions(
    /** New terms to insert as auto_added = true, locked = false. */
    val autoAdd: List<GlossaryTerm> = emptyList(),
    /** Existing unlocked auto-added terms that gained information (gender / notes). */
    val updates: List<GlossaryTerm> = emptyList(),
    /** Terms for manual review (low confidence, conflicts, auto-add disabled). */
    val pending: List<PendingTerm> = emptyList(),
)

/**
 * Decides how discovered terms enter the Story Bible.
 *
 * - Unknown source term, confidence >= threshold, auto-add on, and the source really occurs in
 *   the chunk → auto-added (auto_added = true, locked = false) so later chunks/chapters use it.
 * - Unknown source term otherwise → pending.
 * - Known source term (or alias) with a different English rendering → pending with notes
 *   "conflicts with existing X". The existing term is never overwritten.
 * - Known source term with the same rendering → ignored, except that an unlocked auto-added
 *   term with unknown gender takes a confident gender (and notes if it had none).
 * - A new source term whose English equals an existing term's English → pending (probably an alias).
 */
object BibleMerger {
    private val CJK = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]""")

    fun containsCjk(text: String): Boolean = CJK.containsMatchIn(text)

    fun decide(
        discovered: List<DiscoveredTerm>,
        bible: List<GlossaryTerm>,
        novelId: String,
        chapterNum: Int,
        sourceChunk: String,
        options: TranslationOptions = TranslationOptions(),
    ): TermDecisions {
        val bySource = HashMap<String, GlossaryTerm>()
        for (term in bible) for (form in TermAliases.formsOf(term)) bySource.putIfAbsent(form, term)
        val byEnglish = bible.associateBy { it.english.trim().lowercase() }

        val autoAdd = mutableListOf<GlossaryTerm>()
        val updates = LinkedHashMap<Long, GlossaryTerm>()
        val pending = mutableListOf<PendingTerm>()
        val context = sourceChunk.take(200)

        val candidates = discovered
            .map { it.copy(source = it.source.trim(), english = it.english.trim()) }
            .filter { isPlausible(it) }
            .sortedByDescending { it.confidence }
            .distinctBy { it.source }

        for (d in candidates) {
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
                } else {
                    val note = "conflicts with existing ${existing.english}" +
                        (if (d.notes.isNotBlank()) "; ${d.notes.trim()}" else "")
                    pending += toPending(note)
                }
                continue
            }

            val sameEnglish = byEnglish[d.english.lowercase()]
            if (sameEnglish != null) {
                pending += toPending(
                    "same English as existing ${sameEnglish.source} (alias?)" +
                        (if (d.notes.isNotBlank()) "; ${d.notes.trim()}" else "")
                )
                continue
            }

            if (options.autoAddTerms && d.confidence >= options.autoAddMinConfidence && sourceChunk.contains(d.source)) {
                autoAdd += GlossaryTerm(
                    novelId = novelId,
                    source = d.source,
                    english = d.english,
                    category = GlossaryTerm.normalizeCategory(d.category),
                    gender = GlossaryTerm.normalizeGender(d.gender),
                    notes = d.notes.trim(),
                    aliases = TermAliases.encode(d.aliases.filter { it != d.source }),
                    firstChapter = chapterNum,
                    autoAdded = true,
                    locked = false,
                )
            } else {
                pending += toPending(d.notes.trim())
            }
        }
        return TermDecisions(autoAdd, updates.values.toList(), pending)
    }

    private fun isPlausible(d: DiscoveredTerm): Boolean =
        d.source.isNotEmpty() && d.english.isNotEmpty() &&
            d.source.length <= 40 && d.english.length <= 80 &&
            containsCjk(d.source) && !containsCjk(d.english)
}
