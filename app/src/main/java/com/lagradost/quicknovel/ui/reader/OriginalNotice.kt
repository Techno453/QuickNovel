package com.lagradost.quicknovel.ui.reader

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.annotation.StringRes
import com.lagradost.quicknovel.R

/**
 * Why the reader shows the original of a chapter it could translate (zh / ja / ko sources and
 * imported books with a known language). Shown as one small line under the chapter title, in
 * scroll and paged mode. Never set when the user asked for the original (Reload / Show original).
 */
enum class OriginalNotice(@StringRes val text: Int) {
    /** "Translate chapters when opened" is on but there is no API key. */
    NO_API_KEY(R.string.translator_no_api_key),

    /** "Translate chapters when opened" is off. */
    TRANSLATION_OFF(R.string.tr_reader_original_notice);

    companion object {
        /**
         * The notice for a chapter served as the original. Mirrors the order of the checks in
         * `ChapterTranslationRepository.getOrTranslate`: the switch first, then the key. A failed
         * translation (switch on, key set) gets no notice.
         */
        fun reasonFor(translatable: Boolean, onPurpose: Boolean, onDemand: Boolean, hasKey: Boolean): OriginalNotice? =
            when {
                !translatable || onPurpose -> null
                !onDemand -> TRANSLATION_OFF
                !hasKey -> NO_API_KEY
                else -> null
            }

        /** [title] followed by [notice] on its own line, smaller, regular weight and faded. */
        fun titleWithNotice(title: CharSequence, notice: CharSequence?, sizeFactor: Float = 0.75f): CharSequence {
            if (notice.isNullOrBlank()) return title
            val out = SpannableStringBuilder(title).append('\n')
            val start = out.length
            out.append(notice)
            out.setSpan(NoticeSpan(sizeFactor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return out
        }
    }

    /** Smaller, not bold and at 65 % opacity of whatever colour the title view uses. */
    private class NoticeSpan(private val sizeFactor: Float) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) = apply(paint)

        override fun updateDrawState(paint: TextPaint) {
            apply(paint)
            paint.alpha = (paint.alpha * 0.65f).toInt()
        }

        private fun apply(paint: TextPaint) {
            paint.textSize *= sizeFactor
            paint.isFakeBoldText = false
            paint.typeface = Typeface.create(paint.typeface, Typeface.NORMAL)
        }
    }
}
