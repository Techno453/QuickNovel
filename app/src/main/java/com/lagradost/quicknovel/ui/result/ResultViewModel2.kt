package com.lagradost.quicknovel.ui.result

import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lagradost.cloudstream4.state.ActionHandler
import com.lagradost.cloudstream4.state.DefaultEffectContainer
import com.lagradost.cloudstream4.state.DefaultStateContainer
import com.lagradost.cloudstream4.state.EffectContainer
import com.lagradost.cloudstream4.state.StateContainer
import com.lagradost.quicknovel.APIRepository
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.BookDownloader2.downloadProgress
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.BookDownloader2Helper.generateId
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.DOWNLOAD_EPUB_LAST_ACCESS
import com.lagradost.quicknovel.DownloadActionType
import com.lagradost.quicknovel.DownloadProgressState
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_CHAPTER
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_READ_AT
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION_SCROLL_CHAR
import com.lagradost.quicknovel.EpubResponse
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.RESULT_BOOKMARK_STATE
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.imported.ImportedBookId
import com.lagradost.quicknovel.imported.ImportedBooks
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.translator.ChapterNameTranslator
import com.lagradost.quicknovel.translator.MetadataLabels
import com.lagradost.quicknovel.translator.MetadataTranslations
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.TranslatorNovelId
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.ui.common.ImmutableChapterData
import com.lagradost.quicknovel.ui.common.ImmutableDownloadState
import com.lagradost.quicknovel.ui.common.ImmutableReview
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.translate.TranslationProgress
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * State of the Compose result page (docs/result-page.md). Everything the screen shows; the
 * screen itself only keeps scroll positions and open menus.
 */
@Immutable
data class ResultState(
    val apiName: String,
    val url: String,
    val response: ImmutableSearchResponse? = null,
    val responseError: Throwable? = null,
    val errorText: String? = null,
    val loadingResponse: Boolean = true,
    val reviews: ResultReviewState = ResultReviewState(),
    val bookmark: ReadType = ReadType.NONE,
    val dialog: ResultDialog? = null,
    /** The source has a reviews page. */
    val hasReviews: Boolean = false,
    /** The source is slow to download (rate limit above 2 s): show the "stream instead" warning. */
    val slowSource: Boolean = false,
    /** An imported EPUB/TXT book (no source site: no browser, share or download). */
    val isImported: Boolean = false,
    /** Translator novel id (`apiName/name`, or `Imported/...`). */
    val novelId: String? = null,
    /** zh / ja / ko, or null when the novel is not translated (or an import's language is unknown). */
    val sourceLang: String? = null,
    /** Detected / chosen language of an imported book; null for online novels. */
    val languageLabel: SourceLanguageLabel? = null,
    /** AI chapter translation is set up (Translate and Glossary buttons). */
    val translationEnabled: Boolean = false,
    /**
     * Chapter names are translated in the list (translatable novel and titles are translated at
     * all, see [MetadataTranslations.anyEnabled]); only the visible rows, batched.
     */
    val translateChapterNames: Boolean = false,
    /** Translated chapter names so far (original → English). */
    val chapterNames: PersistentMap<String, String> = persistentMapOf(),
    /** Every chapter, in novel order (null: no chapter list). */
    val allChapters: PersistentList<ImmutableChapterData>? = null,
    /** The chapters as listed: filtered, searched and sorted. */
    val chapters: PersistentList<ImmutableChapterData>? = null,
    val chapterFilter: ChapterFilter = ChapterFilter(),
    val chapterSort: Int = com.lagradost.quicknovel.ui.download.CHAPTER_SORT,
    val chapterQuery: String = "",
    /** Last read time per chapter index. */
    val readAt: PersistentMap<Int, Long> = persistentMapOf(),
    val chapterStatuses: PersistentMap<Int, ChapterTranslationStatus> = persistentMapOf(),
    /** The selected tab, kept while the two-pane layout hides the Chapters tab. */
    val selectedTab: ResultTab = ResultTab.Novel,
    val translatedTitle: String? = null,
    val translatedSynopsis: String? = null,
    /** Translated latest-chapter name and tags (original → English). */
    val labels: PersistentMap<String, String> = persistentMapOf(),
    val showOriginal: Boolean = false,
    /** Tags that open the source's listing for that genre (tag → index in the provider's tags). */
    val tagIndex: PersistentMap<String, Int> = persistentMapOf(),
    val translateButton: TranslateButtonState = TranslateButtonState(),
) {
    val hasChapters: Boolean get() = !allChapters.isNullOrEmpty()
    val hasRelated: Boolean get() = !response?.loadData?.related.isNullOrEmpty()

    /** Translate / Glossary are offered: translatable novel (imports always) and AI set up. */
    val showTranslate: Boolean
        get() = translationEnabled && hasChapters && (sourceLang != null || isImported)

    val latestChapterName: String? get() = allChapters?.lastOrNull()?.name
}

@Immutable
sealed class ResultDialog {
    data class Bookmark(val selected: ReadType) : ResultDialog()

