package com.lagradost.quicknovel.ui.translate

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.DOWNLOAD_EPUB_SIZE
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.translator.NameReplacer
import com.lagradost.quicknovel.util.GoogleTranslate
import kotlinx.coroutines.CancellationException
import java.io.File

/** Writes translated chapters as .txt so they appear in the offline epub (batch jobs and range jobs). */
object TranslatedChapterFiles {
    private const val TAG = "TranslatedChapterFiles"

    suspend fun save(
        ctx: Context,
        apiName: String,
        novelAuthor: String?,
        novelName: String,
        chapterIdx: Int,
        chapterName: String,
        englishHtml: String,
    ) {
        // Imported books keep their own EPUB; the reader serves their translations from the DB.
        if (apiName == BookDownloader2Helper.IMPORT_SOURCE) return
        try {
            val sApiName = BookDownloader2Helper.sanitizeFilename(apiName)
            val sAuthor = BookDownloader2Helper.sanitizeFilename(novelAuthor ?: "")
            val sName = BookDownloader2Helper.sanitizeFilename(novelName)
            val file = File(ctx.filesDir.toString() + BookDownloader2Helper.getFilename(sApiName, sAuthor, sName, chapterIdx))
            file.parentFile?.mkdirs()
            val translatedChapterName = GoogleTranslate.translate(chapterName)
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
     * Applies a glossary rename ([NameReplacer]) to the chapter .txt files of [novelName] on
     * [apiName], whatever the author directory, so the offline epub matches the database. The
     * epub is regenerated on next open. Returns the number of files changed.
     */
    fun replaceInFiles(ctx: Context, apiName: String, novelName: String, old: String, new: String): Int {
        if (apiName == BookDownloader2Helper.IMPORT_SOURCE || old.isBlank() || old == new) return 0
        val sApiName = BookDownloader2Helper.sanitizeFilename(apiName)
        val sName = BookDownloader2Helper.sanitizeFilename(novelName)
        val apiDir = File(ctx.filesDir, sApiName)
        // With an author: files/<api>/<author>/<name>/N.txt; without: files/<api>/<name>/N.txt.
        val candidates = apiDir.listFiles().orEmpty().filter { it.isDirectory }
            .map { dir -> if (dir.name == sName) dir to "" else File(dir, sName) to dir.name }
            .filter { it.first.isDirectory }
        var changed = 0
        for ((novelDir, author) in candidates) {
            var touched = false
            for (file in novelDir.listFiles().orEmpty()) {
                if (!file.name.endsWith(".txt")) continue
                try {
                    val text = file.readText()
                    val updated = NameReplacer.replace(text, old, new)
                    if (updated != text) {
                        file.writeText(updated)
                        changed++
                        touched = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not update ${file.name}: ${e.message}")
                }
            }
            if (touched) {
                val epubId = BookDownloader2Helper.generateId(apiName, author.ifEmpty { null }, novelName)
                ctx.setKey(DOWNLOAD_EPUB_SIZE, epubId.toString(), -1)
            }
        }
        return changed
    }
}
