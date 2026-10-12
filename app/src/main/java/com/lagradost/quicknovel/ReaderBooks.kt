package com.lagradost.quicknovel

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaPlayer
import android.speech.tts.Voice
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.text.getSpans
import androidx.core.text.toSpanned
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKeyClass
import com.lagradost.quicknovel.BookDownloader2Helper.getQuickChapter
import com.lagradost.quicknovel.CommonActivity.TAG
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.TTSHelper.parseTextToSpans
import com.lagradost.quicknovel.TTSHelper.preParseHtml
import com.lagradost.quicknovel.TTSHelper.ttsParseText
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.letInner
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.map
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.mvvm.safeApiCall
import com.lagradost.quicknovel.mvvm.safeAsync
import com.lagradost.quicknovel.mvvm.throwableToResource
import com.lagradost.quicknovel.providers.RedditProvider
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.ui.ReadingType
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityIndex
import com.lagradost.quicknovel.ui.UiText
import com.lagradost.quicknovel.ui.toScroll
import com.lagradost.quicknovel.ui.toUiText
import com.lagradost.quicknovel.ui.txt
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.translator.ChapterTranslationRepository
import com.lagradost.quicknovel.imported.EpubChapters
import com.lagradost.quicknovel.imported.ImportedBooks
import com.lagradost.quicknovel.translator.NovelAnalyzer
import com.lagradost.quicknovel.ui.translate.NovelChapterSource
import com.lagradost.quicknovel.ui.translate.ReadAheadScheduler
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.TranslatorNovelId
import com.lagradost.quicknovel.util.CoilImagesPlugin
import com.lagradost.quicknovel.util.CoilImagesPlugin.CoilStore
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread
import com.lagradost.quicknovel.util.SubtitleHelper
import com.lagradost.quicknovel.util.translation.TranslationManager
import com.lagradost.quicknovel.util.translation.models.TranslatorAgents
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageSizeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.commonmark.node.Node
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

abstract class AbstractBook {
    open fun resolveUrl(url: String): String {
        return url
    }

    abstract val canReload: Boolean

    abstract fun size(): Int
    abstract fun title(): String
    abstract fun getChapterTitle(index: Int): UiText
    abstract fun getLoadingStatus(index: Int): String?

    @Throws
    open fun loadImage(image: String): InputStream? = null