    /** "Source language…" of an imported book; [then] runs after a choice. */
    data class SourceLanguage(
        val detected: String?,
        val current: String?,
        val then: AfterLanguage = AfterLanguage.Nothing,
        val startChapter: Int? = null,
    ) : ResultDialog()

    data object ConfirmDelete : ResultDialog()
    data class DownloadFrom(val chapterCount: Int) : ResultDialog()
    data object ChapterFilter : ResultDialog()
}

enum class AfterLanguage { Nothing, Translate, Analyze }

@Immutable
data class ResultReviewState(
    val loading: Boolean = false,
    val page: Int = 0,
    val error: Throwable? = null,
    val items: PersistentList<ImmutableReview> = persistentListOf(),
)

enum class ChapterOperation {
    /** Open the chapter in the reader. */
    Stream,

    /** Mark read / unread. */
    ToggleRead,
}

@Immutable
sealed class ResultPageAction {
    data class SetBookmark(val type: ReadType) : ResultPageAction()
    data object OpenBookmark : ResultPageAction()
    data object DismissDialog : ResultPageAction()
    data object ExpandReviews : ResultPageAction()
    data class ResultAction(val action: SearchResponseAction) : ResultPageAction()
    data class ChapterAction(
        val response: ImmutableSearchResponse,
        val chapter: ImmutableChapterData,
        val operation: ChapterOperation
    ) : ResultPageAction()

    data class SelectTab(val tab: ResultTab) : ResultPageAction()
    data object ToggleOriginal : ResultPageAction()
    data object Retry : ResultPageAction()

    data object OpenChapterFilter : ResultPageAction()
    data class SetChapterFilter(val filter: ChapterFilter) : ResultPageAction()
    data class SetChapterSort(val sort: Int) : ResultPageAction()
    data class SetChapterQuery(val query: String) : ResultPageAction()

    /** The chapter list shows listed rows [first]..[last] (drives chapter-name translation). */
    data class ChapterRowsVisible(val first: Int, val last: Int) : ResultPageAction()

    data class Download(val action: DownloadMenuAction) : ResultPageAction()
    /** Tap on the download button when it has no choice to make: download / pause / resume. */
    data object DownloadOrPause : ResultPageAction()
    data class DownloadFrom(val start: Int?) : ResultPageAction()
    data object ConfirmDelete : ResultPageAction()
    data object ReadEpub : ResultPageAction()
    data object StreamRead : ResultPageAction()

    /** Translate button (a chapter's long-press menu passes [startChapter]). */
    data class Translate(val startChapter: Int? = null) : ResultPageAction()
    data object Analyze : ResultPageAction()
    data object OpenGlossary : ResultPageAction()
    data object ChooseSourceLanguage : ResultPageAction()
    data class SetSourceLanguage(val code: String) : ResultPageAction()
    data class OpenTag(val index: Int) : ResultPageAction()

    /** The activity-wide translation job changed ([activeNovelId]: the novel it runs for). */
    data class TranslationProgressChanged(val progress: TranslationProgress?, val activeNovelId: String?) :
        ResultPageAction()

    /** Back on screen (after reading): read marks may have changed. */
    data object Resumed : ResultPageAction()
}

@Immutable
sealed class ResultPageEffect {
    data class Toast(val text: Int) : ResultPageEffect()
    data class ToastText(val text: String) : ResultPageEffect()
    data class Error(val t: Throwable) : ResultPageEffect()

    /** The translate range dialog (chapter / range / whole novel, overnight batch). */
    data class OpenTranslateDialog(
        val novelId: String,
        val apiName: String,
        val chapters: List<ChapterData>,
        val author: String?,
        val name: String,
        val startChapter: Int?,
    ) : ResultPageEffect()

    data class OpenAnalyze(
        val novelId: String,
        val apiName: String,
        val name: String,
        val author: String?,
        val chapters: List<ChapterData>,
    ) : ResultPageEffect()

    data class OpenGlossary(val novelId: String, val name: String) : ResultPageEffect()
    data class OpenTag(val apiName: String, val index: Int) : ResultPageEffect()
}

/**
 * View model of the Compose result page: loads the novel (provider or imported book), owns the
 * download, bookmark, history, chapter list and translation state, and turns UI actions into
 * effects for the fragment (dialogs and navigation that need a FragmentManager).
 */
