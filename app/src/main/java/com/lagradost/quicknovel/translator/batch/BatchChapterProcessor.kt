package com.lagradost.quicknovel.translator.batch

import com.lagradost.quicknovel.translator.QualityChecks
import com.lagradost.quicknovel.translator.StoryContext
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.TranslationOptions
import com.lagradost.quicknovel.translator.TranslationResult
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import kotlinx.coroutines.CancellationException

/**
 * Post-processing of one chapter of batch results, in chunk order, with the same code as live
 * translation ([TranslationEngine.assembleChapter]): the `===TERMS===` tail is parsed, discovered
 * terms go through BibleMerger against the live bible, and QualityChecks run on every chunk.
 *
 * A chunk goes to the live path when
 * - it is missing, failed, cut off or blocked → full live translation (with the fallback model);
 * - it reads like a refusal → full live translation;
 * - it fails the checks in strict mode → the strict re-request with a reminder; the better
 *   attempt is kept.
 *
 * [allowLive] is asked before every live call (spend cap). When a chunk cannot be produced at all
 * the chapter is not stored ([Outcome.result] = null) and is left for the normal live path.
 */
class BatchChapterProcessor(
    private val engine: TranslationEngine,
    private val options: TranslationOptions,
    private val allowLive: suspend () -> Boolean = { true },
) {
    data class Outcome(
        /** The chapter to store, or null when some chunk could not be produced. */
        val result: TranslationResult?,
        /** Chunks fully re-translated live (missing / failed / refused). */
        val liveChunks: Int = 0,
        /** Chunks re-requested in strict mode after failing the checks. */
        val strictRetries: Int = 0,
        val error: String? = null,
    )

    private class ChunkUnavailable(message: String) : Exception(message)

    suspend fun process(
        novelId: String,
        chapterNum: Int,
        chunks: List<String>,
        outcomes: List<ChunkOutcome>,
        bible: List<GlossaryTerm>,
        context: StoryContext,
    ): Outcome {
        require(chunks.size == outcomes.size) { "chunks and outcomes differ in size" }
        var liveChunks = 0
        var strictRetries = 0
        return try {
            val result = engine.assembleChapter(chunks, novelId, chapterNum, bible, context, options) { request ->
                val outcome = outcomes[request.index]
                val batchOut = (outcome as? ChunkOutcome.Ok)?.let { engine.evaluateReply(request.chunk, it.content, request.checkTerms) }
                val unusable = batchOut == null || batchOut.translation.isBlank() ||
                    QualityChecks.looksLikeRefusal(request.chunk, batchOut.translation)
                when {
                    unusable -> {
                        val why = when (outcome) {
                            is ChunkOutcome.Failed -> outcome.reason
                            ChunkOutcome.Missing -> "missing from the batch results"
                            is ChunkOutcome.Ok -> "refused or empty"
                        }
                        if (!allowLive()) throw ChunkUnavailable("passage ${request.index + 1}: $why (live re-translation not allowed)")
                        val live = try {
                            engine.translateChunkLive(request, options)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw ChunkUnavailable("passage ${request.index + 1}: $why; live re-translation failed: ${e.message}")
                        }
                        if (live.translation.isBlank()) throw ChunkUnavailable("passage ${request.index + 1}: $why; live re-translation was empty")
                        liveChunks++
                        live
                    }
                    options.strictMode && batchOut!!.issueCount > 0 && allowLive() -> {
                        strictRetries++
                        engine.strictRetryLive(request, batchOut, options)
                    }
                    else -> batchOut!!
                }
            }
            if (result.englishText.isBlank()) Outcome(null, liveChunks, strictRetries, "empty translation")
            else Outcome(result, liveChunks, strictRetries)
        } catch (e: ChunkUnavailable) {
            Outcome(null, liveChunks, strictRetries, e.message)
        }
    }
}
