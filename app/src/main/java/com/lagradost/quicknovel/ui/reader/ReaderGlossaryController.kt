package com.lagradost.quicknovel.ui.reader

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.ui.glossary.GlossaryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quiet glossary lookup in the reader: nothing is marked in the text; a long-press on a word
 * (or the "Glossary" item of the text selection menu) looks the pressed text up in the novel's
 * Story Bible ([GlossaryLookup]) and opens [GlossaryLookupSheet]. Only for novels that have a
 * translator id (zh/ja/ko sources); elsewhere long-press keeps doing nothing.
 */
class ReaderGlossaryController(
    private val activity: AppCompatActivity,
    private val viewModel: ReadActivityViewModel,
) {
    private val glossary: GlossaryViewModel by lazy {
        ViewModelProvider(activity)[GlossaryViewModel::class.java]
    }

    private var observing = false

    /**
     * The translator novel id of the open book, or null (book not loaded / not translatable, or a
     * Preview, which reads without the glossary).
     */
    val novelId: String? get() = if (viewModel.isPreview) null else viewModel.translatorNovelIdOrNull()

    val isAvailable: Boolean get() = novelId != null

    /** Starts loading the Story Bible once the book is known, so the first lookup is instant. */
    fun prepare() {
        val id = novelId ?: return
        glossary.init(id)
        if (!observing) {
            observing = true
            // Renaming a locked character from the edit dialog: offer to update stored chapters,
            // same as the glossary screen does.
            glossary.renameOffer.observe(activity) { offer ->
                offer ?: return@observe
                glossary.clearRenameOffer()
                AlertDialog.Builder(activity)
                    .setTitle(R.string.rename_apply_title)
                    .setMessage(activity.getString(R.string.rename_apply_message, offer.oldEnglish, offer.chapterCount, offer.newEnglish))
                    .setPositiveButton(activity.getString(R.string.rename_apply_yes, offer.chapterCount)) { _, _ ->
                        val appCtx = activity.applicationContext
                        activity.lifecycleScope.launch {
                            val changed = glossary.applyRename(offer)
                            Toast.makeText(appCtx, appCtx.getString(R.string.rename_applied, changed), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.rename_apply_no, null)
                    .show()
            }
        }
    }

    /** Long-press at [offset] of [text] in chapter [chapter]. @return true if handled */
    fun lookupAt(text: CharSequence, offset: Int, chapter: Int): Boolean {
        val id = novelId ?: return false
        val snapshot = text.toString()
        activity.lifecycleScope.launch {
            val terms = loadTerms(id)
            val match = GlossaryLookup.findAt(snapshot, offset, terms)
            if (match != null) {
                show(match.term.id, snapshot.substring(match.start, match.end), chapter, !match.exact)
            } else {
                val word = GlossaryLookup.wordAt(snapshot, offset) ?: return@launch
                show(null, GlossaryLookup.englishForNewTerm(snapshot, word.first, word.last + 1), chapter, false)
            }
        }
        return true
    }

    /** "Glossary" from the selection menu: [start, end) of [text]. */
    fun lookupSelection(text: CharSequence, start: Int, end: Int, chapter: Int) {
        val id = novelId ?: run {
            Toast.makeText(activity, R.string.reader_glossary_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = text.toString()
        activity.lifecycleScope.launch {
            val terms = loadTerms(id)
            val match = GlossaryLookup.findForSelection(snapshot, start, end, terms)
            if (match != null) {
                show(match.term.id, snapshot.substring(match.start, match.end), chapter, !match.exact)
            } else {
                val english = GlossaryLookup.englishForNewTerm(snapshot, start, end)
                if (english.isNotBlank()) show(null, english, chapter, false)
            }
        }
    }

    private suspend fun loadTerms(novelId: String) =
        glossary.terms.value?.takeIf { it.isNotEmpty() && glossary.novelId == novelId }
            ?: withContext(Dispatchers.IO) {
                TranslatorDatabase.getInstance(activity).glossaryDao().getAllTermsSync(novelId)
            }

    private fun show(termId: Long?, text: String, chapter: Int, partial: Boolean) {
        if (activity.isFinishing || activity.supportFragmentManager.isStateSaved) return
        prepare()
        activity.supportFragmentManager.findFragmentByTag(GlossaryLookupSheet.TAG)?.let {
            (it as? GlossaryLookupSheet)?.dismissAllowingStateLoss()
        }
        GlossaryLookupSheet.newInstance(termId, text, chapter, partial)
            .show(activity.supportFragmentManager, GlossaryLookupSheet.TAG)
    }
}