class ResultViewModel2(
    val apiName: String,
    val url: String,
    initializer: ImmutableSearchResponse? = null,
) : ViewModel(), ActionHandler<ResultPageAction>,
    StateContainer<ResultState> by DefaultStateContainer(initialState(apiName, url, initializer)),
    EffectContainer<ResultPageEffect> by DefaultEffectContainer() {

    companion object {
        private val context get() = BaseApplication.context

        private fun initialState(apiName: String, url: String, initializer: ImmutableSearchResponse?): ResultState {
            val api = Apis.getApiFromNameOrNull(apiName)
            return ResultState(
                apiName = apiName,
                url = url,
                response = initializer,
                bookmark = initializer?.id?.let { getBookmarkState(it) } ?: ReadType.NONE,
                hasReviews = api?.hasReviews == true,
                slowSource = (api?.rateLimitTime ?: 0) > 2000,
                isImported = ImportedBookId.fromResultUrl(url) != null,
                chapterFilter = currentFilter(),
                chapterSort = ResultViewModel.sortChapterBy,
            )
        }

        fun provideFactory(bundle: Bundle) = viewModelFactory {
            initializer {
                ResultViewModel2(
                    apiName = bundle.getString("apiName")!!,
                    url = bundle.getString("url")!!,
                )
            }
        }

        fun setBookmarkState(id: Int, state: ReadType) {
            setKey(RESULT_BOOKMARK_STATE, id.toString(), state.prefValue)
        }

        fun getBookmarkState(id: Int): ReadType = ReadType.fromSpinner(
            getKey<Int>(RESULT_BOOKMARK_STATE, id.toString())
        )

        /** The filter switches, stored in the same preferences as the old page's sheet. */
        private fun currentFilter() = ChapterFilter(
            read = ResultViewModel.filterChapterByRead,
            unread = ResultViewModel.filterChapterByUnread,
            downloaded = ResultViewModel.filterChapterByDownloads,
            bookmarked = ResultViewModel.filterChapterByBookmarked,
        )
    }

    /** The provider, or null for imported books (and unknown sources). */
    val api: APIRepository? = Apis.getApiFromNameOrNull(apiName)

    private val loadMutex = Mutex()

    /** The loaded novel as the downloader and reader want it. */
    private var load: LoadResponse? = null
    private var loadId: Int = 0
    private var loadUrl: String = url
    private var loadJob: Job? = null
    private var metadataJob: Job? = null

    /** Batched translation of the visible chapter names (per source language). */
    private var chapterNameTranslator: ChapterNameTranslator? = null
    private var chapterNameLang: String? = null
    private var listedNames: Pair<List<ImmutableChapterData>, List<String>>? = null
    private var statusSource: ChapterStatusSource? = null

    init {
        loadNovel()
        BookDownloader2.downloadProgressChanged += ::onDownloadStateChange
        BookDownloader2.downloadRemoved += ::onDownloadRemoved
        BookDownloader2.bookmarkChanged += ::onBookmarkChanged
        BookDownloader2.chapterReadChanged += ::onChapterChanged
    }

    override fun onCleared() {
        BookDownloader2.downloadProgressChanged -= ::onDownloadStateChange
        BookDownloader2.downloadRemoved -= ::onDownloadRemoved
        BookDownloader2.bookmarkChanged -= ::onBookmarkChanged
        BookDownloader2.chapterReadChanged -= ::onChapterChanged
    }

    // ============================== loading ==============================

    private fun loadNovel() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            updateState { copy(loadingResponse = response == null, responseError = null, errorText = null) }
            ImportedBookId.fromResultUrl(url)?.let { novelId ->
                loadImported(novelId)
                return@launch
            }
            val api = api
            if (api == null) {
                fail(null, "Unknown source: $apiName")
                return@launch
            }
            when (val data = api.load(url)) {
                is Resource.Success -> onLoaded(data.value)
                is Resource.Failure -> fail(null, data.errorString)
                else -> {}
            }
        }
    }

    private fun fail(t: Throwable?, text: String) {
        updateState { copy(loadingResponse = false, responseError = t, errorText = text) }
    }

    /** An imported EPUB/TXT book: chapters from its stored EPUB, no provider. */
    private suspend fun loadImported(novelId: String) {
        val data = withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext null
            val record = ImportedBooks.record(novelId) ?: return@withContext null
            ImportedBooks.loadResponse(ctx, record)
        }
        if (data == null) {
            fail(null, "This imported book is no longer in the library")
            return
        }
        onLoaded(data)
    }

    private suspend fun onLoaded(res: LoadResponse) {
        val id = generateId(res, apiName)
        val imported = ImportedBookId.fromResultUrl(res.url) != null
        val novelId = ImportedBookId.fromResultUrl(res.url) ?: TranslatorNovelId.from(apiName, res.name)
        val (response, chapters, extra) = withContext(Dispatchers.IO) {
            val response = ImmutableSearchResponse.from(res).let {
                // generateId(res, res.apiName) above uses the response's own api name; keep ours.
                if (it.id != id) it.copy(id = id) else it
            }
            val chapters = chapterList(res)
            val ctx = context
            val lang = sourceLangOf(res)
            val labelInfo = if (imported) {
                val record = ImportedBooks.record(novelId)
                ResultPageLogic.sourceLanguageLabel(record?.detectedLang, record?.langOverride)
            } else null
            Triple(
                response,
                chapters,
                Extra(
                    lang = lang,
                    languageLabel = labelInfo,
                    translationEnabled = SecurePrefs.isTranslationEnabled(ctx),
                    titlesTranslated = MetadataTranslations.anyEnabled(ctx),
                    readAt = readTimes(res, chapters?.size ?: 0),
                    bookmark = getBookmarkState(id),
                )
            )
        }

        loadMutex.withLock {
            load = res
            loadId = id
            loadUrl = res.url
        }

        val tagIndex = api?.tags?.mapIndexed { i, (label, _) -> label to i }?.toMap().orEmpty()
        updateState {
            copy(
                response = response,
                loadingResponse = false,
                responseError = null,
                errorText = null,
                isImported = imported,
                novelId = novelId,
                sourceLang = extra.lang,
                languageLabel = extra.languageLabel,
                translationEnabled = extra.translationEnabled,
                translateChapterNames = extra.lang != null && extra.titlesTranslated,
                allChapters = chapters,
                readAt = extra.readAt,
                bookmark = extra.bookmark,
                tagIndex = tagIndex.toPersistentMap(),
                // cached translations show at once, the rest arrives below
                translatedTitle = extra.lang?.let { MetadataTranslations.translator.cachedTitle(TranslatorNovelId.from(apiName, res.name), res.name) },
                labels = MetadataLabels.cached(extra.lang, chapters?.lastOrNull()?.name, res.tags).toPersistentMap(),
            ).withListedChapters()
        }

        withContext(Dispatchers.IO) {
            // What the old page did when it opened a novel: last access, bookmark data, the
            // library's "+N new" (openChanged clears it) and the download state.
            setKey(DOWNLOAD_EPUB_LAST_ACCESS, id.toString(), System.currentTimeMillis())
            if (extra.bookmark != ReadType.NONE) {
                updateBookmarkData(res, id)
                BookDownloader2.bookmarkChanged(id)
            }
            BookDownloader2.openChanged(id)
        }
        insertZeroData()
        translateMetadata()
        refreshChapterStatuses()
    }

    private data class Extra(
        val lang: String?,
        val languageLabel: SourceLanguageLabel?,
        val translationEnabled: Boolean,
        val titlesTranslated: Boolean,
        val readAt: PersistentMap<Int, Long>,
        val bookmark: ReadType,
    )

    /** zh / ja / ko of the novel: the provider's language, or the imported book's detected / chosen one. */
    private fun sourceLangOf(res: LoadResponse): String? {
        ImportedBookId.fromResultUrl(res.url)?.let { return ImportedBooks.lang(it) }
        return TranslationLanguages.normalize(api?.api?.lang)
    }

    private fun chapterList(res: LoadResponse): PersistentList<ImmutableChapterData>? = when (res) {
        is StreamResponse -> res.data.mapIndexed { i, c -> ImmutableChapterData.from(c, i) }.toPersistentList()
        is EpubResponse -> BookDownloader2.getEpubChapterNames(context, res.apiName, res.author, res.name)
            ?.mapIndexed { i, name -> ImmutableChapterData(name = name, url = "epub_chapter://$i", index = i) }
            ?.toPersistentList()

        else -> null
    }

    /** Last read time of every chapter (only stream novels record them, as before). */
    private fun readTimes(res: LoadResponse, count: Int): PersistentMap<Int, Long> {
        if (res !is StreamResponse) return persistentMapOf()
        val out = HashMap<Int, Long>()
        for (i in 0 until count) {
            getKey<Long>(EPUB_CURRENT_POSITION_READ_AT, "${res.name}/$i")?.let { out[i] = it }
        }
        return out.toPersistentMap()
    }

    /** Recomputes [ResultState.chapters] from the full list, the filter, the query and the sort. */
    private fun ResultState.withListedChapters(): ResultState {
        val all = allChapters ?: return copy(chapters = null)
        val listed = ResultPageLogic.filterChapters(
            chapters = all,
            readAt = readAt,
            downloadedProgress = response?.downloadState?.progress,
            filter = chapterFilter,
            sort = chapterSort,
            query = chapterQuery,
            translatedNames = if (showOriginal) emptyMap() else chapterNames,
        )
        return copy(chapters = listed.toPersistentList())
    }

    // ============================== translated metadata ==============================

    /** Title + synopsis (one call) and the latest chapter + tags (one batched call), cached. */
    private fun translateMetadata() {
        val s = state.value
        val lang = s.sourceLang ?: return
        val response = s.response ?: return
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { MetadataTranslations.anyEnabled(context) }) {
                updateState {
                    copy(
                        translatedTitle = null, translatedSynopsis = null, labels = persistentMapOf(),
                        translateChapterNames = false, chapterNames = persistentMapOf(),
                    )
                }
                return@launch
            }
            launch {
                val labels = try {
                    MetadataLabels.translate(lang, s.latestChapterName, response.tags)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logError(t)
                    emptyMap()
                }
                if (labels.isNotEmpty()) updateState { copy(labels = (this.labels + labels).toPersistentMap()) }
            }
            val translated = try {
                MetadataTranslations.novel(apiName, response.name, response.synopsis, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError(t)
                null
            } ?: return@launch
            updateState { copy(translatedTitle = translated.title, translatedSynopsis = translated.synopsis) }
        }
    }

    // ============================== chapter names ==============================

    /**
     * The chapter list shows listed rows [first]..[last]: their names (plus a small lookahead)
     * are translated in batches once the list stops for a moment ([ChapterNameWindow]).
     */
    private fun onChapterRowsVisible(first: Int, last: Int) {
        val s = state.value
        val lang = s.sourceLang
        val chapters = s.chapters
        if (lang == null || chapters == null || !s.translateChapterNames || s.showOriginal) return
        val translator = chapterNameTranslator?.takeIf { chapterNameLang == lang } ?: run {
            chapterNameTranslator?.reset()
            ChapterNameTranslator(
                scope = viewModelScope,
                translate = { names -> MetadataLabels.translateNames(lang, names) },
                onTranslated = { map ->
                    updateState {
                        if (sourceLang != lang) return@updateState this
                        val next = copy(chapterNames = (chapterNames + map).toPersistentMap())
                        if (chapterQuery.isBlank()) next else next.withListedChapters()
                    }
                },
                cached = { name -> MetadataLabels.cachedName(lang, name) },
            ).also {
                chapterNameTranslator = it
                chapterNameLang = lang
            }
        }
        val names = listedNames?.takeIf { it.first === chapters }?.second
            ?: chapters.map { it.name }.also { listedNames = chapters to it }
        translator.visible(names, first, last)
    }

    // ============================== chapter statuses ==============================

    /** One refresh of the Translated / Source changed / Queued chips (the screen calls this every 4 s). */
    suspend fun refreshChapterStatuses() {
        val s = state.value
        val novelId = s.novelId ?: return
        if (s.sourceLang == null) {
            if (s.chapterStatuses.isNotEmpty()) updateState { copy(chapterStatuses = persistentMapOf()) }
            return
        }
        val ctx = context ?: return
        val source = statusSource ?: TranslatorChapterStatusSource.create(ctx).also { statusSource = it }
        val next = withContext(Dispatchers.IO) { ChapterStatusSource.statuses(source, novelId) }
        if (next != state.value.chapterStatuses) updateState { copy(chapterStatuses = next.toPersistentMap()) }
    }

    // ============================== downloads ==============================

    private fun onDownloadStateChange(data: Pair<Int, DownloadProgressState>) {
        viewModelScope.launch {
            val (id, newState) = data
            if (id != loadId || load == null) return@launch
            setDownloadState(newState)
        }
    }

    private fun onDownloadRemoved(id: Int) {
        viewModelScope.launch {
            if (id != loadId || load == null) return@launch
            insertZeroData()
        }
    }

    private fun setDownloadState(newState: DownloadProgressState) {
        updateState {
            val r = response ?: return@updateState this
            val before = r.downloadState?.progress
            val next = copy(response = r.copy(downloadState = ImmutableDownloadState.from(newState)))
            // the "downloaded" filter depends on the progress
            if (chapterFilter.downloaded && before != newState.progress) next.withListedChapters() else next
        }
    }

    /** Puts the stored (or an empty) download state in place when there is no live one. */
    private suspend fun insertZeroData() {
        val res = load ?: return
        val id = loadId
        val newState = withContext(Dispatchers.IO) {
            BookDownloader2.downloadInfoMutex.withLock {
                downloadProgress[id] ?: (BookDownloader2Helper.downloadInfo(context, res.author, res.name, res.apiName)
                    ?.let { info ->
                        DownloadProgressState(
                            state = DownloadState.Nothing,
                            progress = info.progress,
                            total = info.total,
                            downloaded = info.downloaded,
                            lastUpdatedMs = System.currentTimeMillis(),
                            etaMs = null
                        ).also { downloadProgress[id] = it }
                    } ?: DownloadProgressState(
                    state = DownloadState.Nothing,
                    progress = 0,
                    total = (res as? StreamResponse)?.data?.size?.toLong() ?: 1,
                    downloaded = 0,
                    lastUpdatedMs = System.currentTimeMillis(),
                    etaMs = null
                ))
            }
        }
        setDownloadState(newState)
    }

    /** Download, or pause / resume the running one (tap on the download button). */
    private fun downloadOrPause() = launchWithLoad { res, id ->
        BookDownloader2.downloadInfoMutex.withLock {
            when (downloadProgress[id]?.state) {
                DownloadState.IsPaused -> BookDownloader2.addPendingAction(id, DownloadActionType.Resume)
                DownloadState.IsDownloading -> BookDownloader2.addPendingAction(id, DownloadActionType.Pause)
                DownloadState.IsDone, DownloadState.IsPending -> {}
                else -> BookDownloader2.download(res, context ?: return@launchWithLoad)
            }
        }
    }

    private fun download() = launchWithLoad { res, id ->
        BookDownloader2.downloadInfoMutex.withLock {
            when (downloadProgress[id]?.state) {
                DownloadState.IsPaused -> BookDownloader2.addPendingAction(id, DownloadActionType.Resume)
                DownloadState.IsDone, DownloadState.IsPending -> {}
                else -> BookDownloader2.download(res, context ?: return@launchWithLoad)
            }
        }
    }

    private fun downloadFrom(start: Int?) = launchWithLoad { res, id ->
        val api = api ?: return@launchWithLoad
        BookDownloader2.downloadInfoMutex.withLock {
            BookDownloader2.changeDownloadStart(res, api, start)
            when (downloadProgress[id]?.state) {
                DownloadState.IsPaused -> BookDownloader2.addPendingAction(id, DownloadActionType.Resume)
                DownloadState.IsPending -> {}
                else -> BookDownloader2.download(res, context ?: return@launchWithLoad)
            }
        }
    }

    private fun pendingAction(onlyIn: Set<DownloadState>, action: DownloadActionType) = launchWithLoad { _, id ->
        BookDownloader2.downloadInfoMutex.withLock {
            val current = downloadProgress[id]?.state ?: return@launchWithLoad
            if (current in onlyIn) BookDownloader2.addPendingAction(id, action)
        }
    }

    private fun delete() = launchWithLoad { res, _ ->
        BookDownloader2.deleteNovel(res.author, res.name, apiName)
        val novelId = state.value.novelId ?: return@launchWithLoad
        val ctx = context ?: return@launchWithLoad
        withContext(Dispatchers.IO) {
            try {
                if (ImportedBooks.isImported(novelId)) ImportedBooks.remove(novelId)
                TranslatorDatabase.getInstance(ctx).deleteAllForNovel(novelId)
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    private fun launchWithLoad(block: suspend (LoadResponse, Int) -> Unit) = viewModelScope.launch {
        val (res, id) = loadMutex.withLock { (load ?: return@launch) to loadId }
        try {
            block(res, id)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logError(t)
            postEffect { ResultPageEffect.Error(t) }
        }
    }

    private fun runDownloadAction(action: DownloadMenuAction) {
        when (action) {
            DownloadMenuAction.Resume, DownloadMenuAction.Redownload -> download()
            DownloadMenuAction.Download -> downloadFrom(null)
            DownloadMenuAction.Pause -> pendingAction(setOf(DownloadState.IsDownloading), DownloadActionType.Pause)
            DownloadMenuAction.Stop -> pendingAction(
                setOf(DownloadState.IsDownloading, DownloadState.IsPaused, DownloadState.IsPending),
                DownloadActionType.Stop
            )

            DownloadMenuAction.DownloadFrom -> updateState {
                copy(dialog = ResultDialog.DownloadFrom(allChapters?.size ?: 0))
            }

            DownloadMenuAction.Delete -> updateState { copy(dialog = ResultDialog.ConfirmDelete) }
        }
    }

    // ============================== reading ==============================

    private suspend fun addToHistory(res: LoadResponse, id: Int) {
        setKey(
            HISTORY_FOLDER, id.toString(), ResultCached(
                loadUrl, res.name, apiName, id, res.author, res.posterUrl, res.tags, res.rating,
                (res as? StreamResponse)?.data?.size ?: 1, System.currentTimeMillis(),
                synopsis = res.synopsis, posterHeaders = res.posterHeaders
            )
        )
    }

    private fun updateBookmarkData(res: LoadResponse, id: Int) {
        setKey(
            RESULT_BOOKMARK, id.toString(), ResultCached(
                loadUrl, res.name, apiName, id, res.author, res.posterUrl, res.tags, res.rating,
                (res as? StreamResponse)?.data?.size ?: 1, System.currentTimeMillis(),
                synopsis = res.synopsis, posterHeaders = res.posterHeaders
            )
        )
    }

    private fun setReadChapter(res: LoadResponse, index: Int, value: Boolean) {
        if (res !is StreamResponse) return
        if (value) setKey(EPUB_CURRENT_POSITION_READ_AT, "${res.name}/$index", System.currentTimeMillis())
        else removeKey(EPUB_CURRENT_POSITION_READ_AT, "${res.name}/$index")
    }

    /** Opens the reader (streaming), at [chapter] if given; imported books read their stored EPUB. */
    private fun streamRead(chapter: ImmutableChapterData?) = launchWithLoad { res, id ->
        withContext(Dispatchers.IO) {
            addToHistory(res, id)
            val importedId = ImportedBookId.fromResultUrl(res.url)
            if (chapter != null && chapter.index >= 0) {
                val key = importedId?.let { ImportedBooks.record(it)?.epubTitle } ?: res.name
                if (res is StreamResponse) setReadChapter(res, chapter.index, true)
                setKey(EPUB_CURRENT_POSITION, key, chapter.index)
                setKey(EPUB_CURRENT_POSITION_CHAPTER, key, chapter.name)
                setKey(EPUB_CURRENT_POSITION_SCROLL_CHAR, key, 0)
            }
            if (importedId != null) {
                BookDownloader2.readEpub(
                    id, state.value.response?.downloadState?.progress?.toInt() ?: 1,
                    res.author, res.name, apiName, res.synopsis
                )
            } else {
                BookDownloader2.stream(res, apiName)
            }
        }
        if (chapter != null) refreshReadTimes()
    }

    private fun readEpub() = launchWithLoad { res, id ->
        val progress = state.value.response?.downloadState?.progress?.toInt() ?: return@launchWithLoad
        withContext(Dispatchers.IO) {
            addToHistory(res, id)
            try {
                BookDownloader2.readEpub(id, progress, res.author, res.name, apiName, res.synopsis) {
                    updateState { copy(response = response?.copy(generating = true)) }
                }
            } finally {
                updateState { copy(response = response?.copy(generating = false)) }
            }
        }
    }

    private fun refreshReadTimes() {
        viewModelScope.launch {
            val res = load ?: return@launch
            val count = state.value.allChapters?.size ?: 0
            val times = withContext(Dispatchers.IO) { readTimes(res, count) }
            if (times != state.value.readAt) updateState { copy(readAt = times).withListedChapters() }
        }
    }

    private fun onBookmarkChanged(id: Int) {
        viewModelScope.launch {
            if (id != loadId) return@launch
            updateState { copy(bookmark = getBookmarkState(id)) }
        }
    }

    private fun onChapterChanged(name: String) {
        viewModelScope.launch {
            val r = state.value.response ?: return@launch
            if (name != r.name) return@launch
            updateState {
                val current = response ?: return@updateState this
                copy(
                    response = current.copy(
                        chaptersRead = ImmutableSearchResponse.chaptersRead(name),
                        timeOfPageOpened = System.currentTimeMillis(),
                        epubSize = current.id?.let { ImmutableSearchResponse.epubSize(it) } ?: current.epubSize
                    )
                )
            }
            refreshReadTimes()
        }
    }

    // ============================== translation entry points ==============================

    private fun streamChapters(): List<ChapterData>? = (load as? StreamResponse)?.data

    private fun translate(startChapter: Int?) {
        val s = state.value
        val novelId = s.novelId ?: return
        val response = s.response ?: return
        if (s.isImported && s.sourceLang == null) {
            askLanguage(AfterLanguage.Translate, startChapter)
            return
        }
        val chapters = streamChapters() ?: return
        viewModelScope.launch {
            postEffect {
                ResultPageEffect.OpenTranslateDialog(
                    novelId = novelId, apiName = apiName, chapters = ArrayList(chapters),
                    author = response.author, name = response.name, startChapter = startChapter,
                )
            }
        }
    }

    private fun analyze() {
        val s = state.value
        val novelId = s.novelId ?: return
        val response = s.response ?: return
        if (s.isImported && s.sourceLang == null) {
            askLanguage(AfterLanguage.Analyze, null)
            return
        }
        val chapters = streamChapters() ?: return
        viewModelScope.launch {
            postEffect { ResultPageEffect.OpenAnalyze(novelId, apiName, response.name, response.author, chapters) }
        }
    }

    private fun askLanguage(then: AfterLanguage, startChapter: Int?) {
        val novelId = state.value.novelId ?: return
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { ImportedBooks.record(novelId) }
            updateState {
                copy(
                    dialog = ResultDialog.SourceLanguage(
                        detected = record?.detectedLang,
                        current = record?.lang(),
                        then = then,
                        startChapter = startChapter,
                    )
                )
            }
        }
    }

    private fun setSourceLanguage(code: String) {
        val s = state.value
        val novelId = s.novelId ?: return
        val pending = s.dialog as? ResultDialog.SourceLanguage
        viewModelScope.launch {
            val (record, titlesTranslated) = withContext(Dispatchers.IO) {
                ImportedBooks.setLangOverride(novelId, code)
                ImportedBooks.record(novelId) to MetadataTranslations.anyEnabled(context)
            }
            updateState {
                copy(
                    dialog = null,
                    sourceLang = TranslationLanguages.normalize(code) ?: code,
                    languageLabel = ResultPageLogic.sourceLanguageLabel(record?.detectedLang, record?.langOverride ?: code),
                    translateChapterNames = titlesTranslated,
                    chapterNames = persistentMapOf(),
                ).withListedChapters()
            }
            // Chips and title translation follow the new language.
            translateMetadata()
            refreshChapterStatuses()
            when (pending?.then) {
                AfterLanguage.Translate -> translate(pending.startChapter)
                AfterLanguage.Analyze -> analyze()
                else -> {}
            }
        }
    }

    // ============================== actions ==============================

    override fun onAction(action: ResultPageAction) {
        when (action) {
            is ResultPageAction.ResultAction -> {
                try {
                    action.action.doAction()
                } catch (t: NotImplementedError) {
                    logError(t)
                }
            }

            is ResultPageAction.ChapterAction -> when (action.operation) {
                ChapterOperation.Stream -> streamRead(action.chapter)
                ChapterOperation.ToggleRead -> launchWithLoad { res, _ ->
                    val read = state.value.readAt.containsKey(action.chapter.index)
                    withContext(Dispatchers.IO) { setReadChapter(res, action.chapter.index, !read) }
                    refreshReadTimes()
                }
            }

            ResultPageAction.ExpandReviews -> {
                if (state.value.reviews.loading) return
                viewModelScope.launch { expandReviews() }
            }

            ResultPageAction.OpenBookmark -> updateState { copy(dialog = ResultDialog.Bookmark(bookmark)) }
            ResultPageAction.DismissDialog -> updateState { copy(dialog = null) }
            is ResultPageAction.SetBookmark -> launchWithLoad { res, id ->
                withContext(Dispatchers.IO) {
                    setBookmarkState(id, action.type)
                    updateBookmarkData(res, id)
                }
                // bookmarkChanged updates the state through onBookmarkChanged
                BookDownloader2.bookmarkChanged(id)
            }

            is ResultPageAction.SelectTab -> {
                updateState { copy(selectedTab = action.tab) }
                if (action.tab == ResultTab.Reviews && state.value.reviews.items.isEmpty()) {
                    onAction(ResultPageAction.ExpandReviews)
                }
            }

            ResultPageAction.ToggleOriginal -> updateState {
                // A chapter search also matches translated names, which Show original turns off.
                copy(showOriginal = !showOriginal).let { if (chapterQuery.isBlank()) it else it.withListedChapters() }
            }
            ResultPageAction.Retry -> loadNovel()

            ResultPageAction.OpenChapterFilter -> updateState { copy(dialog = ResultDialog.ChapterFilter) }
            is ResultPageAction.SetChapterFilter -> {
                ResultViewModel.filterChapterByRead = action.filter.read
                ResultViewModel.filterChapterByUnread = action.filter.unread
                ResultViewModel.filterChapterByDownloads = action.filter.downloaded
                ResultViewModel.filterChapterByBookmarked = action.filter.bookmarked
                updateState { copy(chapterFilter = action.filter).withListedChapters() }
            }

            is ResultPageAction.SetChapterSort -> {
                ResultViewModel.sortChapterBy = action.sort
                updateState { copy(chapterSort = action.sort).withListedChapters() }
            }

            is ResultPageAction.SetChapterQuery -> updateState { copy(chapterQuery = action.query).withListedChapters() }
            is ResultPageAction.ChapterRowsVisible -> onChapterRowsVisible(action.first, action.last)

            is ResultPageAction.Download -> {
                // Imported books only offer Delete: there is no site to download from.
                if (state.value.isImported && action.action != DownloadMenuAction.Delete) return
                runDownloadAction(action.action)
            }

            ResultPageAction.DownloadOrPause -> if (!state.value.isImported) downloadOrPause()
            is ResultPageAction.DownloadFrom -> {
                updateState { copy(dialog = null) }
                if (!state.value.isImported) downloadFrom(action.start)
            }

            ResultPageAction.ConfirmDelete -> {
                updateState { copy(dialog = null) }
                delete()
            }

            ResultPageAction.ReadEpub -> readEpub()
            ResultPageAction.StreamRead -> streamRead(null)

            is ResultPageAction.Translate -> translate(action.startChapter)
            ResultPageAction.Analyze -> analyze()
            ResultPageAction.OpenGlossary -> {
                val s = state.value
                val novelId = s.novelId ?: return
                val name = s.response?.name ?: return
                // The glossary subtitle shows the title as the page shows it.
                val shown = ResultPageLogic.displayedTitle(name, s.translatedTitle, s.showOriginal)
                viewModelScope.launch { postEffect { ResultPageEffect.OpenGlossary(novelId, shown) } }
            }

            ResultPageAction.ChooseSourceLanguage -> askLanguage(AfterLanguage.Nothing, null)
            is ResultPageAction.SetSourceLanguage -> setSourceLanguage(action.code)
            is ResultPageAction.OpenTag -> {
                val name = api?.name ?: return
                viewModelScope.launch { postEffect { ResultPageEffect.OpenTag(name, action.index) } }
            }

            is ResultPageAction.TranslationProgressChanged -> {
                val novelId = state.value.novelId
                val button = ResultPageLogic.translateButton(
                    action.progress,
                    isForThisNovel = novelId != null && novelId == action.activeNovelId
                )
                if (button != state.value.translateButton) updateState { copy(translateButton = button) }
            }

            ResultPageAction.Resumed -> {
                refreshReadTimes()
                val enabled = SecurePrefs.isTranslationEnabled(context)
                if (enabled != state.value.translationEnabled) updateState { copy(translationEnabled = enabled) }
            }
        }
    }

    // ============================== reviews ==============================

    private val expandMutex = Mutex()

    private suspend fun expandReviews() = withContext(Dispatchers.IO) {
        val api = api ?: return@withContext
        if (!expandMutex.tryLock()) return@withContext
        try {
            val query = state.value.reviews
            val response = state.value.response ?: return@withContext
            updateState { copy(reviews = reviews.copy(loading = true)) }
            val nextPage = query.page + 1
            api.loadReviewsResult(page = nextPage, url = response.url, data = response.reviewData)
                .onFailure { error ->
                    if (error is CancellationException) return@withContext
                    updateState { copy(reviews = reviews.copy(loading = false, error = error)) }
                }.onSuccess { list ->
                    updateState {
                        if (query.page != reviews.page) return@updateState copy(reviews = reviews.copy(loading = false))
                        copy(
                            reviews = reviews.copy(
                                loading = false,
                                error = null,
                                items = query.items.addingAll(list),
                                page = nextPage
                            )
                        )
                    }
                }
        } finally {
            updateState { copy(reviews = reviews.copy(loading = false)) }
            expandMutex.unlock()
        }
    }
}
