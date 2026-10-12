package com.lagradost.quicknovel.ui.common

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.translator.MetadataTranslations
import com.lagradost.quicknovel.util.UIHelper.html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds translated titles and synopses into the Views preview sheet. The
 * originals are shown first and swapped when the translation arrives; nothing waits for it.
 * See [MetadataTranslations] and docs/browse.md.
 */
object MetadataTranslationViews {
    /** "Translated" with "original" underneath in a smaller, dimmer line. */
    private fun twoLineTitle(view: TextView, translated: String, original: String): CharSequence {
        val dim = (view.currentTextColor and 0x00FFFFFF) or (0xA0 shl 24)
        return SpannableStringBuilder(translated).apply {
            append('\n')
            val start = length
            append(original)
            setSpan(RelativeSizeSpan(0.72f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(dim), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Preview sheet (long press on a poster): translated title with the original underneath,
     * translated synopsis (the tap-to-read dialog shows the translation too).
     */
    fun bindPreview(
        owner: LifecycleOwner,
        apiName: String,
        name: String,
        synopsis: String?,
        titleView: TextView,
        descriptionView: TextView,
    ) {
        if (MetadataTranslations.langOf(apiName) == null) return
        MetadataTranslations.cachedTitle(apiName, name)?.let { titleView.text = twoLineTitle(titleView, it, name) }
        owner.lifecycleScope.launch {
            if (!withContext(Dispatchers.IO) { MetadataTranslations.anyEnabled(titleView.context) }) {
                titleView.text = name
                return@launch
            }
            val translated = MetadataTranslations.novel(apiName, name, synopsis) ?: return@launch
            translated.title?.let { titleView.text = twoLineTitle(titleView, it, name) }
            translated.synopsis?.let { text ->
                descriptionView.text = text.html()
                descriptionView.setOnClickListener { view ->
                    AlertDialog.Builder(view.context, R.style.AlertDialogCustom)
                        .setMessage(text.html())
                        .setTitle(translated.title ?: name)
                        .show()
                }
            }
        }
    }
}
