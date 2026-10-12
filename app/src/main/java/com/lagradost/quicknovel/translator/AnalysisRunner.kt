package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm
import kotlinx.coroutines.CancellationException

/**
 * The "Analyze novel" pre-pass without Android: author's-note stripping, segmentation into
 * ~[CostEstimator.ANALYSIS_CHARS_PER_CALL]-character requests, the extraction calls, the
 * [AnalysisFilter] clean-up and the conversion into new Story Bible entries. Persistence and the
 * model call are injected so the whole flow is unit-testable; [NovelAnalyzer] wires it to the
 * database and the engine.
 */
object AnalysisRunner {
    /** One chapter's plain text ([index] is the chapter index, i.e. the translation key). */
    data class Chapter(val index: Int, val text: String)

    /**
     * One analysis request: consecutive chapters (or a piece of a long one) up to the size limit.
     * [text] is what is sent (author's notes removed); [rawChapters] are the full texts of the
     * chapters it covers, for the author's-note check.
     */
    data class Segment(val firstIndex: Int, val lastIndex: Int, val text: String, val rawChapters: List<String>) {
        val label: String
            get() = if (firstIndex == lastIndex) "chapter ${firstIndex + 1}" else "chapters ${firstIndex + 1}-${lastIndex + 1}"
    }

    data class Result(
        /** The premise after the run (the previous one when no call returned a premise). */
        val premise: String,
        val premiseChanged: Boolean,
        val termsAdded: Int,
        val pendingAdded: Int,
        val callsSucceeded: Int,
        val callsFailed: Int,
        /** Message of the last failed call, if any. */
        val lastError: String?,
        /** Chapters whose text was analyzed. */
        val chaptersAnalyzed: Int,
        val dropped: List<AnalysisFilter.Dropped> = emptyList(),
    ) {
        /** At least one extraction call came back: the novel counts as analyzed. */
        val succeeded: Boolean get() = callsSucceeded > 0
    }

    /** Model call: (excerpt, known source forms, premise so far, chapter label) → extraction. */
    fun interface Extractor {
        suspend fun extract(excerpt: String, known: Set<String>, premise: String, label: String): BibleExtraction
    }

