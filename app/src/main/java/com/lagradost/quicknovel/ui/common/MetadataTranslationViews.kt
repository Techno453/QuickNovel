package com.lagradost.quicknovel.ui.common

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.translator.MetadataTranslations
import com.lagradost.quicknovel.util.UIHelper.html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds translated titles and synopses into the Views screens (preview sheet, result page). The
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

    /**
     * Result page: binds [titleView] and [synopsisView] (collapsed to [maxSynopsis] characters,
     * tap to expand) and, once a translation exists, a small "Show original" / "Show translation"
     * toggle added right above the synopsis. (Not under the title: the result page's header sits
     * below its scroll view, whose transparent spacer takes every touch there.)
     */
    fun bindResult(
        owner: LifecycleOwner,
        apiName: String,
        name: String,
        synopsis: String?,
        titleView: TextView,
        synopsisView: TextView,
        maxSynopsis: Int,
    ) {
        var translatedTitle: String? = null
        var translatedSynopsis: String? = null
        var showOriginal = false
        var expanded = false

        fun shorten(text: String) = if (text.length > maxSynopsis) text.substring(0, maxSynopsis) + "..." else text

        fun render() {
            val title = translatedTitle?.takeUnless { showOriginal }
            titleView.text = title ?: name
            val shownSynopsis = translatedSynopsis?.takeUnless { showOriginal } ?: synopsis
            synopsisView.text = when {
                shownSynopsis == null -> "..."
                expanded -> shownSynopsis.html()
                else -> shorten(shownSynopsis).html()
            }
        }

        synopsisView.setOnClickListener {
            expanded = !expanded
            render()
        }
        render()

        if (MetadataTranslations.langOf(apiName) == null) return
        val toggle = toggleAbove(synopsisView)
        toggle?.setOnClickListener {
            showOriginal = !showOriginal
            toggle.setText(if (showOriginal) R.string.browse_show_translation else R.string.browse_show_original)
            render()
        }
        owner.lifecycleScope.launch {
            if (!withContext(Dispatchers.IO) { MetadataTranslations.anyEnabled(titleView.context) }) return@launch
            val translated = MetadataTranslations.novel(apiName, name, synopsis) ?: return@launch
            translatedTitle = translated.title
            translatedSynopsis = translated.synopsis
            if (translatedTitle != null || translatedSynopsis != null) {
                toggle?.setText(R.string.browse_show_original)
                toggle?.isVisible = true
            }
            render()
        }
    }

    /**
     * A small text button right above [anchor] (created once and reused), or null when the
     * anchor is not inside a vertical LinearLayout. Hidden until there is a translation.
     */
    private fun toggleAbove(anchor: TextView): TextView? {
        val parent = anchor.parent as? LinearLayout ?: return null
        if (parent.orientation != LinearLayout.VERTICAL) return null
        parent.findViewWithTag<TextView>(TOGGLE_TAG)?.let { return it }
        val context = anchor.context
        val toggle = TextView(context).apply {
            tag = TOGGLE_TAG
            isVisible = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor((anchor.currentTextColor and 0x00FFFFFF) or (0xB0 shl 24))
            val pad = (4 * resources.displayMetrics.density).toInt()
            // Line up with the anchor's text.
            setPadding(anchor.paddingStart, pad, pad * 2, pad)
            isClickable = true
            isFocusable = true
            val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = attrs.getDrawable(0)
            attrs.recycle()
        }
        parent.addView(
            toggle,
            parent.indexOfChild(anchor),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                (anchor.layoutParams as? ViewGroup.MarginLayoutParams)?.let { marginStart = it.marginStart }
            },
        )
        return toggle
    }

    private const val TOGGLE_TAG = "metadata_translation_toggle"
}
