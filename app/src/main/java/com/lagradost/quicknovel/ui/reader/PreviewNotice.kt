package com.lagradost.quicknovel.ui.reader

import androidx.annotation.StringRes
import com.lagradost.quicknovel.R

/**
 * The line under a chapter title when the reader is a Preview (result page "Preview": translated
 * one chapter at a time, without the glossary). Same small line as [OriginalNotice]
 * ([OriginalNotice.titleWithNotice]); outside a preview the line is the original notice alone.
 */
enum class PreviewNotice(@StringRes val text: Int) {
    /** A translation made without the glossary (by this preview or an earlier one). */
    WITHOUT_GLOSSARY(R.string.reader_preview_without_glossary),

    /** "Preview": a stored translation made with the glossary, or the original on purpose / after a failure. */
    PREVIEW(R.string.reader_preview),

    /** "Preview · <why the original is shown>" ([Line.reason]). */
    WITH_REASON(R.string.reader_preview_reason),

    /** The original of a preloaded chapter, translated once the reader opens it ([PreviewChapters]). */
    NOT_YET(R.string.reader_preview_not_yet);

    /** The notice and, for [WITH_REASON], the original notice it names. */
    data class Line(val notice: PreviewNotice, val reason: OriginalNotice? = null)

    companion object {
        /**
         * The preview line of a loaded chapter, or null for books that never translate (an
         * English source reads the same in a preview).
         *
         * @param translatable the book translates chapters when opened (zh / ja / ko source)
         * @param translated the chapter is shown translated
         * @param withoutGlossary that translation was made by a preview (no glossary)
         * @param deferred the original is shown because the chapter is not open yet
         * @param original why the original is shown ([OriginalNotice.reasonFor]), if it is
         */
        fun lineFor(
            translatable: Boolean,
            translated: Boolean,
            withoutGlossary: Boolean,
            deferred: Boolean,
            original: OriginalNotice?,
        ): Line? = when {
            !translatable -> null
            translated && withoutGlossary -> Line(WITHOUT_GLOSSARY)
            translated -> Line(PREVIEW)
            original != null -> Line(WITH_REASON, original)
            deferred -> Line(NOT_YET)
            else -> Line(PREVIEW)
        }
    }
}

/** Which chapters a preview translates: only the one the reader has open. */
object PreviewChapters {
    /**
     * True when chapter [index], loaded while [openChapter] is open, is served as the original
     * for now and translated once opened: the reader preloads the chapters around the open one,
     * and a preview pays only for the chapters actually read. Never when opening it would not
     * translate it anyway ([wouldTranslate] false: switch off or no key, the original notice
     * applies), nor before the reader has an open chapter (-1).
     */
    fun defers(wouldTranslate: Boolean, index: Int, openChapter: Int): Boolean =
        wouldTranslate && openChapter >= 0 && index != openChapter
}
