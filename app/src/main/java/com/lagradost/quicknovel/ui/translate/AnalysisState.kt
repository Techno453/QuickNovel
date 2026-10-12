package com.lagradost.quicknovel.ui.translate

/** Progress of the "Analyze novel" pre-pass run by [TranslationForegroundService]. */
data class AnalysisProgress(
    val novelId: String,
    val message: String,
    val current: Int = 0,
    val total: Int = 0,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null,
    val termsAdded: Int = 0,
    val premiseUpdated: Boolean = false,
) {
    val finished: Boolean get() = completed || cancelled || error != null
}

/**
 * Shared state between the service's analysis job and the Glossary screen. Kept separate from
 * [TranslationState] so screens observing chapter translation (e.g. the result page) do not
 * react to analysis jobs; both job kinds share the service's single job slot.
 */
object AnalysisState {
    /** Latest progress while running (null when idle / finished). */
    @Volatile
    var lastProgress: AnalysisProgress? = null
        private set

    private val listeners = mutableListOf<(AnalysisProgress) -> Unit>()

    fun addListener(l: (AnalysisProgress) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: (AnalysisProgress) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    fun isRunningFor(novelId: String): Boolean = lastProgress?.novelId == novelId

    val isRunning: Boolean get() = lastProgress != null

    fun post(p: AnalysisProgress) {
        lastProgress = if (p.finished) null else p
        synchronized(listeners) { listeners.toList() }.forEach { it(p) }
    }
}
