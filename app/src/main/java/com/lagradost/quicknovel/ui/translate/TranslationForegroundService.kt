package com.lagradost.quicknovel.ui.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.DOWNLOAD_EPUB_SIZE
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.network.CloudflareKiller
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.db.TranslatedChapter
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.GoogleTranslate
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the chapter translation job.
 * Survives the app going to the background so long work isn't interrupted.
 *
 * Communicates results back to [TranslateViewModel] via the shared [TranslationState] object.
 */
class TranslationForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var translationJob: Job? = null

    // Shared across all chapters in this session — once CF cookies are obtained for a host,
    // subsequent chapters on the same host reuse them without re-launching the WebView.
    private val cfKiller = CloudflareKiller()

    companion object {
        private const val CHANNEL_ID = "translation_progress"
        private const val NOTIFICATION_ID = 9001

        const val ACTION_START = "start_translation"
        const val ACTION_CANCEL = "cancel_translation"

        const val EXTRA_NOVEL_ID = "novelId"
        const val EXTRA_API_NAME = "apiName"
        const val EXTRA_NOVEL_NAME = "novelName"
        const val EXTRA_NOVEL_AUTHOR = "novelAuthor"
        const val EXTRA_FROM_INDEX = "fromIndex"
        const val EXTRA_TO_INDEX = "toIndex"

        fun startTranslation(
            ctx: Context,
            novelId: String,
            apiName: String,
            novelName: String,
            novelAuthor: String?,
            chapters: List<ChapterData>,
            fromIndex: Int,
            toIndex: Int
        ) {
            TranslationState.pendingChapters = chapters
            val intent = Intent(ctx, TranslationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NOVEL_ID, novelId)
                putExtra(EXTRA_API_NAME, apiName)
                putExtra(EXTRA_NOVEL_NAME, novelName)
                putExtra(EXTRA_NOVEL_AUTHOR, novelAuthor)
                putExtra(EXTRA_FROM_INDEX, fromIndex)
                putExtra(EXTRA_TO_INDEX, toIndex)
            }
            ContextCompat.startForegroundService(ctx, intent)
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
        // Create the channel here — before startForeground() — so the notification is valid
        // on API 26+. Calling it again later in startTranslationJob() is harmless (idempotent).
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(nm)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                translationJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val novelId = intent.getStringExtra(EXTRA_NOVEL_ID)
                val apiName = intent.getStringExtra(EXTRA_API_NAME)
                val novelName = intent.getStringExtra(EXTRA_NOVEL_NAME)
                val novelAuthor = intent.getStringExtra(EXTRA_NOVEL_AUTHOR)
                val fromIndex = intent.getIntExtra(EXTRA_FROM_INDEX, 0)
                val toIndex = intent.getIntExtra(EXTRA_TO_INDEX, 0)
                val chapters = TranslationState.pendingChapters

                // Must call startForeground before any early return on API 26+.
                // On API 29+ specify the service type to match the manifest declaration.
                val startingNotification = buildNotification("Starting…", 0, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, startingNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, startingNotification)
                }

                if (novelId == null || apiName == null || novelName == null || chapters == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startTranslationJob(novelId, apiName, novelName, novelAuthor, chapters, fromIndex, toIndex)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTranslationJob(
        novelId: String,
        apiName: String,
        novelName: String,
        novelAuthor: String?,
        chapters: List<ChapterData>,
        fromIndex: Int,
        toIndex: Int
    ) {
        translationJob?.cancel()
        TranslationState.setActive(novelId)
        translationJob = serviceScope.launch {
            val ctx = applicationContext
            val apiKey = SecurePrefs.getApiKey(ctx)
            if (apiKey.isNullOrBlank()) {
                TranslationState.postProgress(
                    TranslationProgress(0, 0, "", error = "No API key set. Go to Settings → Translation.")
                )
                stopSelf()
                return@launch
            }

            val db = TranslatorDatabase.getInstance(ctx)
            val translationDao = db.translationDao()
            val glossaryDao = db.glossaryDao()
            val pendingTermDao = db.pendingTermDao()

            val model = SecurePrefs.getModel(ctx)
            val engine = TranslationEngine(apiKey, model)
            val api = Apis.getApiFromNameOrNull(apiName)

            val alreadyTranslated = translationDao.getTranslatedChapterNums(novelId).toSet()
            val range = chapters.subList(fromIndex, minOf(toIndex + 1, chapters.size))
            val toTranslate = range.filter { chapters.indexOf(it) !in alreadyTranslated }

            if (toTranslate.isEmpty()) {
                TranslationState.postProgress(
                    TranslationProgress(0, 0, "", completed = true, newTermsFound = 0)
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel(nm)

            var newTermsTotal = 0
            toTranslate.forEachIndexed { idx, chapter ->
                if (!isActive) return@forEachIndexed

                val chapterIdx = chapters.indexOf(chapter)
                val progress = TranslationProgress(idx + 1, toTranslate.size, chapter.name)
                TranslationState.postProgress(progress)
                nm.notify(NOTIFICATION_ID, buildNotification(chapter.name, idx + 1, toTranslate.size))

                try {
                    var chineseHtml = api?.loadHtml(chapter.url)
                    if (chineseHtml.isNullOrBlank()) return@forEachIndexed
                    if (isCloudflareChallenge(chineseHtml)) {
                        android.util.Log.w("TranslationService", "Cloudflare challenge detected for chapter ${chapter.name} — attempting bypass")
                        try {
                            // Use CloudflareKiller to solve the challenge via WebView.
                            // WebViewResolver uses BaseApplication.context so this works from a service.
                            // The bypass populates cf_clearance cookies in the system CookieManager,
                            // which the provider's own app.get() will then pick up on retry.
                            app.get(chapter.url, interceptor = cfKiller)
                            chineseHtml = api?.loadHtml(chapter.url)
                        } catch (e: Exception) {
                            android.util.Log.e("TranslationService", "Cloudflare bypass failed for chapter ${chapter.name}", e)
                        }
                        if (chineseHtml.isNullOrBlank() || isCloudflareChallenge(chineseHtml)) {
                            android.util.Log.w("TranslationService", "Cloudflare bypass unsuccessful — skipping chapter ${chapter.name}")
                            return@forEachIndexed
                        }
                        android.util.Log.i("TranslationService", "Cloudflare bypass succeeded for chapter ${chapter.name}")
                    }

                    val glossaryTerms = glossaryDao.getAllTermsSync(novelId)
                    val result = engine.translateChapter(
                        chineseText = chineseHtml,
                        novelId = novelId,
                        chapterNum = chapterIdx,
                        glossaryTerms = glossaryTerms,
                        onProgress = {}
                    )

                    translationDao.insert(
                        TranslatedChapter(
                            novelId = novelId,
                            chapterNum = chapterIdx,
                            englishText = result.englishText,
                            model = model
                        )
                    )

                    // Save as .txt so it appears in the offline epub
                    try {
                        val sApiName = BookDownloader2Helper.sanitizeFilename(apiName)
                        val sAuthor = BookDownloader2Helper.sanitizeFilename(novelAuthor ?: "")
                        val sName = BookDownloader2Helper.sanitizeFilename(novelName)
                        val filepath = ctx.filesDir.toString() +
                            BookDownloader2Helper.getFilename(sApiName, sAuthor, sName, chapterIdx)
                        val file = File(filepath)
                        file.parentFile?.mkdirs()
                        val translatedChapterName = try {
                            GoogleTranslate.translate(chapter.name)
                        } catch (e: Exception) {
                            chapter.name
                        }
                        file.writeText("${translatedChapterName}\n${result.englishText}")
                        // Reset the stored epub chapter count so the epub is regenerated
                        // on the next open to include this newly translated chapter.
                        val epubId = BookDownloader2Helper.generateId(apiName, novelAuthor, novelName)
                        ctx.setKey(DOWNLOAD_EPUB_SIZE, epubId.toString(), -1)
                    } catch (e: Exception) {
                        android.util.Log.w("TranslationService", "Could not save chapter .txt: ${e.message}")
                    }

                    if (result.pendingTerms.isNotEmpty()) {
                        pendingTermDao.insertAll(result.pendingTerms)
                        newTermsTotal += result.pendingTerms.size
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("TranslationService", "Error translating chapter ${chapter.name}", e)
                }
            }

            nm.cancel(NOTIFICATION_ID)
            TranslationState.postProgress(
                TranslationProgress(toTranslate.size, toTranslate.size, "", completed = true, newTermsFound = newTermsTotal)
            )
            TranslationState.pendingChapters = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Returns true if the fetched HTML is a Cloudflare challenge page rather than real content.
     * These pages should be skipped rather than sent to the translation engine.
     */
    private fun isCloudflareChallenge(html: String): Boolean {
        // Common strings present in Cloudflare's JS/browser challenge pages
        return html.contains("Enable JavaScript and cookies to continue", ignoreCase = true) ||
            html.contains("Just a moment", ignoreCase = true) && html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("cf_chl_opt", ignoreCase = true) ||
            html.contains("ray ID", ignoreCase = true) && html.contains("cloudflare", ignoreCase = true)
    }

    private fun buildNotification(chapterName: String, current: Int, total: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.netflix_download)
            .setContentTitle(getString(R.string.translating_chapters))
            .setContentText(if (total > 0) "$current/$total: $chapterName" else chapterName)
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

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
        translationJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Shared in-memory state between [TranslationForegroundService] and [TranslateViewModel]. */
object TranslationState {
    /** Chapters list passed from ViewModel to Service (Intent extras can't hold large lists). */
    @Volatile var pendingChapters: List<ChapterData>? = null

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

    fun postProgress(p: TranslationProgress?) {
        val finished = p?.completed == true || p?.error != null
        lastProgress = if (finished) null else p
        if (finished) activeNovelId = null
        synchronized(_listeners) { _listeners.toList() }.forEach { it(p) }
    }

    /** Called by the service when it starts a new translation so [activeNovelId] is set early. */
    internal fun setActive(novelId: String) {
        activeNovelId = novelId
    }
}
