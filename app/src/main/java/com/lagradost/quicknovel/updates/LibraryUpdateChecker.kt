package com.lagradost.quicknovel.updates

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE_PDF
import com.lagradost.quicknovel.BookDownloader2Helper.generateId
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.DOWNLOAD_TOTAL
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.getKeys
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration.Companion.milliseconds

/** Progress of a running check, for the library's progress indicator. */
data class UpdateCheckProgress(val checked: Int, val total: Int, val manual: Boolean)

data class UpdateCheckResult(
    val checked: Int,
    val found: List<FoundNovel>,
    val failed: Int,
    val skipped: Int,
    /** The time cap was hit; the novels not reached go first next time. */
    val timedOut: Boolean,
)

/**
 * Loads the chapter list of every novel in the selected library categories and records new
 * chapters. One run at a time per process; requests to the same site are at least
 * [SITE_GAP_MS] apart; the whole run is capped at [MAX_RUNTIME_MS] (WorkManager stops
 * non-foreground workers after 10 minutes).
 */
object LibraryUpdateChecker {
    private const val TAG = "LibraryUpdateChecker"
    const val SITE_GAP_MS = 1_500L
    const val MAX_RUNTIME_MS = 8 * 60_000L

    private val runMutex = Mutex()
    private val _progress = MutableStateFlow<UpdateCheckProgress?>(null)
    val progress: StateFlow<UpdateCheckProgress?> = _progress.asStateFlow()

    val isRunning: Boolean get() = runMutex.isLocked

    /** The novels of the selected categories, with what the library already knows about them. */
    fun collectCandidates(context: Context, categories: Set<LibraryCategory>): List<CheckCandidate> {
        val out = ArrayList<CheckCandidate>()

        for (key in context.getKeys("$RESULT_BOOKMARK_STATE/")) {
            val category = LibraryCategory.fromBookmarkState(context.getKey<Int>(key)) ?: continue
            if (category !in categories) continue
            val idString = key.substringAfter('/')
            val cached = context.getKey<ResultCached>(RESULT_BOOKMARK, idString) ?: continue
            if (cached.isImported) continue
            out += CheckCandidate(
                id = cached.id,
                apiName = cached.apiName,
                url = cached.source,
                name = cached.name,
                baselineHint = cached.totalChapters.takeIf { it > 0 },
                lastCheckedAt = LibraryUpdateStore.get(context, cached.id)?.lastCheckedAt,
            )
        }

        if (LibraryCategory.Downloads in categories) {
            for (key in context.getKeys("$DOWNLOAD_FOLDER/")) {
                val data = context.getKey<DownloadFragment.DownloadData>(key) ?: continue
                if (data.apiName == IMPORT_SOURCE || data.apiName == IMPORT_SOURCE_PDF) continue
                val id = generateId(data.apiName, data.author, data.name)
                out += CheckCandidate(
                    id = id,
                    apiName = data.apiName,
                    url = data.source,
                    name = data.name,
                    baselineHint = context.getKey<Int>(DOWNLOAD_TOTAL, id.toString())
                        ?.takeIf { it > 0 },
                    lastCheckedAt = LibraryUpdateStore.get(context, id)?.lastCheckedAt,
                )
            }
        }
        return out
    }

    /** Returns null when another run is already in progress. */
    suspend fun run(context: Context, manual: Boolean): UpdateCheckResult? {
        if (!runMutex.tryLock()) return null
        try {
            return runLocked(context.applicationContext, manual)
        } finally {
            _progress.value = null
            runMutex.unlock()
        }
    }

