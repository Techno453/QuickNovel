package com.lagradost.quicknovel.translator

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Runs the "Analyze novel" pre-pass ([AnalysisRunner]) against the database, for the manual
 * "Analyze novel" action and automatically before a novel's first translation (batch job,
 * reader read-ahead and the reader's on-demand path).
 *
 * - Single-flight per novel: one analysis at a time; callers that waited for a running
 *   automatic analysis see the novel as analyzed afterwards and do not run it again.
 * - State: success (at least one extraction call answered) sets novel_context.analyzed_at /
 *   analysis_chapters; failure marks nothing, so the next translation start retries. Callers
 *   that were already waiting when an automatic analysis failed do not retry immediately.
 * - Model: [SecurePrefs.analysisEngineConfig] (the "Analysis model" setting).
 */
object NovelAnalyzer {
    private const val TAG = "NovelAnalyzer"

    /** Error without an API key when the caller passes no message (the automatic pass only logs it). */
    const val NO_API_KEY = "No API key"

    enum class Stage { FETCHING, EXTRACTING }

    /** Progress and completion callbacks; both optional. */
    interface Listener {
        fun onProgress(stage: Stage, current: Int, total: Int) {}
        fun onFinished(outcome: Outcome) {}
    }

    data class Outcome(
        val succeeded: Boolean,
        val termsAdded: Int = 0,
        val pendingAdded: Int = 0,
        val premiseUpdated: Boolean = false,
        val chaptersAnalyzed: Int = 0,
        /** Why nothing (or not everything) worked; null on full success. */
        val error: String? = null,
        val dropped: Int = 0,
    )

    private val gate = OncePerKey()

    /** True if the pre-pass has succeeded for [novelId] (manually or automatically). */
    suspend fun isAnalyzed(ctx: Context, novelId: String): Boolean = try {
        TranslatorDatabase.getInstance(ctx).novelContextDao().get(novelId)?.isAnalyzed == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read analysis state", e)
        true // never block or loop on a broken DB
    }

    /** True if a translation of [novelId] would start with an automatic analysis. */
    suspend fun needsAutoAnalysis(ctx: Context, novelId: String): Boolean =
        SecurePrefs.isAutoAnalyzeEnabled(ctx) && !isAnalyzed(ctx, novelId)

    /**
     * The automatic pre-pass: analyzes the first [SecurePrefs.getAutoAnalyzeChapterCount]
     * chapters if the setting is on and the novel was never analyzed. Never throws (except
     * cancellation); a failure is logged and translation should simply go on.
     *
     * @param availableChapters size of the novel's chapter list
     * @param fetch loads chapter i's source (HTML or text); callers pass a memoizing fetcher so
     *   chapters the translation needs anyway are not downloaded twice
     * @return the outcome, or null when no analysis was needed
     */
    suspend fun ensureAnalyzed(
        ctx: Context,
        novelId: String,
        sourceLang: String,
        availableChapters: Int,
        fetch: suspend (Int) -> String?,
        listener: Listener? = null,
    ): Outcome? {
        if (availableChapters <= 0 || !SecurePrefs.isAutoAnalyzeEnabled(ctx)) return null
        return gate.runOnce(
            key = novelId,
            isDone = { isAnalyzed(ctx, novelId) },
            succeeded = { it.succeeded },
        ) {
            val count = SecurePrefs.getAutoAnalyzeChapterCount(ctx).coerceAtMost(availableChapters)
            Log.i(TAG, "Analyzing $novelId (first $count chapters) before its first translation")
            runLocked(ctx, novelId, sourceLang, count, fetch, listener).also { outcome ->
                if (!outcome.succeeded) Log.w(TAG, "Automatic analysis of $novelId failed: ${outcome.error}; translating without it")
            }
        }
    }

    /** True while an analysis of [novelId] runs in this process. */
    fun isRunning(novelId: String): Boolean = gate.isRunning(novelId)