    fun loadImageBitmap(image: String): Bitmap? {
        return try {
            // read file and not save
            val stream = this.loadImage(image)
            return if (stream != null) {
                 BookDownloader2Helper.decodeSafeBitmap(data = stream.readBytes())
            }
            else null
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    @WorkerThread
    @Throws
    abstract suspend fun getChapterData(index: Int, reload: Boolean): String

    abstract fun expand(last: String): Boolean

    @WorkerThread
    @Throws
    protected abstract suspend fun posterBytes(): ByteArray?

    private var poster: Bitmap? = null

    init {
        ioSafe {
            poster = posterBytes()?.let { byteArray ->
                BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            }
        }
    }

    fun poster(): Bitmap? {
        return poster
    }

    abstract fun author(): String?

    /** Returns true if this book comes from a translatable (zh/ja/ko) source. */
    open fun isTranslatableSource(): Boolean = false

    /** Chapters whose text was last served from the AI translation DB (or translated on demand). */
    val aiTranslatedChapters: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Translator novel id ("apiName/novelName") for translatable sources, else null. */
    open fun translatorNovelId(): String? = null

    /** zh / ja / ko of a translatable book (chapter-name translation), else null. May read disk. */
    open fun sourceLang(): String? = null

    /** The reader moved to chapter [index]: keep the next chapters translated (read-ahead mode). */
    fun onChapterOpened(index: Int) {
        val ctx = context ?: return
        val novelId = translatorNovelId() ?: return
        ReadAheadScheduler.onChapterOpened(ctx, novelId, index)
    }
}

class QuickBook(val data: QuickStreamData) : AbstractBook() {
    override fun resolveUrl(url: String): String {
        return Apis.getApiFromNameNull(data.meta.apiName)?.fixUrl(url) ?: url
    }

    override fun author(): String? {
        return data.meta.author
    }

    override fun isTranslatableSource(): Boolean =
        TranslationLanguages.isTranslatableApi(data.meta.apiName)

    override fun sourceLang(): String? = TranslationLanguages.langOfApi(data.meta.apiName)

    override fun translatorNovelId(): String? =
        if (isTranslatableSource()) TranslatorNovelId.from(data.meta.apiName, data.meta.name).also {
            // Lets the read-ahead worker find the chapter list without reloading the novel page.
            NovelChapterSource.remember(it, data.meta.apiName, data.meta.name, data.meta.author, data.data)
        } else null

    /** Toast once when the reader's on-demand path runs the automatic analysis. */
    private val analysisListener = object : NovelAnalyzer.Listener {
        @Volatile
        private var announced = false
        override fun onProgress(stage: NovelAnalyzer.Stage, current: Int, total: Int) {
            if (!announced) {
                announced = true
                showToast(R.string.analysis_reader_started)
            }
        }
    }

    override val canReload = true

    override fun size(): Int {
        return data.data.size
    }

    override fun title(): String {
        return data.meta.name
    }

    override fun getChapterTitle(index: Int): UiText {
        return data.data[index].name.toUiText()
    }

    override fun getLoadingStatus(index: Int): String {
        return data.data[index].url
    }

    override suspend fun getChapterData(index: Int, reload: Boolean): String {
        val ctx = context ?: throw ErrorLoadingException("Invalid context")

        // Translatable (zh/ja/ko) sources: stored AI translation, or on-demand translation if enabled
        val sourceLang = TranslationLanguages.langOfApi(data.meta.apiName)
        if (sourceLang != null && !reload) {
            val novelId = TranslatorNovelId.from(data.meta.apiName, data.meta.name)
            ChapterTranslationRepository.getOrTranslate(
                ctx, novelId, index, sourceLang,
                chapterCount = data.data.size,
                // Before the novel's first translation, the first chapters are analyzed (once).
                fetchChapter = { i -> data.data.getOrNull(i)?.let { ctx.getQuickChapter(data.meta, it, i, false)?.html } },
                analysisListener = analysisListener,
            ) {
                ctx.getQuickChapter(data.meta, data.data[index], index, false)?.html
            }?.let { aiTranslatedChapters += index; return it }
        }
        aiTranslatedChapters -= index

        return ctx.getQuickChapter(
            data.meta,
            data.data[index],
            index,
            reload
        )?.html ?: throw ErrorLoadingException("Error loading chapter")
    }

    override fun expand(last: String): Boolean {
        try {
            val elements =
                Jsoup.parse(last).allElements.filterNotNull()

            for (element in elements) {
                val href = element.attr("href") ?: continue

                val text =
                    element.ownText().replace(Regex("[\\[\\]().,|{}<>]"), "").trim()
                if (text.equals("next", true) || text.equals(
                        "next chapter",
                        true
                    ) || text.equals("next part", true)
                ) {
                    val name = RedditProvider.getName(href) ?: "Next"
                    data.data.add(ChapterData(name, href, null, null))
                    return true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return false
    }

    override suspend fun posterBytes(): ByteArray? {
        val poster = data.poster
        if (poster != null) {
            try {
                return MainActivity.app.get(poster).okhttpResponse.body.bytes()
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return null
    }
}

/**
 * An EPUB in the reader. [novelId] is set for books in the translation pipeline:
 * - an offline EPUB generated from a zh/ja/ko source: stored translations are served;
 * - an imported book ([imported] = true, id from [ImportedBooks]): stored translations are served
 *   and, like a streamed online novel, missing ones are translated on demand (analysis first,
 *   read-ahead, glossary), with the language detected on import or chosen on the result page.
 */
class RegularBook(
    val data: EpubBook,
    val novelId: String? = null,
    private val imported: Boolean = false,
) : AbstractBook() {
    /** Chapter split shared with the result page and the translation pipeline. */
    private val chapters = EpubChapters(data)

    /** Reload re-reads the original chapter text (without the translation), as for online novels. */
    override val canReload = imported && novelId != null

    // novelId is only set when opened from the translation pipeline (zh/ja/ko source or imported book)
    override fun isTranslatableSource(): Boolean = novelId != null

    override fun translatorNovelId(): String? = novelId

    override fun sourceLang(): String? {
        val id = novelId ?: return null
        // Imports: detected / chosen language; offline EPUBs of a source: "apiName/novelName".
        return if (imported) ImportedBooks.lang(id) else TranslationLanguages.langOfApi(id.substringBefore('/'))
    }

    /** Toast once when the on-demand path runs the automatic analysis. */
    private val analysisListener = object : NovelAnalyzer.Listener {
        @Volatile
        private var announced = false
        override fun onProgress(stage: NovelAnalyzer.Stage, current: Int, total: Int) {
            if (!announced) {
                announced = true
                showToast(R.string.analysis_reader_started)
            }
        }
    }

    override fun author(): String? {
        val author = data.metadata.authors.firstOrNull() ?: return null
        return listOfNotNull(author.firstname, author.lastname).joinToString(" ").ifBlank { null }
    }

    override fun loadImage(image: String): InputStream? {
        val decodedImage = try { URLDecoder.decode(image, "UTF-8") } catch (e: Exception) { image }
        val res = data.resources.resourceMap[decodedImage]
            ?: data.resources.resourceMap[image]
        return res?.inputStream
    }

    override fun size(): Int = chapters.size

    override fun title(): String = data.title ?: "Unknown Book"

    override fun getChapterTitle(index: Int): UiText {
        return chapters.title(index)?.toUiText()
            ?: txt(R.string.chapter_format, (index + 1).toString())
    }

    override fun getLoadingStatus(index: Int): String? = null

    override suspend fun getChapterData(index: Int, reload: Boolean): String {
        val id = novelId
        val ctx = context
        if (id != null && ctx != null && !reload) {
            val translated = if (imported) {
                ChapterTranslationRepository.getOrTranslate(
                    ctx, id, index, ImportedBooks.lang(id),
                    chapterCount = chapters.size,
                    // Before the book's first translation, the first chapters are analyzed (once).
                    fetchChapter = { i -> chapters.html(i).ifBlank { null } },
                    analysisListener = analysisListener,
                ) { chapters.html(index) }
            } else {
                ChapterTranslationRepository.getCached(ctx, id, index)
            }
            if (translated != null) {
                aiTranslatedChapters += index
                return translated
            }
        }
        aiTranslatedChapters -= index
        return chapters.html(index)
    }

    override fun expand(last: String): Boolean = false

    override suspend fun posterBytes(): ByteArray? = data.coverImage?.data
}
