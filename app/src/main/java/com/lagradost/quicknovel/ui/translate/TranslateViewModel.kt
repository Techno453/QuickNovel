package com.lagradost.quicknovel.ui.translate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
    val newTermsFound: Int = 0
)

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
