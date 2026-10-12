package com.lagradost.quicknovel.ui.reader

import android.app.Dialog
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.ReadGlossaryLookupBinding
import com.lagradost.quicknovel.translator.TermAliases
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm
import com.lagradost.quicknovel.ui.glossary.AddTermDialogFragment
import com.lagradost.quicknovel.ui.glossary.GlossaryViewModel
import com.lagradost.quicknovel.util.AdaptiveLayout.adaptToWindow
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute

/**
 * Small bottom sheet shown by a long-press (or the "Glossary" selection action) in the reader:
 * the Story Bible entry under the finger with Edit / Lock and the pending suggestions for it, or
 * "Add to glossary" prefilled with the pressed text. Backed by the activity's GlossaryViewModel,
 * so edits made here (and in the dialogs it opens) show up immediately.
 */
class GlossaryLookupSheet : BottomSheetDialogFragment() {
    companion object {
        private const val ARG_TERM_ID = "term_id"
        private const val ARG_TEXT = "text"
        private const val ARG_CHAPTER = "chapter"
        private const val ARG_PARTIAL = "partial"
        const val TAG = "glossary_lookup"

        /** [termId] null = no match; [text] is the matched rendering or the pressed text. */
        fun newInstance(termId: Long?, text: String, chapter: Int, partial: Boolean) =
            GlossaryLookupSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_TERM_ID, termId ?: -1L)
                    putString(ARG_TEXT, text)
                    putInt(ARG_CHAPTER, chapter)
                    putBoolean(ARG_PARTIAL, partial)
                }
            }
    }

    private val glossary: GlossaryViewModel by activityViewModels()
    private var binding: ReadGlossaryLookupBinding? = null

    private val termId get() = arguments?.getLong(ARG_TERM_ID, -1L)?.takeIf { it > 0 }
    private val text get() = arguments?.getString(ARG_TEXT).orEmpty()
    private val chapter get() = arguments?.getInt(ARG_CHAPTER, 0) ?: 0
    private val partial get() = arguments?.getBoolean(ARG_PARTIAL, false) ?: false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // max width on wide windows (foldable inner screen), expanded on wide/short windows
        return (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            adaptToWindow()
            // small sheet: always fully open so Edit / Lock are visible without dragging
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = ReadGlossaryLookupBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        glossary.loadPendingTerms()
        glossary.terms.observe(viewLifecycleOwner) { render() }
        glossary.pendingTerms.observe(viewLifecycleOwner) { render() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render() {
        val b = binding ?: return
        val id = termId
        val term = id?.let { tid -> glossary.terms.value?.firstOrNull { it.id == tid } }
        if (term == null) {
            renderNotFound(b)
        } else {
            renderTerm(b, term, GlossaryLookup.pendingFor(term, glossary.pendingTerms.value.orEmpty()))
        }
    }

    private fun renderNotFound(b: ReadGlossaryLookupBinding) {
        b.lookupTitle.text = getString(R.string.reader_glossary_not_found_title)
        b.lookupStatus.text = getString(R.string.reader_glossary_not_found, text)
        b.lookupFields.removeAllViews()
        b.lookupPending.removeAllViews()
        b.lookupPendingTitle.isVisible = false
        b.lookupTermActions.isVisible = false
        b.lookupAdd.isVisible = true
        b.lookupAdd.setOnClickListener {
            // Untranslated CJK selected: it is the source; otherwise the English rendering.
            val cjk = text.any { GlossaryLookup.isCjk(it) }
            AddTermDialogFragment.newPrefilled(
                english = text.takeUnless { cjk },
                source = text.takeIf { cjk },
                firstChapter = chapter,
            ).show(parentFragmentManager, "add_term")
            dismiss()
        }
    }

    private fun renderTerm(b: ReadGlossaryLookupBinding, term: GlossaryTerm, pending: List<PendingTerm>) {
        val ctx = b.root.context
        b.lookupTitle.text = term.english.ifBlank { term.source }
        b.lookupStatus.text = listOfNotNull(
            AddTermDialogFragment.categoryLabel(ctx, GlossaryTerm.normalizeCategory(term.category)),
            getString(R.string.reader_glossary_locked).takeIf { term.locked },
            getString(R.string.reader_glossary_auto_added).takeIf { term.autoAdded && !term.locked },
            getString(R.string.reader_glossary_partial).takeIf { partial },
        ).joinToString(" · ")

        b.lookupFields.removeAllViews()
        fun field(label: Int, value: String?) {
            if (value.isNullOrBlank()) return
            b.lookupFields.addView(fieldRow(ctx, getString(label), value))
        }
        field(R.string.reader_glossary_source, term.source)
        field(R.string.reader_glossary_english, term.english)
        // gender matters for characters (pronouns); for places and items only when set
        if (term.isCharacter || GlossaryTerm.normalizeGender(term.gender) != GlossaryTerm.GENDER_UNKNOWN) {
            field(R.string.reader_glossary_gender, genderLabel(term.gender))
        }
        field(R.string.reader_glossary_category, AddTermDialogFragment.categoryLabel(ctx, GlossaryTerm.normalizeCategory(term.category)))
        field(R.string.reader_glossary_aliases, TermAliases.toCommaSeparated(term.aliases))
        field(R.string.reader_glossary_notes, term.effectiveNotes)
        field(R.string.reader_glossary_first_chapter, term.firstChapter?.let { (it + 1).toString() } ?: getString(R.string.reader_glossary_unknown))

        b.lookupPending.removeAllViews()
        b.lookupPendingTitle.isVisible = pending.isNotEmpty()
        for (p in pending) b.lookupPending.addView(pendingRow(ctx, term, p))

        b.lookupAdd.isVisible = false
        b.lookupTermActions.isVisible = true
        b.lookupEdit.setOnClickListener {
            AddTermDialogFragment.newInstance(term).show(parentFragmentManager, "edit_term")
        }
        b.lookupLock.text = getString(if (term.locked) R.string.reader_glossary_unlock else R.string.reader_glossary_lock)
        b.lookupLock.setIconResource(R.drawable.ic_baseline_lock_24)
        b.lookupLock.setOnClickListener {
            if (term.locked) {
                glossary.saveTerm(term.copy(locked = false), term)
            } else {
                glossary.lockTerms(listOf(term))
            }
        }
    }

    private fun genderLabel(gender: String): String = getString(
        when (GlossaryTerm.normalizeGender(gender)) {
            GlossaryTerm.GENDER_MALE -> R.string.gender_male
            GlossaryTerm.GENDER_FEMALE -> R.string.gender_female
            GlossaryTerm.GENDER_NEUTRAL -> R.string.gender_neutral
            else -> R.string.gender_unknown
        }
    )

    private fun fieldRow(ctx: android.content.Context, label: String, value: String): View {
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(ctx.colorFromAttribute(R.attr.grayTextColor))
                layoutParams = LinearLayout.LayoutParams((110 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(ctx).apply {
                text = value
                textSize = 15f
                setTextColor(ctx.colorFromAttribute(R.attr.textColor))
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    /** One pending suggestion with Approve / Dismiss (same logic as the pending-terms dialog). */
    private fun pendingRow(ctx: android.content.Context, term: GlossaryTerm, p: PendingTerm): View {
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, 0)
            addView(TextView(ctx).apply {
                text = getString(R.string.reader_glossary_pending_item, p.source, p.suggestedEnglish, p.chapterNum + 1)
                textSize = 15f
                setTextColor(ctx.colorFromAttribute(R.attr.textColor))
            })
            if (p.notes.isNotBlank()) {
                addView(TextView(ctx).apply {
                    text = p.notes
                    textSize = 13f
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ctx.colorFromAttribute(R.attr.grayTextColor))
                })
            }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val approve = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.pending_approve)
                    setOnClickListener {
                        // Approving a conflicting rendering updates the existing entry (same source);
                        // other suggestions become their own locked entries.
                        val category = if (p.source == term.source) GlossaryTerm.normalizeCategory(term.category)
                        else GlossaryTerm.normalizeCategory(p.category)
                        val gender = if (p.source == term.source && GlossaryTerm.normalizeGender(p.gender) == GlossaryTerm.GENDER_UNKNOWN)
                            term.gender else GlossaryTerm.normalizeGender(p.gender)
                        glossary.approvePendingTerm(p, p.suggestedEnglish, category, gender, p.notes)
                    }
                }
                val dismiss = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.reader_glossary_dismiss)
                    setOnClickListener { glossary.rejectPendingTerm(p) }
                }
                addView(approve)
                addView(dismiss)
            })
        }
    }
}
