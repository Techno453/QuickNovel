package com.lagradost.quicknovel.ui.translate

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.translator.ChapterTranslationRepository
import com.lagradost.quicknovel.translator.EngineConfig
import com.lagradost.quicknovel.translator.LlmProviders
import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.SpendCaps
import com.lagradost.quicknovel.translator.SpendKind
import com.lagradost.quicknovel.translator.SpendLedger
import com.lagradost.quicknovel.translator.SpendMeter
import com.lagradost.quicknovel.translator.SpendPricing
import com.lagradost.quicknovel.translator.SpendTag
import com.lagradost.quicknovel.translator.StoryBibleRepository
import com.lagradost.quicknovel.translator.StoryContext
import com.lagradost.quicknovel.translator.TokensPerChar
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.batch.BatchAssembler
import com.lagradost.quicknovel.translator.batch.BatchChapterInput
import com.lagradost.quicknovel.translator.batch.BatchChapterProcessor
import com.lagradost.quicknovel.translator.batch.BatchChapterStatus
import com.lagradost.quicknovel.translator.batch.BatchCost
import com.lagradost.quicknovel.translator.batch.BatchOutputParser
import com.lagradost.quicknovel.translator.batch.BatchRequestBuilder
import com.lagradost.quicknovel.translator.batch.BatchStatus
import com.lagradost.quicknovel.translator.batch.BuiltChapter
import com.lagradost.quicknovel.translator.batch.ChunkOutcome
import com.lagradost.quicknovel.translator.batch.OpenAiBatchClient
import com.lagradost.quicknovel.translator.db.BatchChapter
import com.lagradost.quicknovel.translator.db.BatchJob
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Overnight pre-translation through the OpenAI Batch API: submission (run by
 * [TranslationForegroundService] in its main job slot), polling ([BatchPollWorker]) and storing
 * the results. See docs/translation-pipeline.md, "Overnight batch".
 */
object BatchManager {
    private const val TAG = "BatchManager"

    /** A PREPARING row older than this was left by a killed process. */
    private const val STALE_PREPARING_MS = 2 * 60 * 60 * 1000L

    private val mapper = jacksonObjectMapper()
    private val pollMutex = Mutex()

    /** True if the selected model can use the Batch API (OpenAI's own endpoint, with a key). */
    fun isAvailable(config: EngineConfig): Boolean =
        OpenAiBatchClient.supports(config.baseUrl) && config.apiKey.isNotBlank()

    sealed class SubmitResult {
        data class Submitted(val job: BatchJob) : SubmitResult()
        data class Refused(val message: String) : SubmitResult()
        data class Failed(val message: String) : SubmitResult()
        object NothingToDo : SubmitResult()
    }

    private fun tokensPerChar(config: EngineConfig): TokensPerChar =
        ModelPresets.byId(config.presetId)?.tokensPerChar
            ?: com.lagradost.quicknovel.translator.CostEstimator.familyFor(config.model).tokensPerChar

    /** Chapter indices of [novelId] that are in a batch that has not finished. */
    suspend fun chaptersInUnfinishedBatches(ctx: Context, novelId: String): Set<Int> {
        val dao = TranslatorDatabase.getInstance(ctx).batchDao()
        val jobs = dao.jobsForNovel(novelId, BatchStatus.UNFINISHED_NAMES)
        if (jobs.isEmpty()) return emptySet()
        return dao.chapterNumsOf(jobs.map { it.id }).toHashSet()
    }

    suspend fun latestForNovel(ctx: Context, novelId: String): BatchJob? =
        TranslatorDatabase.getInstance(ctx).batchDao().latestForNovel(novelId)

