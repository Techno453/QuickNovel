package com.lagradost.quicknovel.ui.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.APIRepository
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.DOWNLOAD_EPUB_SIZE
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.network.CloudflareKiller
import com.lagradost.quicknovel.translator.ChapterTranslationRepository
import com.lagradost.quicknovel.translator.NovelAnalyzer
import com.lagradost.quicknovel.translator.ReadAheadQueue
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.StoryBibleRepository
import com.lagradost.quicknovel.translator.TranslationApiException
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.TranslationResult
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.GoogleTranslate
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the chapter translation jobs.
 * Survives the app going to the background so long work isn't interrupted.
 *
 * Two job slots:
 * - the main slot ([translationJob]): a batch translation ([ACTION_START]) or a manual
 *   "Analyze novel" ([ACTION_ANALYZE]); reserved through [TranslationState];
 * - the low-priority read-ahead worker ([readAheadJob], [ACTION_READ_AHEAD]) that keeps the
 *   next chapters after the one being read translated ([ReadAheadState.queue]). It yields to the
 *   main slot between chapters and is resumed when the main job finishes.
 *
 * Before a novel's first translation (batch or read-ahead) the analysis pre-pass runs once
 * ([NovelAnalyzer.ensureAnalyzed]); chapters it downloads are reused by the translation.
 *
 * Communicates results back to [TranslateViewModel] via the shared [TranslationState] object.
 */
class TranslationForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var translationJob: Job? = null
    @Volatile
    private var readAheadJob: Job? = null

    /** Last notification shown under [NOTIFICATION_ID], re-used when startForeground is called again. */
    @Volatile
    private var currentNotification: Notification? = null

    // Shared across all chapters in this session — once CF cookies are obtained for a host,
    // subsequent chapters on the same host reuse them without re-launching the WebView.
    private val cfKiller = CloudflareKiller()

    companion object {
        private const val TAG = "TranslationService"
        private const val CHANNEL_ID = "translation_progress"
        private const val NOTIFICATION_ID = 9001
        private const val PROGRESS_MAX = 1000
        private const val MIN_NOTIFY_INTERVAL_MS = 750L

        private const val SUMMARY_NOTIFICATION_ID = 9002

        const val ACTION_START = "start_translation"
        const val ACTION_ANALYZE = "analyze_novel"
        const val ACTION_CANCEL = "cancel_translation"
        const val ACTION_READ_AHEAD = "read_ahead"
        const val ACTION_STOP_READ_AHEAD = "stop_read_ahead"
        const val EXTRA_CHAPTER_COUNT = "chapterCount"

        /** True between onCreate and onDestroy. */
        @Volatile
        var isAlive: Boolean = false
            private set

        /**
         * Starts the "Analyze novel" pre-pass over the first [chapterCount] chapters. Shares the
         * main job slot with translation; returns false if a job is already running.
         */
        fun startAnalysis(
            ctx: Context,
            novelId: String,
            apiName: String,
            chapters: List<ChapterData>,
            chapterCount: Int
        ): Boolean {
            if (!TranslationState.tryReserve(chapters)) {
                Log.w(TAG, "A job is already running; ignoring analysis request")
                return false
            }
            val intent = Intent(ctx, TranslationForegroundService::class.java).apply {
                action = ACTION_ANALYZE
                putExtra(EXTRA_NOVEL_ID, novelId)
                putExtra(EXTRA_API_NAME, apiName)
                putExtra(EXTRA_CHAPTER_COUNT, chapterCount)
            }
            AnalysisState.post(AnalysisProgress(novelId, ctx.getString(R.string.analyzing_novel)))
            return try {
                ContextCompat.startForegroundService(ctx, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Could not start analysis", e)
                TranslationState.release()
                AnalysisState.post(
                    AnalysisProgress(novelId, "", error = ctx.getString(R.string.analysis_failed, e.message ?: ""))
                )
                false
            }
        }

        const val EXTRA_NOVEL_ID = "novelId"
        const val EXTRA_API_NAME = "apiName"
        const val EXTRA_NOVEL_NAME = "novelName"
        const val EXTRA_NOVEL_AUTHOR = "novelAuthor"
        const val EXTRA_FROM_INDEX = "fromIndex"
        const val EXTRA_TO_INDEX = "toIndex"

        /**
         * Starts a translation job. Returns false (and does nothing) if a job is already
         * running, so the pending chapter list of the running job is never clobbered.
         */
        fun startTranslation(
            ctx: Context,
            novelId: String,
            apiName: String,
            novelName: String,
            novelAuthor: String?,
            chapters: List<ChapterData>,
            fromIndex: Int,
            toIndex: Int
        ): Boolean {
            if (!TranslationState.tryReserve(chapters)) {
                Log.w(TAG, "Translation already running; ignoring new start request")
                return false
            }
            val intent = Intent(ctx, TranslationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NOVEL_ID, novelId)
                putExtra(EXTRA_API_NAME, apiName)
                putExtra(EXTRA_NOVEL_NAME, novelName)
                putExtra(EXTRA_NOVEL_AUTHOR, novelAuthor)
                putExtra(EXTRA_FROM_INDEX, fromIndex)
                putExtra(EXTRA_TO_INDEX, toIndex)
            }
            return try {
                ContextCompat.startForegroundService(ctx, intent)
                true
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background
                Log.e(TAG, "Could not start translation service", e)
                TranslationState.release()
                TranslationState.postProgress(
                    TranslationProgress(0, 0, "", error = "Could not start translation: ${e.message}")
                )
                false
            }
        }

        fun cancelTranslation(ctx: Context) {
            val intent = Intent(ctx, TranslationForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            ctx.startService(intent)
        }

        /**
         * Starts the read-ahead worker for [ReadAheadState.queue]. Call only when
         * [ReadAheadQueue.update] returned true (the caller then owns the worker claim).
         */
        fun startReadAhead(ctx: Context): Boolean {
            val intent = Intent(ctx, TranslationForegroundService::class.java).apply { action = ACTION_READ_AHEAD }
            return try {
                ContextCompat.startForegroundService(ctx, intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start read-ahead", e)
                ReadAheadState.queue.releaseWorker()
                false
            }
        }

        /** Clears the read-ahead queue and stops the worker (the chapter in progress is cancelled). */
        fun stopReadAhead(ctx: Context) {
            ReadAheadState.queue.clear()
            if (!isAlive) return
            try {
                ctx.startService(Intent(ctx, TranslationForegroundService::class.java).apply { action = ACTION_STOP_READ_AHEAD })
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop read-ahead", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        // Create the channel here — before startForeground() — so the notification is valid on API 26+.
        createNotificationChannel(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    }

    private fun goForeground(notification: Notification) {
        currentNotification = notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun postNotification(notification: Notification) {
        currentNotification = notification
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    /** Stops the service when neither job slot is busy. */
    @Synchronized
    private fun stopIfIdle() {
        if (translationJob?.isActive == true || readAheadJob?.isActive == true) return
        currentNotification = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Called by the main job's finally: free the slot, resume read-ahead or stop. */
    private fun onMainJobFinished(job: Job?) {
        if (translationJob !== job) return
        translationJob = null
        TranslationState.release()
        resumeReadAheadOrStop()
    }

    /** The main slot is free: resume read-ahead if it is waiting for it, else stop when idle. */
    private fun resumeReadAheadOrStop() {
        if (translationJob?.isActive != true && ReadAheadState.queue.isWorkerClaimed && readAheadJob?.isActive != true) {
            startReadAheadJob()
        } else {
            stopIfIdle()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                TranslationState.postCancelled()
                AnalysisState.lastProgress?.let {
                    AnalysisState.post(it.copy(message = getString(R.string.analysis_cancelled), cancelled = true))
                }
                val job = translationJob
                if (job?.isActive == true) {
                    // The job's finally block releases the slot and stops the service.
                    job.cancel()
                } else {
                    TranslationState.release()
                    resumeReadAheadOrStop()
                }
                return START_NOT_STICKY
            }

            ACTION_STOP_READ_AHEAD -> {
                ReadAheadState.queue.clear()
                val job = readAheadJob
                if (job?.isActive == true) job.cancel() else stopIfIdle()
                return START_NOT_STICKY
            }

            ACTION_READ_AHEAD -> {
                // Must call startForeground on every startForegroundService; keep what is shown.
                goForeground(currentNotification ?: buildReadAheadNotification(getString(R.string.read_ahead_title), 0, 0f))
                when {
                    // The main job resumes the worker when it finishes.
                    translationJob?.isActive == true -> Unit
                    readAheadJob?.isActive == true -> Unit
                    else -> startReadAheadJob()
                }
            }

            ACTION_START -> {
                // Must call startForeground before any early return on API 26+.
                // On API 29+ specify the service type to match the manifest declaration.
                goForeground(buildNotification("Starting…", 0, 0))

                if (translationJob?.isActive == true) {
                    // Never replace a running job (its chapter list is in TranslationState).
                    Log.w(TAG, "Start ignored: a translation job is already running")
                    return START_NOT_STICKY
                }

                val novelId = intent.getStringExtra(EXTRA_NOVEL_ID)
                val apiName = intent.getStringExtra(EXTRA_API_NAME)
                val novelName = intent.getStringExtra(EXTRA_NOVEL_NAME)
                val novelAuthor = intent.getStringExtra(EXTRA_NOVEL_AUTHOR)
                val fromIndex = intent.getIntExtra(EXTRA_FROM_INDEX, 0)
                val toIndex = intent.getIntExtra(EXTRA_TO_INDEX, 0)
                val chapters = TranslationState.pendingChapters

                if (novelId == null || apiName == null || novelName == null || chapters == null) {
                    TranslationState.release()
                    resumeReadAheadOrStop()
                    return START_NOT_STICKY
                }

                val apiKey = SecurePrefs.getApiKey(applicationContext)
                if (apiKey.isNullOrBlank()) {
                    finishWithError("No API key set. Go to Settings → Translation.")
                    return START_NOT_STICKY
                }

                startTranslationJob(apiKey, novelId, apiName, novelName, novelAuthor, chapters, fromIndex, toIndex)
            }

            ACTION_ANALYZE -> {
                goForeground(
                    buildNotification(getString(R.string.analysis_fetching, 0, 0), 0, 0, title = getString(R.string.analyzing_novel))
                )
                if (translationJob?.isActive == true) {
                    Log.w(TAG, "Analysis ignored: a job is already running")
                    return START_NOT_STICKY
                }

                val novelId = intent.getStringExtra(EXTRA_NOVEL_ID)
                val apiName = intent.getStringExtra(EXTRA_API_NAME)
                val count = intent.getIntExtra(EXTRA_CHAPTER_COUNT, SecurePrefs.DEFAULT_ANALYZE_CHAPTERS)
                val chapters = TranslationState.pendingChapters
                if (novelId == null || apiName == null || chapters == null) {
                    novelId?.let { AnalysisState.post(AnalysisProgress(it, "", error = getString(R.string.analysis_no_chapters))) }
                    TranslationState.release()
                    resumeReadAheadOrStop()
                    return START_NOT_STICKY
                }
                if (SecurePrefs.analysisEngineConfig(applicationContext).apiKey.isBlank()) {
                    AnalysisState.post(AnalysisProgress(novelId, "", error = getString(R.string.translator_no_api_key)))
                    TranslationState.release()
                    resumeReadAheadOrStop()
                    return START_NOT_STICKY
                }
                startAnalysisJob(novelId, apiName, chapters, count)
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------------------------------
    // Analysis
    // ------------------------------------------------------------------------------------------

    private fun stageMessage(stage: NovelAnalyzer.Stage, current: Int, total: Int): String = when (stage) {
        NovelAnalyzer.Stage.FETCHING -> getString(R.string.analysis_fetching, current, total)
        NovelAnalyzer.Stage.EXTRACTING -> getString(R.string.analysis_extracting, current, total)
    }

    /** Reports an analysis result to the Glossary screen ([AnalysisState]). */
    private fun postAnalysisOutcome(novelId: String, outcome: NovelAnalyzer.Outcome) {
        if (!outcome.succeeded) {
            AnalysisState.post(
                AnalysisProgress(novelId, "", error = getString(R.string.analysis_failed, outcome.error ?: "?"))
            )
            return
        }
        val msg = if (outcome.premiseUpdated) getString(R.string.analysis_done_premise, outcome.termsAdded)
        else getString(R.string.analysis_done, outcome.termsAdded)
        AnalysisState.post(
            AnalysisProgress(
                novelId, msg, completed = true, termsAdded = outcome.termsAdded, premiseUpdated = outcome.premiseUpdated
            )
        )
    }

    /** Listener for an analysis run by this service: Glossary state + [onLine] (notification / progress). */
    private fun analysisListener(novelId: String, onLine: (detail: String, current: Int, total: Int) -> Unit) =
        object : NovelAnalyzer.Listener {
            override fun onProgress(stage: NovelAnalyzer.Stage, current: Int, total: Int) {
                val detail = stageMessage(stage, current, total)
                AnalysisState.post(AnalysisProgress(novelId, detail, current, total))
                onLine(detail, current, total)
            }

            override fun onFinished(outcome: NovelAnalyzer.Outcome) = postAnalysisOutcome(novelId, outcome)
        }

    /** The manual "Analyze novel" (always runs; a re-run refreshes the analysis state). */
    private fun startAnalysisJob(
        novelId: String,
        apiName: String,
        chapters: List<ChapterData>,
        requestedCount: Int
    ) {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val ctx = applicationContext
            val title = getString(R.string.analyzing_novel)
            try {
                val api = Apis.getApiFromNameOrNull(apiName)
                val sourceLang = TranslationLanguages.normalize(api?.api?.lang) ?: TranslationLanguages.DEFAULT
                val count = requestedCount.coerceIn(1, SecurePrefs.MAX_ANALYZE_CHAPTERS).coerceAtMost(chapters.size)
                NovelAnalyzer.analyze(
                    ctx, novelId, sourceLang, count,
                    fetch = { i -> loadChapterSource(api, chapters[i]) },
                    listener = analysisListener(novelId) { detail, current, total ->
                        postNotification(buildNotification(detail, current, total, title = title, plainText = true))
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Analysis job failed", e)
                AnalysisState.post(
                    AnalysisProgress(novelId, "", error = getString(R.string.analysis_failed, e.message ?: e.javaClass.simpleName))
                )
            } finally {
                onMainJobFinished(coroutineContext[Job])
            }
        }
        translationJob = job
        job.start()
    }

    private fun finishWithError(message: String) {
        TranslationState.postProgress(TranslationProgress(0, 0, "", error = message))
        TranslationState.release()
        resumeReadAheadOrStop()
    }

    /** Memoizing chapter loader: chapters fetched for the analysis are reused by the translation. */
    private class SourceCache(
        private val load: suspend (Int) -> String?,
    ) {
        private val cache = HashMap<Int, String>()
        suspend fun get(index: Int): String? = cache[index] ?: load(index)?.also { cache[index] = it }
        fun take(index: Int): String? = cache.remove(index)
        fun clear() = cache.clear()
    }

    // ------------------------------------------------------------------------------------------
    // Batch translation
    // ------------------------------------------------------------------------------------------

    private fun startTranslationJob(
        apiKey: String,
        novelId: String,
        apiName: String,
        novelName: String,
        novelAuthor: String?,
        chapters: List<ChapterData>,
        fromIndex: Int,
        toIndex: Int
    ) {
        TranslationState.setActive(novelId)
        // LAZY so translationJob is assigned before the body (and its finally) can run.
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val ctx = applicationContext
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                val db = TranslatorDatabase.getInstance(ctx)
                val translationDao = db.translationDao()

                val api = Apis.getApiFromNameOrNull(apiName)
                val sourceLang = TranslationLanguages.normalize(api?.api?.lang) ?: TranslationLanguages.DEFAULT
                val config = SecurePrefs.engineConfig(ctx).copy(apiKey = apiKey)
                val model = config.model
                val fallbackConfig = SecurePrefs.fallbackEngineConfig(ctx)
                val engine = TranslationEngine(config, sourceLang, fallback = fallbackConfig)
                if (fallbackConfig == null && SecurePrefs.getFallbackPresetId(ctx) != null) {
                    Log.w(TAG, "Fallback model configured but unusable (no API key for its provider, or same model)")
                }

                // Chapter indices are the translation keys; work on indices directly (no indexOf).
                val alreadyTranslated = translationDao.getTranslatedChapterNums(novelId).toHashSet()
                val lastIndex = minOf(toIndex, chapters.size - 1)
                val toTranslate = (fromIndex.coerceAtLeast(0)..lastIndex).filter { it !in alreadyTranslated }

                var newTermsTotal = 0
                var failed = 0
                var lastError: String? = null
                var deviations = 0
                var fragments = 0
                var paragraphWarnings = 0
                var fallbackChapters = 0

                fun progress(current: Int, name: String) = TranslationProgress(
                    current, toTranslate.size, name,
                    newTermsFound = newTermsTotal,
                    glossaryDeviations = deviations,
                    untranslatedFragments = fragments,
                    paragraphWarnings = paragraphWarnings,
                    fallbackChapters = fallbackChapters,
                    fallbackModel = engine.fallbackModelId,
                )

                val sources = SourceCache { i -> chapters.getOrNull(i)?.let { loadChapterSource(api, it) } }

                // First translation of this novel: build the Story Bible first (once; failures are
                // logged and the translation goes on without it).
                if (toTranslate.isNotEmpty()) {
                    val title = getString(R.string.analyzing_before_translation)
                    NovelAnalyzer.ensureAnalyzed(
                        ctx, novelId, sourceLang, chapters.size,
                        fetch = { i -> sources.get(i) },
                        listener = analysisListener(novelId) { detail, current, total ->
                            TranslationState.postProgress(
                                TranslationProgress(
                                    0, toTranslate.size, getString(R.string.analyzing_novel_progress, current, total),
                                    analyzing = true,
                                )
                            )
                            postNotification(buildNotification(detail, current, total, title = title, plainText = true))
                        },
                    )
                }

                toTranslate.forEachIndexed { idx, chapterIdx ->
                    ensureActive()
                    val chapter = chapters[chapterIdx]

                    TranslationState.postProgress(progress(idx + 1, chapter.name))
                    val issues = progress(idx + 1, chapter.name).takeIf { it.hasQualityIssues }?.qualitySummary(ctx)
                    var lastNotify = 0L
                    fun notifyProgress(fraction: Float) {
                        val now = SystemClock.elapsedRealtime()
                        if (fraction in 0.001f..0.999f && now - lastNotify < MIN_NOTIFY_INTERVAL_MS) return
                        lastNotify = now
                        postNotification(buildNotification(chapter.name, idx + 1, toTranslate.size, fraction, detail = issues))
                    }
                    notifyProgress(0f)

                    try {
                        // Same per-chapter lock as the reader and read-ahead: never translate a chapter twice at once.
                        val result: TranslationResult? = ChapterTranslationRepository.withChapterLock(novelId, chapterIdx) {
                            if (ChapterTranslationRepository.getCached(ctx, novelId, chapterIdx) != null) {
                                sources.take(chapterIdx)
                                return@withChapterLock null // translated meanwhile (reader / read-ahead)
                            }
                            val sourceHtml = sources.take(chapterIdx) ?: loadChapterSource(api, chapter)
                                ?: throw ChapterUnavailableException()

                            // Bible, style and continuity context are reloaded per chapter so terms
                            // auto-added by the previous chapter are used immediately.
                            val inputs = StoryBibleRepository.loadChapterInputs(ctx, novelId, chapterIdx, sourceLang)
                            val result = engine.translateChapter(
                                sourceText = sourceHtml,
                                novelId = novelId,
                                chapterNum = chapterIdx,
                                glossaryTerms = inputs.bible,
                                context = inputs.context,
                                options = inputs.options,
                                onProgress = ::notifyProgress
                            )
                            val outcome = StoryBibleRepository.persistChapter(ctx, novelId, chapterIdx, result, model, sourceLang)
                            newTermsTotal += outcome.pending + outcome.autoAdded
                            result
                        }
                        if (result == null) return@forEachIndexed
                        saveChapterTxt(apiName, novelAuthor, novelName, chapterIdx, chapter, result.englishText)

                        deviations += result.glossaryViolations
                        fragments += result.untranslatedFragments
                        paragraphWarnings += result.paragraphWarnings
                        if (result.usedFallback) {
                            fallbackChapters++
                            Log.i(TAG, "Chapter $chapterIdx: ${result.fallbackChunks} passage(s) re-translated with ${engine.fallbackModelId}")
                        }
                        if (result.violatedTerms.isNotEmpty() || result.leakedFragments.isNotEmpty()) {
                            Log.i(TAG, "Chapter $chapterIdx: deviations=${result.violatedTerms} leaks=${result.leakedFragments} retries=${result.retries}")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ChapterUnavailableException) {
                        failed++
                    } catch (e: TranslationApiException) {
                        Log.e(TAG, "API error translating chapter ${chapter.name}", e)
                        failed++
                        lastError = e.message
                        if (!e.retryable && e.message?.startsWith("Invalid API key") == true) throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error translating chapter ${chapter.name}", e)
                        failed++
                    }
                }
                sources.clear()

                nm.cancel(NOTIFICATION_ID)
                val done = toTranslate.size - failed
                if (toTranslate.isNotEmpty() && done == 0 && lastError != null) {
                    TranslationState.postProgress(TranslationProgress(0, toTranslate.size, "", error = lastError))
                } else {
                    val final = progress(done, "").copy(completed = true)
                    if (toTranslate.isNotEmpty()) postSummaryNotification(final)
                    TranslationState.postProgress(final)
                }
            } catch (e: CancellationException) {
                nm.cancel(NOTIFICATION_ID)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Translation job failed", e)
                nm.cancel(NOTIFICATION_ID)
                TranslationState.postProgress(TranslationProgress(0, 0, "", error = e.message ?: "Translation failed"))
            } finally {
                // Only the current job may free the slot / stop the service.
                onMainJobFinished(coroutineContext[Job])
            }
        }
        translationJob = job
        job.start()
    }

    /** The chapter's source could not be loaded (counted as failed). */
    private class ChapterUnavailableException : Exception()

    // ------------------------------------------------------------------------------------------
    // Read-ahead
    // ------------------------------------------------------------------------------------------

    /**
     * Works through [ReadAheadState.queue] in order. Between chapters it stops when the main slot
     * is busy (the main job restarts it when done), when read-ahead was turned off, or when the
     * queue is empty (which releases the worker claim).
     */
    private fun startReadAheadJob() {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val ctx = applicationContext
            val queue = ReadAheadState.queue
            var yielded = false
            try {
                while (true) {
                    ensureActive()
                    if (TranslationState.isRunning) {
                        Log.i(TAG, "Read-ahead paused: a translation or analysis job is running")
                        yielded = true
                        break
                    }
                    if (SecurePrefs.getReadAheadChapters(ctx) <= 0) queue.clear()
                    val item = queue.poll() ?: break
                    try {
                        translateAhead(item, queue)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TranslationApiException) {
                        Log.e(TAG, "Read-ahead: API error on ${item.novelId} #${item.chapter}", e)
                        if (e.message?.startsWith("Invalid API key") == true) queue.clear()
                    } catch (e: Exception) {
                        Log.e(TAG, "Read-ahead: failed on ${item.novelId} #${item.chapter}", e)
                    } finally {
                        queue.done(item)
                    }
                }
            } finally {
                if (readAheadJob === coroutineContext[Job]) {
                    readAheadJob = null
                    // Paused for a main job that has already finished in the meantime: carry on.
                    if (yielded && !TranslationState.isRunning) resumeReadAheadOrStop() else stopIfIdle()
                }
            }
        }
        readAheadJob = job
        job.start()
    }

    private suspend fun translateAhead(item: ReadAheadQueue.Item, queue: ReadAheadQueue) {
        val ctx = applicationContext
        if (ChapterTranslationRepository.getCached(ctx, item.novelId, item.chapter) != null) return
        val entry = NovelChapterSource.resolve(item.novelId) ?: return
        val chapter = entry.chapters.getOrNull(item.chapter) ?: return
        val api = Apis.getApiFromNameOrNull(entry.apiName)
        val lang = TranslationLanguages.normalize(api?.api?.lang) ?: return
        val config = SecurePrefs.engineConfig(ctx).takeIf { it.apiKey.isNotBlank() } ?: run {
            queue.clear()
            return
        }
        val sources = SourceCache { i -> entry.chapters.getOrNull(i)?.let { loadChapterSource(api, it) } }

        var lastNotify = 0L
        fun notify(text: String, fraction: Float, force: Boolean = false) {
            if (TranslationState.isRunning) return // the main job owns the notification
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastNotify < MIN_NOTIFY_INTERVAL_MS) return
            lastNotify = now
            postNotification(buildReadAheadNotification(text, queue.pendingChapters.size, fraction))
        }

        // Once per novel, before read-ahead starts translating it (no-op when already analyzed).
        NovelAnalyzer.ensureAnalyzed(
            ctx, item.novelId, lang, entry.chapters.size,
            fetch = { i -> sources.get(i) },
            listener = analysisListener(item.novelId) { _, current, total ->
                notify(getString(R.string.analyzing_novel_progress, current, total), 0f, force = true)
            },
        )

        notify(chapter.name, 0f, force = true)
        ChapterTranslationRepository.translateIfMissing(
            ctx, item.novelId, item.chapter, lang, config,
            fetchSource = { sources.take(item.chapter) ?: loadChapterSource(api, chapter) },
            onProgress = { f -> notify(chapter.name, f) },
        )
        sources.clear()
    }

    /** Loads the chapter's source text, solving a Cloudflare challenge once if needed. */
    private suspend fun loadChapterSource(api: APIRepository?, chapter: ChapterData): String? {
        var html = api?.loadHtml(chapter.url)
        if (html.isNullOrBlank()) return null
        if (isCloudflareChallenge(html)) {
            Log.w(TAG, "Cloudflare challenge detected for chapter ${chapter.name} — attempting bypass")
            try {
                // CloudflareKiller solves the challenge via WebView (uses BaseApplication.context, so this
                // works from a service) and stores cf_clearance cookies the provider's app.get() picks up.
                app.get(chapter.url, interceptor = cfKiller)
                html = api?.loadHtml(chapter.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cloudflare bypass failed for chapter ${chapter.name}", e)
            }
            if (html.isNullOrBlank() || isCloudflareChallenge(html)) {
                Log.w(TAG, "Cloudflare bypass unsuccessful — skipping chapter ${chapter.name}")
                return null
            }
        }
        return html
    }

    /** Saves the translation as .txt so it appears in the offline epub. */
    private suspend fun saveChapterTxt(
        apiName: String,
        novelAuthor: String?,
        novelName: String,
        chapterIdx: Int,
        chapter: ChapterData,
        englishHtml: String
    ) {
        try {
            val ctx = applicationContext
            val sApiName = BookDownloader2Helper.sanitizeFilename(apiName)
            val sAuthor = BookDownloader2Helper.sanitizeFilename(novelAuthor ?: "")
            val sName = BookDownloader2Helper.sanitizeFilename(novelName)
            val file = File(ctx.filesDir.toString() + BookDownloader2Helper.getFilename(sApiName, sAuthor, sName, chapterIdx))
            file.parentFile?.mkdirs()
            val translatedChapterName = GoogleTranslate.translate(chapter.name)
            file.writeText("${translatedChapterName}\n${englishHtml}")
            // Reset the stored epub chapter count so the epub is regenerated on next open.
            val epubId = BookDownloader2Helper.generateId(apiName, novelAuthor, novelName)
            ctx.setKey(DOWNLOAD_EPUB_SIZE, epubId.toString(), -1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not save chapter .txt: ${e.message}")
        }
    }

    /**
     * Returns true if the fetched HTML is a Cloudflare challenge page rather than real content.
     */
    private fun isCloudflareChallenge(html: String): Boolean {
        return html.contains("Enable JavaScript and cookies to continue", ignoreCase = true) ||
            html.contains("Just a moment", ignoreCase = true) && html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("cf_chl_opt", ignoreCase = true) ||
            html.contains("ray ID", ignoreCase = true) && html.contains("cloudflare", ignoreCase = true)
    }

    private fun contentIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, launch, flags)
    }

    private fun stopReadAheadIntent(): PendingIntent {
        val intent = Intent(this, TranslationForegroundService::class.java).apply { action = ACTION_STOP_READ_AHEAD }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(this, 1, intent, flags)
    }

    /**
     * @param chapterFraction progress within the current chapter (0..1), from the engine's chunk progress
     */
    private fun buildNotification(
        chapterName: String,
        current: Int,
        total: Int,
        chapterFraction: Float = 0f,
        title: String = getString(R.string.translating_chapters),
        /** Extra line, e.g. the running consistency-check counts. */
        detail: String? = null,
        /** Show [chapterName] as is (no "current/total:" prefix). */
        plainText: Boolean = false,
    ) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.netflix_download)
            .setContentTitle(title)
            .setContentText(
                if (total > 0 && !plainText) {
                    val pct = (chapterFraction.coerceIn(0f, 1f) * 100).toInt()
                    "$current/$total: $chapterName" + if (pct in 1..99) " ($pct%)" else ""
                } else chapterName
            )
            .apply { if (!detail.isNullOrBlank()) setSubText(detail) }
            .setProgress(
                PROGRESS_MAX,
                if (total > 0) (((current - 1) + chapterFraction.coerceIn(0f, 1f)) / total * PROGRESS_MAX).toInt() else 0,
                total == 0
            )
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .build()

    /** Read-ahead progress: the chapter being translated, how many more are queued, and a Stop action. */
    private fun buildReadAheadNotification(text: String, queued: Int, fraction: Float) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.netflix_download)
            .setContentTitle(getString(R.string.read_ahead_title))
            .setContentText(if (queued > 0) getString(R.string.read_ahead_text, text, queued) else text)
            .setProgress(PROGRESS_MAX, (fraction.coerceIn(0f, 1f) * PROGRESS_MAX).toInt(), fraction <= 0f)
            .setContentIntent(contentIntent())
            .addAction(0, getString(R.string.read_ahead_stop), stopReadAheadIntent())
            .setOngoing(true)
            .setSilent(true)
            .build()

    /** Dismissable completion notification: chapters done, consistency-check counts, new terms. */
    private fun postSummaryNotification(p: TranslationProgress) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val lines = buildList {
                add(getString(R.string.translation_finished_text, p.current, p.total))
                add(p.qualitySummary(this@TranslationForegroundService))
                p.fallbackSummary(this@TranslationForegroundService)?.let { add(it) }
                if (p.newTermsFound > 0) {
                    add(resources.getQuantityString(R.plurals.new_terms_to_review, p.newTermsFound, p.newTermsFound))
                }
            }
            val text = lines.joinToString(" · ")
            nm.notify(
                SUMMARY_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.netflix_download)
                    .setContentTitle(getString(R.string.translation_finished_title))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                    .setContentIntent(contentIntent())
                    .setAutoCancel(true)
                    .setSilent(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not post summary notification", e)
        }
    }

    private fun createNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Translation Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlive = false
        if (translationJob?.isActive == true) {
            // Destroyed while running (e.g. by the system) — free the slot and reset the UI.
            // (Normal completion releases in the job's finally; releasing unconditionally here
            // could free a slot already reserved for the next job.)
            TranslationState.postCancelled()
            TranslationState.release()
            AnalysisState.lastProgress?.let {
                AnalysisState.post(it.copy(message = getString(R.string.analysis_cancelled), cancelled = true))
            }
        }
        if (readAheadJob != null) ReadAheadState.queue.releaseWorker()
        translationJob?.cancel()
        readAheadJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Shared in-memory state between [TranslationForegroundService] and [TranslateViewModel]. */
object TranslationState {
    /** Chapters list passed from ViewModel to Service (Intent extras can't hold large lists). */
    @Volatile var pendingChapters: List<ChapterData>? = null
        private set

    /** True from a successful [tryReserve] until the job finishes ([release]). */
    @Volatile var isRunning: Boolean = false
        private set

    /**
     * The most recent progress update. New listeners can read this immediately on registration
     * to restore UI state after the fragment is recreated (e.g. returning from background).
     * Cleared to null once translation completes or errors.
     */
    @Volatile var lastProgress: TranslationProgress? = null
        private set

    /** The novelId currently being translated, so the UI can scope button state per-book. */
    @Volatile var activeNovelId: String? = null
        private set

    private val _listeners = mutableListOf<(TranslationProgress?) -> Unit>()

    fun addListener(l: (TranslationProgress?) -> Unit) {
        synchronized(_listeners) { _listeners.add(l) }
    }

    fun removeListener(l: (TranslationProgress?) -> Unit) {
        synchronized(_listeners) { _listeners.remove(l) }
    }

    /** Atomically claims the single translation slot and stores the chapter list. */
    @Synchronized
    fun tryReserve(chapters: List<ChapterData>): Boolean {
        if (isRunning) return false
        isRunning = true
        pendingChapters = chapters
        return true
    }

    /** Frees the translation slot. */
    @Synchronized
    fun release() {
        isRunning = false
        pendingChapters = null
    }

    fun postProgress(p: TranslationProgress?) {
        val finished = p?.completed == true || p?.error != null
        lastProgress = if (finished) null else p
        if (finished) activeNovelId = null
        synchronized(_listeners) { _listeners.toList() }.forEach { it(p) }
    }

    /** Clears progress after a user cancel; listeners receive null (idle). */
    fun postCancelled() {
        lastProgress = null
        activeNovelId = null
        synchronized(_listeners) { _listeners.toList() }.forEach { it(null) }
    }

    /** Called by the service when it starts a new translation so [activeNovelId] is set early. */
    internal fun setActive(novelId: String) {
        activeNovelId = novelId
    }
}
