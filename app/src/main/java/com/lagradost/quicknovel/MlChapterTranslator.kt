package com.lagradost.quicknovel

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import androidx.core.text.getSpans
import androidx.core.text.toSpanned
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.CommonActivity.TAG
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.util.translation.TranslationManager
import com.lagradost.quicknovel.util.translation.models.TranslatorAgents
import io.noties.markwon.image.AsyncDrawableSpan
import java.io.File
import java.security.MessageDigest

/**
 * Machine translation (ML Kit offline or the online agents) of a rendered chapter, paragraph by
 * paragraph, with an on-disk cache in cacheDir (ml_<hash>.<from>_to_<to>.<agent>.txt).
 * Used by [ReadActivityViewModel]; independent of the LLM translation pipeline in translator/.
 */
class MlChapterTranslator(private val translationManager: TranslationManager) {
    private fun hashString(text: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(text)
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun getFinalTranslatedText(spans: ArrayList<TextSpan>, translatedLines: List<String>):Pair<SpannableStringBuilder, ArrayList<TextSpan>>{
        val builder = SpannableStringBuilder()
        val out = ArrayList<TextSpan>()
        spans.forEachIndexed { i, originalSpan ->
            val hasImage = originalSpan.text.getSpans<AsyncDrawableSpan>().isNotEmpty()
            val finalText =
                if (hasImage)
                    originalSpan.text
                else
                    (translatedLines.getOrNull(i)?: return@forEachIndexed).toSpanned()
            val start = builder.length
            builder.append(finalText)
            val end = builder.length
            builder.append('\n')
            out.add(TextSpan(finalText, start, end, originalSpan.index, originalSpan.innerIndex))
        }
        return builder to out
    }


    @Throws(MLException::class)
    suspend fun translate(
        settings: ReadActivityViewModel.MLSettings,
        text: Spanned,
        spans: ArrayList<TextSpan>,
        loading: suspend (Triple<Int, Int, Int>) -> Unit
    ): Pair<Spanned, ArrayList<TextSpan>> {
        try {
            val currentSettings = settings
            if (spans.isEmpty() || currentSettings.isInvalid()) return text to spans
            val textHash = hashString(
                text.trim().toString().toByteArray()
            )
            val agentSuffix = currentSettings.agent.title.ifEmpty { TranslatorAgents.OFFLINE.title }

            //the file
            val filePrefix = "ml_${textHash}.${currentSettings.from}_to_${currentSettings.to}.$agentSuffix"

            // read from cache if it exists
            // we assume that parseTextToSpans is equivalent from restoring from the builder
            // aka out == parseTextToSpans(builder)
            val cachedData = safe {
                context?.cacheDir?.let { dir ->
                    val cache = File(dir, "$filePrefix.txt")
                    if (cache.exists()) {
                        Log.i(TAG, "Cache exists for $filePrefix")
                        val lines = cache.readLines()
                        val (builder, out) = getFinalTranslatedText(spans, lines)
                        return@safe builder to out
                    }
                }
                return@safe null
            }
            if(cachedData != null) return cachedData


            val translatedList = translationManager.translate(
                textList = spans.map { it.text.toString() },
                from = currentSettings.from,
                to = currentSettings.to,
                agent = currentSettings.agent,
                progress = { progress, total ->
                    loading.invoke(Triple(spans[0].index, progress, total))
                }
            )

            val (builder, out) = getFinalTranslatedText(spans, translatedList)

            // atomically write the file by rename
            safe {
                context?.cacheDir?.let {
                    val cache = File(it, "$filePrefix.tmp")
                    cache.writeText(builder.toString())
                    safe { File(it, "$filePrefix.txt").delete() } // just in case
                    cache.renameTo(File(it, "$filePrefix.txt"))
                }
            }

            return builder to out
        } catch (t: Throwable) {
            throw MLException(t)
        }
    }
}