    /** Splits chapters (author's notes removed) into requests of at most [limit] characters. */
    fun segment(chapters: List<Chapter>, limit: Int = CostEstimator.ANALYSIS_CHARS_PER_CALL): List<Segment> {
        val out = mutableListOf<Segment>()
        var first = -1
        var last = -1
        val raw = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) out += Segment(first, last, sb.toString().trim(), raw.toList())
            sb.clear(); raw.clear(); first = -1
        }
        for (ch in chapters) {
            val story = AuthorNotes.strip(ch.text)
            if (story.isBlank()) continue
            for (piece in TranslationEngine.splitIntoChunks(story, limit)) {
                if (sb.isNotEmpty() && sb.length + piece.length + 2 > limit) flush()
                if (first < 0) first = ch.index
                last = ch.index
                if (raw.lastOrNull() !== ch.text) raw += ch.text
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(piece)
            }
        }
        flush()
        return out
    }

    /** What one extraction contributes to the bible. */
    data class NewEntries(val terms: List<GlossaryTerm>, val pending: List<PendingTerm>)

    /**
     * Turns filtered [terms] into new bible entries: skips forms already in [known] (which is
     * updated), non-CJK sources, CJK in the English, sources not in [segmentText]; an entry whose
     * English equals an existing entry's English goes to review as a possible alias.
     */
    fun newEntries(
        novelId: String,
        terms: List<DiscoveredTerm>,
        segment: Segment,
        chapters: List<Chapter>,
        known: MutableSet<String>,
        knownEnglish: MutableMap<String, String>,
    ): NewEntries {
        val out = mutableListOf<GlossaryTerm>()
        val pending = mutableListOf<PendingTerm>()
        for (d in terms.sortedByDescending { it.confidence }) {
            val source = d.source.trim()
            val english = d.english.trim()
            if (source.isEmpty() || english.isEmpty() || source in known) continue
            if (!BibleMerger.containsCjk(source) || BibleMerger.containsCjk(english)) continue
            if (!segment.text.contains(source)) continue // not actually in the excerpt
            val category = GlossaryTerm.normalizeCategory(d.category)
            val gender = GlossaryTerm.normalizeGender(d.gender)
            val firstChapter = chapters.firstOrNull { it.text.contains(source) }?.index ?: segment.firstIndex
            val sameEnglish = knownEnglish[AnalysisFilter.englishKey(english)]
            if (sameEnglish != null) {
                pending += PendingTerm(
                    novelId = novelId, source = source, suggestedEnglish = english, category = category,
                    context = segment.text.take(200), chapterNum = firstChapter, gender = gender,
                    notes = "same English as existing $sameEnglish (alias?)" + (if (d.notes.isNotBlank()) "; ${d.notes.trim()}" else ""),
                    confidence = d.confidence,
                )
                known += source
                continue
            }
            val aliases = d.aliases.map { it.trim() }.filter { it.isNotEmpty() && it != source && it !in known }
            out += GlossaryTerm(
                novelId = novelId,
                source = source,
                english = english,
                category = category,
                gender = gender,
                notes = d.notes.trim(),
                aliases = TermAliases.encode(aliases),
                firstChapter = firstChapter,
                autoAdded = true,
                locked = false,
            )
            known += source
            known += aliases
            knownEnglish[AnalysisFilter.englishKey(english)] = source
        }
        return NewEntries(out, pending)
    }

    /**
     * Runs the extraction over [chapters] (raw plain text, author's notes included).
     *
     * A failing call ([TranslationApiException]) is counted and skipped, except an invalid API
     * key, which is rethrown; cancellation is rethrown. [saveTerms] / [savePending] are called
     * after each segment and return how many rows they stored.
     */
    suspend fun run(
        novelId: String,
        chapters: List<Chapter>,
        bible: List<GlossaryTerm>,
        previousPremise: String,
        extractor: Extractor,
        saveTerms: suspend (List<GlossaryTerm>) -> Int,
        savePending: suspend (List<PendingTerm>) -> Int = { 0 },
        onSegment: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val known = bible.flatMap { TermAliases.formsOf(it) }.toMutableSet()
        val protectedSources = known.toSet()
        val knownEnglish = bible.associateTo(HashMap()) { AnalysisFilter.englishKey(it.english) to it.source }
        var premise = previousPremise
        var added = 0
        var pendingAdded = 0
        var ok = 0
        var failed = 0
        var lastError: String? = null
        val dropped = mutableListOf<AnalysisFilter.Dropped>()

        val segments = segment(chapters)
        for ((s, segment) in segments.withIndex()) {
            onSegment(s + 1, segments.size)
            val extraction = try {
                extractor.extract(segment.text, known, premise, segment.label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationApiException) {
                failed++
                lastError = e.message
                if (e.message?.startsWith("Invalid API key") == true) throw e
                continue
            }
            ok++
            if (extraction.premise.isNotBlank()) premise = extraction.premise.trim()
            val filtered = AnalysisFilter.filter(extraction.terms, segment.rawChapters, protectedSources)
            dropped += filtered.dropped
            val entries = newEntries(novelId, filtered.terms, segment, chapters, known, knownEnglish)
            if (entries.terms.isNotEmpty()) added += saveTerms(entries.terms)
            if (entries.pending.isNotEmpty()) pendingAdded += savePending(entries.pending)
        }
        return Result(
            premise = premise,
            premiseChanged = premise.isNotBlank() && premise != previousPremise,
            termsAdded = added,
            pendingAdded = pendingAdded,
            callsSucceeded = ok,
            callsFailed = failed,
            lastError = lastError,
            chaptersAnalyzed = segments.flatMap { (it.firstIndex..it.lastIndex).toList() }.distinct().size,
            dropped = dropped,
        )
    }
}