    /**
     * Builds and submits a batch for chapters [fromIndex]..[toIndex] that are not translated and
     * not already in an unfinished batch. The caller has run the automatic analysis already.
     *
     * @param fetch loads chapter i's source
     * @param onProgress "fetching" progress (current, total)
     */
    suspend fun submit(
        ctx: Context,
        novelId: String,
        apiName: String,
        novelName: String,
        novelAuthor: String?,
        sourceLang: String,
        chapters: List<ChapterData>,
        fromIndex: Int,
        toIndex: Int,
        fetch: suspend (Int) -> String?,
        onProgress: (current: Int, total: Int) -> Unit,
    ): SubmitResult {
        val config = SecurePrefs.engineConfig(ctx)
        if (!isAvailable(config)) {
            return SubmitResult.Refused("Overnight batch needs an OpenAI model (api.openai.com) with an API key.")
        }
        val db = TranslatorDatabase.getInstance(ctx)
        val dao = db.batchDao()
        val translated = db.translationDao().getTranslatedChapterNums(novelId).toHashSet()
        val inBatch = chaptersInUnfinishedBatches(ctx, novelId)
        val last = minOf(toIndex, chapters.size - 1)
        val targets = (fromIndex.coerceAtLeast(0)..last).filter { it !in translated && it !in inBatch }
        if (targets.isEmpty()) return SubmitResult.NothingToDo

        val now = System.currentTimeMillis()
        var job = BatchJob(
            novelId = novelId, apiName = apiName, novelName = novelName, novelAuthor = novelAuthor,
            sourceLang = sourceLang, model = config.model, presetId = config.presetId,
            status = BatchStatus.PREPARING.name, createdAt = now, updatedAt = now,
            fromChapter = targets.first(), toChapter = targets.last(), requestCount = 0, estimatedUsd = 0.0,
        )
        job = job.copy(id = dao.insertJob(job))

        try {
            val inputs = mutableListOf<BatchChapterInput>()
            targets.forEachIndexed { i, chapterIdx ->
                onProgress(i + 1, targets.size)
                val source = try {
                    fetch(chapterIdx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Batch: could not load chapter $chapterIdx", e)
                    null
                }
                if (!source.isNullOrBlank()) inputs += BatchChapterInput(chapterIdx, chapters[chapterIdx].name, source)
            }
            if (inputs.isEmpty()) return fail(ctx, job, "Could not load any chapter source.")

            // Snapshot of the Story Bible and context, taken now (requests cannot see later discoveries).
            val chapterInputs = StoryBibleRepository.loadChapterInputs(ctx, novelId, inputs.first().chapterNum, sourceLang)
            val context = StoryContext(premise = chapterInputs.context.premise, style = chapterInputs.context.style)
            val built = BatchRequestBuilder(config, sourceLang).build(novelId, inputs, chapterInputs.bible, context)
            if (built.lines.isEmpty()) return fail(ctx, job, "The chapters had no text to translate.")

            val estimate = BatchCost.estimate(
                built.chapters, SpendPricing.pricingFor(config), tokensPerChar(config), sourceLang, chapterInputs.bible.size
            )
            val check = SpendLedger.checkStart(ctx, estimate)
            if (check !is SpendCaps.StartCheck.Allowed) {
                dao.updateJob(job.copy(status = BatchStatus.FAILED.name, error = check.message, updatedAt = System.currentTimeMillis()))
                return SubmitResult.Refused(check.message ?: "Over the spend cap")
            }

            val client = OpenAiBatchClient(config.apiKey, config.baseUrl)
            val fileId = client.uploadJsonl(BatchRequestBuilder.toJsonl(built.lines), "quicknovel-${job.id}.jsonl")
            val info = client.createBatch(fileId, mapOf("app" to "QuickNovel", "novel" to novelName.take(200), "job" to job.id.toString()))
            dao.insertChapters(built.chapters.map { it.toEntity(job.id) })
            job = job.copy(
                status = BatchStatus.fromOpenAi(info.status, info.outputFileId != null, info.errorFileId != null).name,
                openaiBatchId = info.id,
                inputFileId = fileId,
                requestCount = built.requestCount,
                estimatedUsd = estimate ?: 0.0,
                fromChapter = built.chapters.first().chapterNum,
                toChapter = built.chapters.last().chapterNum,
                updatedAt = System.currentTimeMillis(),
            )
            dao.updateJob(job)
            Log.i(TAG, "Submitted batch ${info.id} for $novelId: ${built.chapters.size} chapters, ${built.requestCount} requests")
            return SubmitResult.Submitted(job)
        } catch (e: CancellationException) {
            fail(ctx, job, "Cancelled while preparing")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Batch submission failed", e)
            return fail(ctx, job, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fail(ctx: Context, job: BatchJob, message: String): SubmitResult.Failed {
        try {
            TranslatorDatabase.getInstance(ctx).batchDao().updateJob(
                job.copy(status = BatchStatus.FAILED.name, error = message, updatedAt = System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not mark batch ${job.id} failed", e)
        }
        return SubmitResult.Failed(message)
    }

    private fun BuiltChapter.toEntity(batchId: Long) = BatchChapter(
        batchId = batchId,
        chapterNum = chapterNum,
        chapterName = chapterName,
        sourceHash = sourceHash,
        chunksJson = mapper.writeValueAsString(chunks),
        status = BatchChapterStatus.PENDING.name,
    )

    private fun BatchChapter.toBuilt() = BuiltChapter(chapterNum, chapterName, sourceHash, mapper.readValue<List<String>>(chunksJson))

    private fun openAiKey(ctx: Context): String? = SecurePrefs.getApiKey(ctx, LlmProviders.OPENAI.id)

    /** What a poll found, for notifications. */
    data class PollReport(val stillActive: Int, val finished: List<BatchJob>)

    /**
     * Polls every unfinished batch once and stores the results of finished ones. Serialized: app
     * start, the periodic worker and opening the translate / spend screens may all call it.
     */
    suspend fun pollAll(ctx: Context): PollReport = pollMutex.withLock {
        val dao = TranslatorDatabase.getInstance(ctx).batchDao()
        val finished = mutableListOf<BatchJob>()
        val now = System.currentTimeMillis()
        for (job in dao.jobsWithStatus(listOf(BatchStatus.PREPARING.name))) {
            // Left behind by a killed process (submission never finished).
            if (now - job.updatedAt > STALE_PREPARING_MS && job.openaiBatchId == null) {
                dao.updateJob(job.copy(status = BatchStatus.FAILED.name, error = "Preparing was interrupted", updatedAt = now))
            }
        }
        for (job in dao.jobsWithStatus(BatchStatus.ACTIVE_NAMES)) {
            try {
                val updated = pollOne(ctx, job)
                if (BatchStatus.fromName(updated.status).isFinished) finished += updated
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Polling batch ${job.id} failed", e)
            }
        }
        PollReport(dao.jobsWithStatus(BatchStatus.ACTIVE_NAMES).size, finished)
    }

    private suspend fun pollOne(ctx: Context, job: BatchJob): BatchJob {
        val dao = TranslatorDatabase.getInstance(ctx).batchDao()
        val batchId = job.openaiBatchId ?: return job
        val key = openAiKey(ctx) ?: return job
        val client = OpenAiBatchClient(key)
        val info = client.getBatch(batchId)
        val status = BatchStatus.fromOpenAi(info.status, info.outputFileId != null, info.errorFileId != null)
        var updated = job.copy(
            status = status.name,
            outputFileId = info.outputFileId ?: job.outputFileId,
            errorFileId = info.errorFileId ?: job.errorFileId,
            completedCount = info.completed,
            failedCount = info.failed,
            error = info.errors ?: job.error,
            updatedAt = System.currentTimeMillis(),
        )
        if (status == BatchStatus.FAILED || status == BatchStatus.EXPIRED || status == BatchStatus.CANCELLED) {
            updated = updated.copy(completedAt = System.currentTimeMillis())
        }
        dao.updateJob(updated)
        if (status != BatchStatus.PROCESSING) return updated
        return processResults(ctx, updated, client, info.status)
    }

    /** Downloads the output (and error) file, stores each chapter in order, records the spend. */
    private suspend fun processResults(ctx: Context, job: BatchJob, client: OpenAiBatchClient, openAiStatus: String): BatchJob {
        val db = TranslatorDatabase.getInstance(ctx)
        val dao = db.batchDao()
        val output = job.outputFileId?.let { client.downloadFile(it) }
        val errors = job.errorFileId?.let {
            try {
                client.downloadFile(it)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the error file only explains failures; missing chunks are handled anyway
            }
        }
        val results = BatchOutputParser.parse(output) + BatchOutputParser.parse(errors)
        val rows = dao.chapters(job.id)
        val built = rows.map { it.toBuilt() }
        val assembled = BatchAssembler.assemble(job.novelId, built, results).associateBy { it.chapterNum }

        val preset = ModelPresets.byId(job.presetId)
        val key = openAiKey(ctx).orEmpty()
        val config = (preset?.toEngineConfig(key) ?: SecurePrefs.engineConfig(ctx).copy(model = job.model))
        val pricing = SpendPricing.pricingFor(config)
        val engine = TranslationEngine(config, job.sourceLang, fallback = SecurePrefs.fallbackEngineConfig(ctx))
        val options = SecurePrefs.translationOptions(ctx)
        val meter = SpendMeter()
        val processor = BatchChapterProcessor(engine, options) {
            key.isNotBlank() && SpendLedger.stopMessage(ctx, meter) == null
        }

        var batchUsd = 0.0
        for (row in rows) {
            if (BatchChapterStatus.fromName(row.status) != BatchChapterStatus.PENDING) {
                continue
            }
            val chapter = assembled[row.chapterNum] ?: continue
            val chapterBuilt = built.first { it.chapterNum == row.chapterNum }
            val chapterUsd = BatchCost.actual(chapter.usage, pricing)
            batchUsd += chapterUsd
            var status = BatchChapterStatus.NEEDS_LIVE
            var note: String? = null
            ChapterTranslationRepository.withChapterLock(job.novelId, row.chapterNum) {
                // A preview row (no glossary) does not count: the batch result replaces it.
                if (ChapterTranslationRepository.servable(
                        ChapterTranslationRepository.getCachedEntry(ctx, job.novelId, row.chapterNum)
                    ) != null
                ) {
                    status = BatchChapterStatus.SKIPPED
                    note = "Already translated when the results arrived"
                    return@withChapterLock
                }
                if (chapter.okCount == 0) {
                    // Nothing usable came back (every request failed, or expired / cancelled before it
                    // ran): leave it to the normal path, with the error file's reason when there is one.
                    status = BatchChapterStatus.NEEDS_LIVE
                    note = chapter.outcomes.filterIsInstance<ChunkOutcome.Failed>().firstOrNull()?.reason
                        ?.let { "No results for this chapter: $it" } ?: "No results for this chapter"
                    return@withChapterLock
                }
                val inputs = StoryBibleRepository.loadChapterInputs(ctx, job.novelId, row.chapterNum, job.sourceLang)
                val outcome = withContext(SpendTag(SpendKind.TRANSLATE, job.novelId, row.chapterNum, job.id, meter)) {
                    processor.process(job.novelId, row.chapterNum, chapterBuilt.chunks, chapter.outcomes, inputs.bible, inputs.context)
                }
                val result = outcome.result
                if (result == null) {
                    status = BatchChapterStatus.NEEDS_LIVE
                    note = outcome.error
                } else {
                    StoryBibleRepository.persistChapter(
                        ctx, job.novelId, row.chapterNum, result, job.model, job.sourceLang, row.sourceHash
                    )
                    TranslatedChapterFiles.save(
                        ctx, job.apiName, job.novelAuthor, job.novelName, row.chapterNum, row.chapterName, result.englishText
                    )
                    status = BatchChapterStatus.STORED
                    val live = outcome.liveChunks + outcome.strictRetries
                    if (live > 0) note = "$live passage(s) re-done live (${outcome.liveChunks} missing/failed, ${outcome.strictRetries} strict retries)"
                }
            }
            SpendLedger.recordBatchChapter(
                ctx, job.id, job.novelId, row.chapterNum, config.providerId, job.model, chapter.usage, chapterUsd, pricing != null,
            )
            dao.setChapterStatus(job.id, row.chapterNum, status.name, note)
        }

        val finalStatus = BatchStatus.afterProcessing(openAiStatus)
        val totalBatchUsd = TranslatorDatabase.getInstance(ctx).spendDao().totalForBatch(job.id)
        val done = job.copy(
            status = finalStatus.name,
            actualUsd = totalBatchUsd,
            completedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.updateJob(done)
        Log.i(TAG, "Batch ${job.id} stored: $batchUsd at batch price this run, $totalBatchUsd in total incl. live re-dos")
        return done
    }

    /** Cancels a running batch at OpenAI (finished chunks are still stored when it ends). */
    suspend fun cancel(ctx: Context, jobId: Long): String? {
        val dao = TranslatorDatabase.getInstance(ctx).batchDao()
        val job = dao.getJob(jobId) ?: return "Batch not found"
        val batchId = job.openaiBatchId ?: run {
            dao.updateJob(job.copy(status = BatchStatus.CANCELLED.name, updatedAt = System.currentTimeMillis()))
            return null
        }
        val key = openAiKey(ctx) ?: return "No OpenAI API key"
        return try {
            val info = OpenAiBatchClient(key).cancelBatch(batchId)
            dao.updateJob(
                job.copy(status = BatchStatus.fromOpenAi(info.status, info.outputFileId != null, info.errorFileId != null).name, updatedAt = System.currentTimeMillis())
            )
            BatchScheduler.ensurePolling(ctx)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Cancel failed"
        }
    }

    /** Chapter counts of a job by outcome, for the status row. */
    suspend fun chapterCounts(ctx: Context, jobId: Long): Map<BatchChapterStatus, Int> =
        TranslatorDatabase.getInstance(ctx).batchDao().chapters(jobId)
            .groupingBy { BatchChapterStatus.fromName(it.status) }.eachCount()
}
