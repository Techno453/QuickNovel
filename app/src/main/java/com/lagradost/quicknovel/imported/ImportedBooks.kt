package com.lagradost.quicknovel.imported

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.packageAsXHtml
import com.lagradost.quicknovel.translator.ChapterTextSource
import com.lagradost.quicknovel.translator.ChapterTranslationRepository
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.MediaTypes
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.epub.EpubWriter
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.jsoup.nodes.Entities
import java.io.ByteArrayOutputStream
import java.io.File

/** What the translator knows about an imported book (stored in the app's key store). */
data class ImportedBookRecord(
    @JsonProperty("novelId") val novelId: String,
    /** Library name (EPUB title, or the file name for TXT and cover-less EPUBs). */
    @JsonProperty("name") val name: String,
    @JsonProperty("author") val author: String?,
    /** "epub" or "txt" (a TXT import is stored as a generated EPUB). */
    @JsonProperty("format") val format: String,
    /** SHA-256 of the imported file. */
    @JsonProperty("contentHash") val contentHash: String,
    /** The EPUB's own title (the reader keys its position by it). */
    @JsonProperty("epubTitle") val epubTitle: String?,
    @JsonProperty("synopsis") val synopsis: String?,
    @JsonProperty("chapterCount") val chapterCount: Int,
    /** Detected from the text ([SourceLanguageDetector]); null = undecided. */
    @JsonProperty("detectedLang") val detectedLang: String?,
    /** Set by the user on the result page; wins over [detectedLang]. */
    @JsonProperty("langOverride") val langOverride: String?,
    @JsonProperty("importedAt") val importedAt: Long,
) {
    /** The source language the pipeline uses (override, else detected), or null. */
    fun lang(): String? = SourceLanguageDetector.effective(detectedLang, langOverride)
}

/**
 * Imported books (EPUB / TXT) in the translation pipeline: their translator id, record, language
 * and chapter sources. The book file is the import's `local_epub.epub` (TXT imports are converted
 * to an EPUB on import), read through [EpubChapters] exactly like the reader reads it.
 */
object ImportedBooks {
    private const val TAG = "ImportedBooks"
    private const val FOLDER = "imported_books"

    /** Chapters read to detect the language. */
    private const val LANGUAGE_SAMPLE_CHAPTERS = 8
    private const val LANGUAGE_SAMPLE_CHARS = 6000

    const val FORMAT_EPUB = "epub"
    const val FORMAT_TXT = "txt"

    /** The library's provider name for imports. */
    val apiName: String get() = BookDownloader2Helper.IMPORT_SOURCE

    fun isImported(novelId: String?): Boolean = ImportedBookId.isImported(novelId)

    fun novelIdOf(name: String, author: String?): String = ImportedBookId.of(name, author)

    fun record(novelId: String): ImportedBookRecord? = try {
        getKey<ImportedBookRecord>(FOLDER, novelId)
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable record for $novelId", e)
        null
    }

    private fun save(record: ImportedBookRecord) = setKey(FOLDER, record.novelId, record)

    fun remove(novelId: String) {
        removeKey(FOLDER, novelId)
        synchronized(openBooks) { openBooks.remove(novelId) }
    }

    /** The language the pipeline uses for [novelId], or null (unknown book or undecided). */
    fun lang(novelId: String): String? = record(novelId)?.lang()

    /** Manual language choice from the result page (null clears it). */
    fun setLangOverride(novelId: String, lang: String?) {
        val r = record(novelId) ?: return
        save(r.copy(langOverride = lang))
    }

    /** The stored book file of an import. */
    fun epubFile(ctx: Context, name: String, author: String?): File {
        val dir = BookDownloader2Helper.getDirectory(
            BookDownloader2Helper.sanitizeFilename(apiName),
            BookDownloader2Helper.sanitizeFilename(author ?: ""),
            BookDownloader2Helper.sanitizeFilename(name),
        )
        return File(ctx.filesDir.toString() + dir, com.lagradost.quicknovel.BookDownloader2.LOCAL_EPUB)
    }

    fun coverFile(ctx: Context, name: String, author: String?): File = File(
        ctx.filesDir,
        BookDownloader2Helper.getFilenameIMG(
            BookDownloader2Helper.sanitizeFilename(apiName),
            BookDownloader2Helper.sanitizeFilename(author ?: ""),
            BookDownloader2Helper.sanitizeFilename(name),
        )
    )

    // ---- opened books ----

    private class Opened(val path: String, val modified: Long, val chapters: EpubChapters)

