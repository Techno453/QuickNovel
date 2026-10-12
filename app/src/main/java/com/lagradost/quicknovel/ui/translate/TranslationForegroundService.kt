package com.lagradost.quicknovel.ui.translate

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
import com.lagradost.quicknovel.translator.BibleMerger
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.StoryBibleRepository
import com.lagradost.quicknovel.translator.TermAliases
import com.lagradost.quicknovel.translator.TranslationApiException
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.translator.CostEstimator
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
 * Foreground service that runs the chapter translation job.
 * Survives the app going to the background so long work isn't interrupted.
 *
 * Communicates results back to [TranslateViewModel] via the shared [TranslationState] object.
 */
class TranslationForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var translationJob: Job? = null

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
        const val EXTRA_CHAPTER_COUNT = "chapterCount"

        /**
         * Starts the "Analyze novel" pre-pass over the first [chapterCount] chapters. Shares the
         * single job slot with translation; returns false if a job is already running.
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
    }

    override fun onCreate() {
        super.onCreate()
        // Create the channel here — before startForeground() — so the notification is valid on API 26+.
        createNotificationChannel(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
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
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                // Must call startForeground before any early return on API 26+.
                // On API 29+ specify the service type to match the manifest declaration.
                val startingNotification = buildNotification("Starting…", 0, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, startingNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, startingNotification)
                }

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
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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
                val startingNotification = buildNotification(getString(R.string.analysis_fetching, 0, 0), 0, 0,
                    title = getString(R.string.analyzing_novel))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, startingNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, startingNotification)
                }
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
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val apiKey = SecurePrefs.getApiKey(applicationContext)
                if (apiKey.isNullOrBlank()) {
                    AnalysisState.post(AnalysisProgress(novelId, "", error = getString(R.string.translator_no_api_key)))
                    TranslationState.release()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAnalysisJob(apiKey, novelId, apiName, chapters, count)
            }
        }
        return START_NOT_STICKY
    }

    /** Text of one chapter for the analysis job (plain paragraphs). */
    private data class AnalysisChapter(val index: Int, val text: String)

    /** One analysis request: consecutive chapters up to ~8k characters. */
    private data class AnalysisSegment(val firstIndex: Int, val lastIndex: Int, val text: String)

    private fun segmentChapters(chapters: List<AnalysisChapter>): List<AnalysisSegment> {
        val limit = CostEstimator.ANALYSIS_CHARS_PER_CALL
        val out = mutableListOf<AnalysisSegment>()
        var first = -1
        var last = -1
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) out += AnalysisSegment(first, last, sb.toString().trim())
            sb.clear(); first = -1
        }
        for (ch in chapters) {
            val pieces = TranslationEngine.splitIntoChunks(ch.text, limit)
            for (piece in pieces) {
                if (sb.isNotEmpty() && sb.length + piece.length + 2 > limit) flush()
                if (first < 0) first = ch.index
                last = ch.index
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(piece)
            }
        }
        flush()
        return out
    }

    private fun startAnalysisJob(
        apiKey: String,
        novelId: String,
        apiName: String,
        chapters: List<ChapterData>,
        requestedCount: Int
    ) {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val ctx = applicationContext
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val title = getString(R.string.analyzing_novel)
            fun report(message: String, current: Int, total: Int) {
                AnalysisState.post(AnalysisProgress(novelId, message, current, total))
                nm.notify(NOTIFICATION_ID, buildNotification(message, current, total, title = title, plainText = true))
            }
            try {
                val api = Apis.getApiFromNameOrNull(apiName)
                val sourceLang = TranslationLanguages.normalize(api?.api?.lang) ?: TranslationLanguages.DEFAULT
                val engine = TranslationEngine(
                    apiKey = apiKey,
                    model = SecurePrefs.getModel(ctx),
                    baseUrl = SecurePrefs.getBaseUrl(ctx),
                    sourceLang = sourceLang
                )
                val count = requestedCount.coerceIn(1, SecurePrefs.MAX_ANALYZE_CHAPTERS).coerceAtMost(chapters.size)

                val texts = mutableListOf<AnalysisChapter>()
                for (i in 0 until count) {
                    ensureActive()
                    report(getString(R.string.analysis_fetching, i + 1, count), i + 1, count)
                    val html = try {
                        loadChapterSource(api, chapters[i])
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Analysis: could not load chapter $i", e)
                        null
                    } ?: continue
                    TranslationEngine.htmlToPlainText(html).takeIf { it.isNotBlank() }?.let { texts += AnalysisChapter(i, it) }
                }
                if (texts.isEmpty()) {
                    AnalysisState.post(AnalysisProgress(novelId, "", error = getString(R.string.analysis_no_chapters)))
                    return@launch
                }

                val db = TranslatorDatabase.getInstance(ctx)
                val style = StoryBibleRepository.loadStyle(ctx, novelId, sourceLang)
                val known = db.glossaryDao().getAllTermsSync(novelId).flatMap { TermAliases.formsOf(it) }.toMutableSet()
                var premise = db.novelContextDao().get(novelId)?.premise.orEmpty()
                val originalPremise = premise
                var added = 0
                var lastError: String? = null

                val segments = segmentChapters(texts)
                segments.forEachIndexed { s, segment ->
                    ensureActive()
                    report(getString(R.string.analysis_extracting, s + 1, segments.size), s + 1, segments.size)
                    val label = if (segment.firstIndex == segment.lastIndex) "chapter ${segment.firstIndex + 1}"
                    else "chapters ${segment.firstIndex + 1}-${segment.lastIndex + 1}"
                    val extraction = try {
                        engine.extractStoryBible(segment.text, known, premise, style, label)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TranslationApiException) {
                        Log.e(TAG, "Analysis request failed", e)
                        lastError = e.message
                        if (e.message?.startsWith("Invalid API key") == true) throw e
                        return@forEachIndexed
                    }
                    if (extraction.premise.isNotBlank()) premise = extraction.premise.trim()

                    val newTerms = mutableListOf<GlossaryTerm>()
                    for (d in extraction.terms.sortedByDescending { it.confidence }) {
                        val source = d.source.trim()
                        val english = d.english.trim()
                        if (source.isEmpty() || english.isEmpty() || source in known) continue
                        if (!BibleMerger.containsCjk(source) || BibleMerger.containsCjk(english)) continue
                        if (!segment.text.contains(source)) continue // not actually in the excerpt
                        val firstChapter = texts.firstOrNull { it.text.contains(source) }?.index ?: segment.firstIndex
                        val aliases = d.aliases.map { it.trim() }.filter { it.isNotEmpty() && it != source && it !in known }
                        newTerms += GlossaryTerm(
                            novelId = novelId,
                            source = source,
                            english = english,
                            category = GlossaryTerm.normalizeCategory(d.category),
                            gender = GlossaryTerm.normalizeGender(d.gender),
                            notes = d.notes.trim(),
                            aliases = TermAliases.encode(aliases),
                            firstChapter = firstChapter,
                            autoAdded = true,
                            locked = false,
                        )
                        known += source
                        known += aliases
                    }
                    added += StoryBibleRepository.addAutoTerms(ctx, novelId, newTerms)
                }

                val premiseUpdated = premise.isNotBlank() && premise != originalPremise
                if (premiseUpdated) {
                    StoryBibleRepository.updateContext(ctx, novelId) { it.copy(premise = premise) }
                }
                nm.cancel(NOTIFICATION_ID)
                if (added == 0 && !premiseUpdated && lastError != null) {
                    AnalysisState.post(AnalysisProgress(novelId, "", error = getString(R.string.analysis_failed, lastError)))
                } else {
                    val msg = if (premiseUpdated) getString(R.string.analysis_done_premise, added)
                    else getString(R.string.analysis_done, added)
                    AnalysisState.post(
                        AnalysisProgress(novelId, msg, completed = true, termsAdded = added, premiseUpdated = premiseUpdated)
                    )
                }
            } catch (e: CancellationException) {
                nm.cancel(NOTIFICATION_ID)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Analysis job failed", e)
                nm.cancel(NOTIFICATION_ID)
                AnalysisState.post(
                    AnalysisProgress(novelId, "", error = getString(R.string.analysis_failed, e.message ?: e.javaClass.simpleName))
                )
            } finally {
                if (translationJob === coroutineContext[Job]) {
                    TranslationState.release()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        translationJob = job
        job.start()
    }

    private fun finishWithError(message: String) {
        TranslationState.postProgress(TranslationProgress(0, 0, "", error = message))
        TranslationState.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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
                val model = SecurePrefs.getModel(ctx)
                val engine = TranslationEngine(
                    apiKey = apiKey,
                    model = model,
                    baseUrl = SecurePrefs.getBaseUrl(ctx),
                    sourceLang = sourceLang
                )

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

                fun progress(current: Int, name: String) = TranslationProgress(
                    current, toTranslate.size, name,
                    newTermsFound = newTermsTotal,
                    glossaryDeviations = deviations,
                    untranslatedFragments = fragments,
                    paragraphWarnings = paragraphWarnings,
                )

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
                        nm.notify(
                            NOTIFICATION_ID,
                            buildNotification(chapter.name, idx + 1, toTranslate.size, fraction, detail = issues)
                        )
                    }
                    notifyProgress(0f)

                    try {
                        val sourceHtml = loadChapterSource(api, chapter) ?: run {
                            failed++
                            return@forEachIndexed
                        }

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
                        saveChapterTxt(apiName, novelAuthor, novelName, chapterIdx, chapter, result.englishText)

                        newTermsTotal += outcome.pending + outcome.autoAdded
                        deviations += result.glossaryViolations
                        fragments += result.untranslatedFragments
                        paragraphWarnings += result.paragraphWarnings
                        if (result.violatedTerms.isNotEmpty() || result.leakedFragments.isNotEmpty()) {
                            Log.i(TAG, "Chapter $chapterIdx: deviations=${result.violatedTerms} leaks=${result.leakedFragments} retries=${result.retries}")
                        }
                    } catch (e: CancellationException) {
                        throw e
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
                if (translationJob === coroutineContext[Job]) {
                    TranslationState.release()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        translationJob = job
        job.start()
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

    /** Dismissable completion notification: chapters done, consistency-check counts, new terms. */
    private fun postSummaryNotification(p: TranslationProgress) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val lines = buildList {
                add(getString(R.string.translation_finished_text, p.current, p.total))
                add(p.qualitySummary(this@TranslationForegroundService))
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
        translationJob?.cancel()
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
