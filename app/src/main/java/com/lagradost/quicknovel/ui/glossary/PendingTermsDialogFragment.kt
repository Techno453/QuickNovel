package com.lagradost.quicknovel.ui.glossary

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.databinding.DialogPendingTermsBinding
import com.lagradost.quicknovel.databinding.ItemPendingTermBinding
import com.lagradost.quicknovel.translator.db.PendingTerm

class PendingTermsDialogFragment : DialogFragment() {
    private val viewModel: GlossaryViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val binding = DialogPendingTermsBinding.inflate(layoutInflater)

        val adapter = PendingTermAdapter(
            onApprove = { term, english, category ->
                viewModel.approvePendingTerm(term, english, category)
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
            .setTitle("Review New Terms")
            .setView(binding.root)
            .setNegativeButton("Done", null)
            .create()
    }

    inner class PendingTermAdapter(
        private val onApprove: (PendingTerm, String, String) -> Unit,
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
                pendingChinese.text = term.chinese
                pendingEnglishInput.setText(term.suggestedEnglish)

                val categories = AddTermDialogFragment.CATEGORIES
                val catAdapter = ArrayAdapter(
                    root.context,
                    android.R.layout.simple_spinner_item,
                    categories
                )
                catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                pendingCategorySpinner.adapter = catAdapter

                val catIdx = categories.indexOf(term.category)
                if (catIdx >= 0) pendingCategorySpinner.setSelection(catIdx)

                btnApprove.setOnClickListener {
                    val english = pendingEnglishInput.text.toString().trim()
                    val category = categories[pendingCategorySpinner.selectedItemPosition]
                    if (english.isNotBlank()) {
                        onApprove(term, english, category)
                    }
                }

                btnReject.setOnClickListener {
                    onReject(term)
                }
            }
        }
    }
}