    /** Recently opened books by novel id (the service reads many chapters of one book in a row). */
    private val openBooks = object : LinkedHashMap<String, Opened>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Opened>?) = size > 2
    }

    private fun openEpub(file: File): EpubBook {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return EpubReader().readEpubLazy(AndroidZipFile(fd, file.name), "utf-8")
    }

    /** The book's chapters (cached while the file is unchanged), or null if the file is missing. */
    fun chapters(ctx: Context, record: ImportedBookRecord): EpubChapters? {
        val file = epubFile(ctx, record.name, record.author)
        if (!file.exists()) return null
        synchronized(openBooks) {
            openBooks[record.novelId]?.let { if (it.path == file.path && it.modified == file.lastModified()) return it.chapters }
        }
        return try {
            val chapters = EpubChapters(openEpub(file))
            synchronized(openBooks) { openBooks[record.novelId] = Opened(file.path, file.lastModified(), chapters) }
            chapters
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${file.path}", e)
            null
        }
    }

    // ---- pipeline access ----

    /** Chapter sources of an imported book for the translation pipeline, or null if it is not one. */
    fun textSource(ctx: Context, novelId: String): ChapterTextSource? {
        if (!isImported(novelId)) return null
        val record = record(novelId) ?: return null
        val appCtx = ctx.applicationContext
        return object : ChapterTextSource {
            override val lang: String? = record.lang()
            override val size: Int get() = chapters(appCtx, record)?.size ?: record.chapterCount
            override suspend fun load(index: Int): String? = withContext(Dispatchers.IO) {
                chapters(appCtx, record)?.takeIf { index in 0 until it.size }?.html(index)?.takeIf { it.isNotBlank() }
            }
        }
    }

    /** The chapter list for the result page and the pipeline's chapter lists. */
    fun chapterList(ctx: Context, record: ImportedBookRecord): List<ChapterData> {
        val chapters = chapters(ctx, record) ?: return emptyList()
        return (0 until chapters.size).map { i -> ChapterData(chapters.title(i) ?: "Chapter ${i + 1}", ImportedBookId.chapterUrl(i)) }
    }

    /** The result page's view of an imported book (chapters from the EPUB, cover from the import). */
    fun loadResponse(ctx: Context, record: ImportedBookRecord): StreamResponse {
        val cover = coverFile(ctx, record.name, record.author)
        return StreamResponse(
            url = ImportedBookId.resultUrl(record.novelId),
            name = record.name,
            data = chapterList(ctx, record),
            apiName = apiName,
            author = record.author,
            posterUrl = if (cover.exists()) Uri.fromFile(cover).toString() else null,
            synopsis = record.synopsis,
        )
    }

    // ---- import ----

    /**
     * Records an import (called after the book file was written). Detects the language, keeps a
     * manual language choice from an earlier import, and when the file changed since the last
     * import, compares every stored translation with its chapter's new source: changed chapters
     * are flagged stale ("Source changed"), exactly as for an online chapter whose page changed.
     */
    suspend fun register(ctx: Context, name: String, author: String?, format: String): ImportedBookRecord? = withContext(Dispatchers.IO) {
        val novelId = novelIdOf(name, author)
        val file = epubFile(ctx, name, author)
        if (!file.exists()) return@withContext null
        val old = record(novelId)
        val hash = ImportedBookId.contentHash(file.readBytes())
        synchronized(openBooks) { openBooks.remove(novelId) }
        val book = try {
            openEpub(file)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the imported book ${file.path}", e)
            return@withContext null
        }
        val chapters = EpubChapters(book)
        val record = ImportedBookRecord(
            novelId = novelId,
            name = name,
            author = author,
            format = format,
            contentHash = hash,
            epubTitle = book.title,
            synopsis = book.metadata.descriptions?.firstOrNull { !it.isNullOrBlank() },
            chapterCount = chapters.size,
            detectedLang = SourceLanguageDetector.detect(languageSample(chapters, name)),
            langOverride = old?.langOverride,
            importedAt = System.currentTimeMillis(),
        )
        save(record)
        synchronized(openBooks) { openBooks[novelId] = Opened(file.path, file.lastModified(), chapters) }
        if (old != null && old.contentHash != hash) recheckTranslations(ctx, novelId, chapters)
        Log.i(TAG, "Imported $novelId: ${chapters.size} chapters, language ${record.detectedLang ?: "undecided"}")
        record
    }

    /** The record of a book imported before imported books could be translated (created on first use). */
    suspend fun ensureRecord(ctx: Context, name: String, author: String?): ImportedBookRecord? =
        record(novelIdOf(name, author)) ?: register(ctx, name, author, FORMAT_EPUB)

    private fun languageSample(chapters: EpubChapters, title: String): String {
        val sb = StringBuilder(title).append('\n')
        for (i in 0 until minOf(chapters.size, LANGUAGE_SAMPLE_CHAPTERS)) {
            if (sb.length >= LANGUAGE_SAMPLE_CHARS) break
            sb.append(TranslationEngine.htmlToPlainText(chapters.html(i)).take(LANGUAGE_SAMPLE_CHARS)).append('\n')
        }
        return sb.toString()
    }

    private suspend fun recheckTranslations(ctx: Context, novelId: String, chapters: EpubChapters) {
        try {
            val dao = TranslatorDatabase.getInstance(ctx).translationDao()
            for (i in dao.getTranslatedChapterNums(novelId)) {
                if (i < chapters.size) {
                    ChapterTranslationRepository.checkSourceHash(ctx, novelId, i, chapters.html(i))
                } else {
                    dao.setStale(novelId, i, true) // the chapter is gone from the new file
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not re-check the translations of $novelId", e)
        }
    }

    // ---- TXT ----

    /** A TXT book as an EPUB: one section per chapter ([TxtChapterSplitter]), paragraphs as <p>. */
    fun txtToEpub(title: String, text: String): ByteArray {
        val chapters = TxtChapterSplitter.split(text)
        if (chapters.isEmpty()) throw IllegalArgumentException("The text file is empty")
        val book = EpubBook()
        book.metadata.addTitle(title)
        book.resources.add(BookDownloader2Helper.getGeneralCSStyleForEPUBs())
        chapters.forEachIndexed { i, chapter ->
            val body = buildString {
                append("<h3>").append(Entities.escape(chapter.title)).append("</h3>\n")
                for (p in chapter.paragraphs) append("<p>").append(Entities.escape(p)).append("</p>\n")
            }
            val resource = Resource(
                "chapter$i",
                packageAsXHtml(Entities.escape(chapter.title), body).toByteArray(Charsets.UTF_8),
                "chapter$i.html",
                MediaTypes.XHTML,
            )
            book.addSection(chapter.title, resource)
        }
        val out = ByteArrayOutputStream()
        EpubWriter().write(book, out)
        return out.toByteArray()
    }
}
