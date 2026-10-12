package com.lagradost.quicknovel.ui.translate

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.DOWNLOAD_EPUB_SIZE
import com.lagradost.quicknovel.DataStore.setKey
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
}