    /**
     * The manual "Analyze novel" (always runs; a re-run refreshes the state). Never throws except
     * cancellation. [noKeyMessage] is the outcome's error without an API key (the app's
     * `translator_no_api_key`; this package has no resources).
     */
    suspend fun analyze(
        ctx: Context,
        novelId: String,
        sourceLang: String,
        chapterCount: Int,
        fetch: suspend (Int) -> String?,
        listener: Listener? = null,
        noKeyMessage: String = NO_API_KEY,
    ): Outcome = gate.withLock(novelId) {
        runLocked(ctx, novelId, sourceLang, chapterCount, fetch, listener, noKeyMessage)
    }

    private suspend fun runLocked(
        ctx: Context,
        novelId: String,
        sourceLang: String,
        count: Int,
        fetch: suspend (Int) -> String?,
        listener: Listener?,
        noKeyMessage: String = NO_API_KEY,
    ): Outcome {
        val outcome = try {
            // Recorded as analysis spend; a job's per-job meter (if any) keeps counting.
            val outer = coroutineContext[SpendTag]
            withContext(SpendTag(SpendKind.ANALYSIS, novelId, batchId = outer?.batchId, meter = outer?.meter)) {
                run(ctx, novelId, sourceLang, count, fetch, listener, noKeyMessage)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Analysis of $novelId failed", e)
            Outcome(succeeded = false, error = e.message ?: e.javaClass.simpleName)
        }
        listener?.onFinished(outcome)
        return outcome
    }

    private suspend fun run(
        ctx: Context,
        novelId: String,
        sourceLang: String,
        count: Int,
        fetch: suspend (Int) -> String?,
        listener: Listener?,
        noKeyMessage: String,
    ): Outcome {
        val config = SecurePrefs.analysisEngineConfig(ctx)
        if (config.apiKey.isBlank()) return Outcome(succeeded = false, error = noKeyMessage)
        val engine = TranslationEngine(config, sourceLang)

        val chapters = mutableListOf<AnalysisRunner.Chapter>()
        for (i in 0 until count) {
            listener?.onProgress(Stage.FETCHING, i + 1, count)
            val html = try {
                fetch(i)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Analysis: could not load chapter $i", e)
                null
            } ?: continue
            // Fetched anyway: notice when a translated chapter's source has changed.
            ChapterTranslationRepository.checkSourceHash(ctx, novelId, i, html)
            TranslationEngine.htmlToPlainText(html).takeIf { it.isNotBlank() }
                ?.let { chapters += AnalysisRunner.Chapter(i, it) }
        }
        if (chapters.isEmpty()) return Outcome(succeeded = false, error = "Could not load any chapters to analyze")

        val db = TranslatorDatabase.getInstance(ctx)
        val style = StoryBibleRepository.loadStyle(ctx, novelId, sourceLang)
        val bible = db.glossaryDao().getAllTermsSync(novelId)
        val previousPremise = db.novelContextDao().get(novelId)?.premise.orEmpty()

        val result = AnalysisRunner.run(
            novelId = novelId,
            chapters = chapters,
            bible = bible,
            previousPremise = previousPremise,
            extractor = { excerpt, known, premise, label ->
                engine.extractStoryBible(excerpt, known, premise, style, label)
            },
            saveTerms = { StoryBibleRepository.addAutoTerms(ctx, novelId, it) },
            savePending = { StoryBibleRepository.addPending(ctx, novelId, it) },
            onSegment = { current, total -> listener?.onProgress(Stage.EXTRACTING, current, total) },
        )
        if (result.dropped.isNotEmpty()) {
            Log.i(TAG, "Analysis filter dropped: " + result.dropped.joinToString { "${it.term.source} (${it.reason})" })
        }
        if (result.succeeded) {
            StoryBibleRepository.updateContext(ctx, novelId) {
                it.copy(
                    premise = if (result.premiseChanged) result.premise else it.premise,
                    analyzedAt = System.currentTimeMillis(),
                    analysisChapters = result.chaptersAnalyzed,
                )
            }
        }
        return Outcome(
            succeeded = result.succeeded,
            termsAdded = result.termsAdded,
            pendingAdded = result.pendingAdded,
            premiseUpdated = result.succeeded && result.premiseChanged,
            chaptersAnalyzed = result.chaptersAnalyzed,
            error = result.lastError,
            dropped = result.dropped.size,
        )
    }
}
