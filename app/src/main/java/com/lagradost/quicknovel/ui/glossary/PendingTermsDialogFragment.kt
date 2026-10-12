package com.lagradost.quicknovel.ui.glossary

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.DialogPendingTermsBinding
import com.lagradost.quicknovel.databinding.ItemPendingTermBinding
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm

/** Review terms the translator was unsure about (or that conflict with the bible). */
class PendingTermsDialogFragment : DialogFragment() {
    private val viewModel: GlossaryViewModel by activityViewModels()

    private val genderLabels = listOf(
        GlossaryTerm.GENDER_MALE to R.string.gender_male,
        GlossaryTerm.GENDER_FEMALE to R.string.gender_female,
        GlossaryTerm.GENDER_NEUTRAL to R.string.gender_neutral,
        GlossaryTerm.GENDER_UNKNOWN to R.string.gender_unknown,
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val binding = DialogPendingTermsBinding.inflate(layoutInflater)

        val adapter = PendingTermAdapter(
            onApprove = { term, english, category, gender, notes ->
                viewModel.approvePendingTerm(term, english, category, gender, notes)
            },
            onReject = { term ->
                viewModel.rejectPendingTerm(term)
            }
        )

        binding.pendingTermsList.layoutManager = LinearLayoutManager(ctx)
        binding.pendingTermsList.adapter = adapter

        viewModel.pendingTerms.observe(this) { pending ->
            adapter.submitList(pending)
            if (pending.isEmpty()) dismiss()
        }

        return AlertDialog.Builder(ctx)
            .setTitle(R.string.glossary_review_new_terms)
            .setView(binding.root)
            .setPositiveButton(R.string.pending_done, null)
            .setNeutralButton(R.string.pending_approve_all) { _, _ -> viewModel.approveAllPending() }
            .setNegativeButton(R.string.pending_reject_all) { _, _ ->
                val count = viewModel.pendingTerms.value?.size ?: 0
                val host = activity ?: return@setNegativeButton
                AlertDialog.Builder(host)
                    .setMessage(host.getString(R.string.pending_reject_all_confirm, count))
                    .setPositiveButton(R.string.pending_reject_all) { _, _ -> viewModel.rejectAllPending() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .create()
    }

    private fun isReviewNote(notes: String) =
        notes.startsWith("conflicts with existing") || notes.startsWith("same English as existing")

    inner class PendingTermAdapter(
        private val onApprove: (PendingTerm, String, String, String, String) -> Unit,
        private val onReject: (PendingTerm) -> Unit
    ) : RecyclerView.Adapter<PendingTermAdapter.VH>() {

        private var items: List<PendingTerm> = emptyList()

        fun submitList(list: List<PendingTerm>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val binding: ItemPendingTermBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemPendingTermBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val term = items[position]
            holder.binding.apply {
                val ctx = root.context
                pendingChinese.text = term.source
                pendingMeta.text = ctx.getString(
                    R.string.pending_meta, term.chapterNum + 1, (term.confidence * 100).toInt().coerceIn(0, 100)
                )
                pendingEnglishInput.setText(term.suggestedEnglish)

                // Conflict / alias hints are shown read-only; real notes are editable.
                val reviewNote = term.notes.takeIf { isReviewNote(it) }
                pendingNotes.text = reviewNote.orEmpty()
                pendingNotes.visibility = if (reviewNote != null) View.VISIBLE else View.GONE
                pendingNotesInput.setText(if (reviewNote != null) reviewNote.substringAfter("; ", "") else term.notes)

                val categories = AddTermDialogFragment.CATEGORIES
                val catAdapter = ArrayAdapter(
                    ctx,
                    android.R.layout.simple_spinner_item,
                    categories.map { AddTermDialogFragment.categoryLabel(ctx, it) }
                )
                catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                pendingCategorySpinner.adapter = catAdapter
                val catIdx = categories.indexOf(GlossaryTerm.normalizeCategory(term.category))
                if (catIdx >= 0) pendingCategorySpinner.setSelection(catIdx)

                val genderAdapter = ArrayAdapter(
                    ctx,
                    android.R.layout.simple_spinner_item,
                    genderLabels.map { ctx.getString(it.second) }
                )
                genderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                pendingGenderSpinner.adapter = genderAdapter
                val genderIdx = genderLabels.indexOfFirst { it.first == GlossaryTerm.normalizeGender(term.gender) }
                if (genderIdx >= 0) pendingGenderSpinner.setSelection(genderIdx)

                btnApprove.setOnClickListener {
                    val english = pendingEnglishInput.text.toString().trim()
                    val category = categories[pendingCategorySpinner.selectedItemPosition.coerceAtLeast(0)]
                    val gender = genderLabels[pendingGenderSpinner.selectedItemPosition.coerceAtLeast(0)].first
                    val notes = pendingNotesInput.text.toString().trim()
                    if (english.isNotBlank()) {
                        onApprove(term, english, category, gender, notes)
                    }
                }

                btnReject.setOnClickListener {
                    onReject(term)
                }
            }
        }
    }
}
