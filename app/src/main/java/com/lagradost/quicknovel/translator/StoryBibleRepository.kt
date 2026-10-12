package com.lagradost.quicknovel.translator

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.db.ChapterSummary
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.NovelContext
import com.lagradost.quicknovel.translator.db.PendingTerm
import com.lagradost.quicknovel.translator.db.TranslatedChapter
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the Story Bible + continuity context for a chapter and persists what a translation
 * produced (chapter text + quality counts, summary, auto-added terms, pending terms).
 * Shared by the batch service, the on-demand reader path and the analysis pre-pass so all of
 * them translate with the same bible and context.
 */
object StoryBibleRepository {
    /** Previous-chapter summaries further back than this are not used as "previous chapter". */
    private const val MAX_SUMMARY_DISTANCE = 3

    private val mapper = jacksonObjectMapper()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(novelId: String) = locks.getOrPut(novelId) { Mutex() }

    /** Everything the engine needs for one chapter. */
    data class ChapterInputs(
        val bible: List<GlossaryTerm>,
        val context: StoryContext,
        val options: TranslationOptions,
    )

    /** What [persistChapter] stored. */
    data class PersistOutcome(val autoAdded: Int, val pending: Int)

    suspend fun loadStyle(ctx: Context, novelId: String, lang: String?): TranslationStyle {
        val nc = TranslatorDatabase.getInstance(ctx).novelContextDao().get(novelId)
        return TranslationStyle.resolve(
            lang = lang,
            nameStyle = nc?.nameStyle,
            honorificStyle = nc?.honorificStyle,
            styleNotes = nc?.styleNotes,
            fallbackName = SecurePrefs.getDefaultNameStyle(ctx, lang),
            fallbackHonorific = SecurePrefs.getDefaultHonorificStyle(ctx, lang),
        )
    }

    suspend fun loadChapterInputs(ctx: Context, novelId: String, chapterNum: Int, lang: String?): ChapterInputs {
        val db = TranslatorDatabase.getInstance(ctx)
        val options = SecurePrefs.translationOptions(ctx)
        val bible = db.glossaryDao().getAllTermsSync(novelId)
        val style = loadStyle(ctx, novelId, lang)
        if (!options.carryStoryContext) {
            return ChapterInputs(bible, StoryContext(style = style), options)
        }

        val premise = db.novelContextDao().get(novelId)?.premise.orEmpty()
        val previousSummary = db.chapterSummaryDao().getPrevious(novelId, chapterNum)
            ?.takeIf { chapterNum - it.chapterNum <= MAX_SUMMARY_DISTANCE }
        val previousTail = db.translationDao().getPrevious(novelId, chapterNum)
            ?.takeIf { it.chapterNum == chapterNum - 1 }
            ?.let { TranslationEngine.htmlToPlainText(it.englishText).takeLast(PromptTemplates.CONTEXT_TAIL_CHARS) }
            .orEmpty()

        return ChapterInputs(
            bible = bible,
            context = StoryContext(
                premise = premise,
                previousChapterSummary = previousSummary?.summary.orEmpty(),
                previousEnglishTail = previousTail,
                recentCharacters = previousSummary?.let { TermAliases.parse(it.charactersPresent) }.orEmpty(),
                style = style,
            ),
            options = options,
        )
    }

