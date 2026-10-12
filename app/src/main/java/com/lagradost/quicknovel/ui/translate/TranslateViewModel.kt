package com.lagradost.quicknovel.ui.translate

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.lagradost.quicknovel.R
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.util.AppUtils
import kotlinx.coroutines.launch

data class TranslationProgress(
    val current: Int,
    val total: Int,
    val currentChapterName: String,
    val completed: Boolean = false,
    val error: String? = null,
    /** Pending + auto-added terms found so far (both are worth reviewing in the Glossary). */
    val newTermsFound: Int = 0,
    /** Story Bible terms missing from the output, summed over the chapters so far. */
    val glossaryDeviations: Int = 0,
    /** Untranslated CJK fragments left in the output. */
    val untranslatedFragments: Int = 0,
    /** Chunks with fewer than 70% of the source paragraphs. */
    val paragraphWarnings: Int = 0,
    /** Chapters in which some passages were re-translated by the fallback model. */
    val fallbackChapters: Int = 0,
    /** Fallback model id (null when no fallback is configured). */
    val fallbackModel: String? = null,
) {
    val hasQualityIssues: Boolean
        get() = glossaryDeviations > 0 || untranslatedFragments > 0 || paragraphWarnings > 0

    /** "3 glossary deviations, 1 untranslated fragment" (or "no consistency issues"). */
    fun qualitySummary(ctx: Context): String = qualitySummary(ctx, glossaryDeviations, untranslatedFragments, paragraphWarnings)

    /** "2 chapters used the fallback model (gemini-3.1-flash-lite)", or null if none did. */
    fun fallbackSummary(ctx: Context): String? = fallbackChapters.takeIf { it > 0 }?.let {
        ctx.resources.getQuantityString(R.plurals.chapters_used_fallback, it, it, fallbackModel ?: "?")
    }

    companion object {
        fun qualitySummary(ctx: Context, deviations: Int, fragments: Int, paragraphWarnings: Int): String {
            val res = ctx.resources
            val parts = buildList {
                if (deviations > 0) add(res.getQuantityString(R.plurals.glossary_deviations, deviations, deviations))
                if (fragments > 0) add(res.getQuantityString(R.plurals.untranslated_fragments, fragments, fragments))
                if (paragraphWarnings > 0) add(res.getQuantityString(R.plurals.incomplete_chunks, paragraphWarnings, paragraphWarnings))
            }
            return if (parts.isEmpty()) ctx.getString(R.string.translation_no_issues) else parts.joinToString(", ")
        }
    }
}

class TranslateViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TranslatorDatabase.getInstance(application)
    private val translationDao = db.translationDao()

    val progress: MutableLiveData<TranslationProgress?> = MutableLiveData(null)
    val translatedChapterNums: MutableLiveData<List<Int>> = MutableLiveData(emptyList())

    private var isRunning = false
    var currentNovelId: String? = null
        private set

    private val progressListener: (TranslationProgress?) -> Unit = { p ->
        progress.postValue(p)
        if (p?.completed == true || p?.error != null) {
            isRunning = false
            currentNovelId?.let { id -> loadTranslatedNums(id) }
        }
    }

    init {
        TranslationState.addListener(progressListener)
        // If a translation was already running when this ViewModel was created (e.g. the
        // fragment was recreated after going to background), restore the in-progress state
        // immediately so the UI reflects the correct button state without waiting for the
        // next progress broadcast from the service.
        TranslationState.lastProgress?.let { current ->
            isRunning = true
            currentNovelId = TranslationState.activeNovelId
            progress.value = current
        }
    }

    fun loadTranslatedNums(novelId: String) {
        viewModelScope.launch {
            translatedChapterNums.postValue(
                translationDao.getTranslatedChapterNums(novelId)
            )
        }
    }

    fun startTranslation(
        novelId: String,
        apiName: String,
        chapters: List<ChapterData>,
        fromIndex: Int,
        toIndex: Int,
        novelAuthor: String? = null,
        novelName: String? = null
    ) {
        val ctx = getApplication<Application>()
        if (isRunning || AppUtils.isServiceRunning(ctx, TranslationForegroundService::class.java)) return
        isRunning = true
        currentNovelId = novelId

        // Immediate feedback so the UI reacts before the service starts.
        progress.postValue(TranslationProgress(0, toIndex - fromIndex + 1, "Starting…"))

        val started = TranslationForegroundService.startTranslation(
            ctx = getApplication(),
            novelId = novelId,
            apiName = apiName,
            novelName = novelName ?: "",
            novelAuthor = novelAuthor,
            chapters = chapters,
            fromIndex = fromIndex,
            toIndex = toIndex
        )
        if (!started) {
            isRunning = false
            // The service posts its own error if it failed to start; this covers "already running".
            if (TranslationState.isRunning) {
                progress.postValue(TranslationProgress(0, 0, "", error = "Another translation is already running"))
            }
        }
    }

    fun cancelTranslation() {
        if (isRunning) {
            TranslationForegroundService.cancelTranslation(getApplication())
            isRunning = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        TranslationState.removeListener(progressListener)
        // Do NOT cancel the service here — it should keep running in the background.
    }
}