    private suspend fun runLocked(context: Context, manual: Boolean): UpdateCheckResult {
        val startedAt = System.currentTimeMillis()
        val categories = LibraryUpdateSettings.categories(context)
        val plan = CheckPlanner.order(collectCandidates(context, categories))
        val throttle = SiteThrottle(SITE_GAP_MS)
        val health = SiteHealth()
        val found = ArrayList<FoundNovel>()
        var checked = 0
        var failed = 0
        var skipped = 0
        var timedOut = false

        _progress.value = UpdateCheckProgress(0, plan.size, manual)
        for ((index, candidate) in plan.withIndex()) {
            if (System.currentTimeMillis() - startedAt > MAX_RUNTIME_MS) {
                timedOut = true
                break
            }
            val stored = LibraryUpdateStore.get(context, candidate.id)
                ?: NovelUpdateState(candidate.id)
            val base = stored.copy(
                id = candidate.id,
                apiName = candidate.apiName,
                url = candidate.url,
                name = candidate.name
            )

            if (health.isSkipped(candidate.apiName)) {
                skipped++
                LibraryUpdateStore.put(
                    context,
                    ChapterUpdateDetector.onFailure(
                        base, health.skipReason(candidate.apiName), System.currentTimeMillis()
                    )
                )
            } else {
                val wait = throttle.waitMs(candidate.apiName, System.currentTimeMillis())
                if (wait > 0) delay(wait.milliseconds)
                throttle.record(candidate.apiName, System.currentTimeMillis())

                when (val result = checkOne(candidate, base, health)) {
                    is NovelCheck.Failed -> {
                        failed++
                        LibraryUpdateStore.put(context, result.state)
                    }

                    is NovelCheck.Checked -> {
                        val outcome = result.outcome
                        checked++
                        LibraryUpdateStore.put(context, outcome.state)
                        if (outcome.added > 0) {
                            found += FoundNovel(
                                id = candidate.id,
                                name = candidate.name,
                                apiName = candidate.apiName,
                                url = candidate.url,
                                added = outcome.added,
                                newChapters = outcome.state.newChapters,
                            )
                            LibraryUpdateStore.changed(candidate.id)
                        }
                        updateBookmarkTotal(context, candidate.id, outcome.state)
                    }
                }
            }
            _progress.value = UpdateCheckProgress(index + 1, plan.size, manual)
        }

        Log.i(
            TAG,
            "Checked $checked/${plan.size} novels in ${System.currentTimeMillis() - startedAt} ms, " +
                    "${found.size} with new chapters, $failed failed, $skipped skipped" +
                    if (timedOut) ", stopped at the time cap" else ""
        )
        if (found.isNotEmpty()) {
            LibraryUpdateNotifier.notifyNewChapters(context, found)
        }
        return UpdateCheckResult(checked, found, failed, skipped, timedOut)
    }

    private sealed class NovelCheck {
        data class Checked(val outcome: CheckOutcome) : NovelCheck()

        /** The novel's state with the error noted. */
        data class Failed(val state: NovelUpdateState) : NovelCheck()
    }

    /** Loads one novel; failures are noted on the state, never thrown (except cancellation). */
    private suspend fun checkOne(
        candidate: CheckCandidate,
        base: NovelUpdateState,
        health: SiteHealth,
    ): NovelCheck {
        fun failed(message: String) = NovelCheck.Failed(
            ChapterUpdateDetector.onFailure(base, message, System.currentTimeMillis())
        )
        try {
            val api = Apis.getApiFromNameOrNull(candidate.apiName)
                ?: return failed("Source ${candidate.apiName} is no longer available")
            return when (val response = api.load(candidate.url, allowCache = false)) {
                is Resource.Success -> {
                    val loaded = response.value as? StreamResponse
                        ?: return failed("This source has no chapter list to check")
                    health.recordSuccess(candidate.apiName)
                    val outcome = ChapterUpdateDetector.onSuccess(
                        stored = base,
                        fresh = loaded.data.map { ChapterRef(it.name, it.url) },
                        now = System.currentTimeMillis(),
                        baselineHint = candidate.baselineHint,
                    )
                    if (outcome.kind == ChangeKind.EmptyList) {
                        NovelCheck.Failed(outcome.state)
                    } else {
                        NovelCheck.Checked(outcome)
                    }
                }

                is Resource.Failure -> {
                    health.recordFailure(
                        candidate.apiName,
                        FailureClassifier.isSiteLevel(response.cause, response.errorString),
                        response.errorString
                    )
                    failed(response.errorString)
                }

                is Resource.Loading -> failed("No response")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logError(t)
            val message = t.message ?: t.javaClass.simpleName
            health.recordFailure(
                candidate.apiName, FailureClassifier.isSiteLevel(t, message), message
            )
            return failed(message)
        }
    }

    /** Keeps the bookmark's "read/total chapters" in the library in step with the site. */
    private fun updateBookmarkTotal(context: Context, id: Int, state: NovelUpdateState) {
        val total = state.lastKnownChapterCount ?: return
        try {
            val cached = context.getKey<ResultCached>(RESULT_BOOKMARK, id.toString()) ?: return
            if (cached.totalChapters == total) return
            context.setKey(RESULT_BOOKMARK, id.toString(), cached.copy(totalChapters = total))
            BookDownloader2.bookmarkChanged(id)
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