    /** Stores the translation and everything it discovered. Never overwrites a locked term. */
    suspend fun persistChapter(
        ctx: Context,
        novelId: String,
        chapterNum: Int,
        result: TranslationResult,
        model: String,
        lang: String,
        /** [SourceHash] of the source that was translated ("" = unknown). */
        sourceHash: String = "",
    ): PersistOutcome {
        val db = TranslatorDatabase.getInstance(ctx)
        db.translationDao().insert(
            TranslatedChapter(
                sourceHash = sourceHash,
                stale = false,
                novelId = novelId,
                chapterNum = chapterNum,
                englishText = result.englishText,
                model = result.modelUsed.ifBlank { model },
                sourceLang = lang,
                glossaryViolations = result.glossaryViolations,
                cjkLeaks = result.untranslatedFragments,
                paragraphWarnings = result.paragraphWarnings,
                usedFallback = result.usedFallback,
            )
        )
        if (result.chapterSummary.isNotBlank() || result.charactersPresent.isNotEmpty()) {
            db.chapterSummaryDao().upsert(
                ChapterSummary(
                    novelId = novelId,
                    chapterNum = chapterNum,
                    summary = result.chapterSummary,
                    charactersPresent = mapper.writeValueAsString(result.charactersPresent),
                )
            )
        }
        val added = addAutoTerms(ctx, novelId, result.autoAddedTerms)
        applyUpdates(ctx, novelId, result.updatedTerms)
        val pending = addPending(ctx, novelId, result.pendingTerms)
        return PersistOutcome(added, pending)
    }

    /** Inserts auto-added terms whose source is not in the bible yet. Returns the number inserted. */
    suspend fun addAutoTerms(ctx: Context, novelId: String, terms: List<GlossaryTerm>): Int {
        if (terms.isEmpty()) return 0
        val dao = TranslatorDatabase.getInstance(ctx).glossaryDao()
        return lockFor(novelId).withLock {
            val known = dao.getAllTermsSync(novelId).flatMap { TermAliases.formsOf(it) }.toHashSet()
            var count = 0
            for (term in terms) {
                if (term.source in known) continue
                dao.upsertTerm(term.copy(id = 0, novelId = novelId, autoAdded = true, locked = false))
                known += term.source
                count++
            }
            count
        }
    }

    /** Applies gender / notes updates to terms that are still unlocked. */
    private suspend fun applyUpdates(ctx: Context, novelId: String, updates: List<GlossaryTerm>) {
        if (updates.isEmpty()) return
        val dao = TranslatorDatabase.getInstance(ctx).glossaryDao()
        lockFor(novelId).withLock {
            val current = dao.getAllTermsSync(novelId).associateBy { it.id }
            for (u in updates) {
                val row = current[u.id] ?: continue
                if (row.locked) continue
                dao.upsertTerm(
                    row.copy(
                        gender = if (row.gender == GlossaryTerm.GENDER_UNKNOWN) u.gender else row.gender,
                        notes = row.notes.ifBlank { u.notes },
                    )
                )
            }
        }
    }

    /** Inserts pending terms that are not already pending or already in the bible with that rendering. */
    suspend fun addPending(ctx: Context, novelId: String, terms: List<PendingTerm>): Int {
        if (terms.isEmpty()) return 0
        val db = TranslatorDatabase.getInstance(ctx)
        return lockFor(novelId).withLock {
            val bible = db.glossaryDao().getAllTermsSync(novelId)
            var count = 0
            for (p in terms) {
                val inBible = bible.any { t ->
                    p.source in TermAliases.formsOf(t) && t.english.equals(p.suggestedEnglish, ignoreCase = true)
                }
                if (inBible || db.pendingTermDao().countMatching(novelId, p.source, p.suggestedEnglish) > 0) continue
                db.pendingTermDao().insert(p.copy(id = 0, novelId = novelId))
                count++
            }
            count
        }
    }

    suspend fun getContext(ctx: Context, novelId: String): NovelContext =
        TranslatorDatabase.getInstance(ctx).novelContextDao().get(novelId) ?: NovelContext(novelId = novelId)

    suspend fun updateContext(ctx: Context, novelId: String, block: (NovelContext) -> NovelContext) {
        val dao = TranslatorDatabase.getInstance(ctx).novelContextDao()
        lockFor(novelId).withLock {
            val current = dao.get(novelId) ?: NovelContext(novelId = novelId)
            dao.upsert(block(current).copy(novelId = novelId, updatedAt = System.currentTimeMillis()))
        }
    }
}
