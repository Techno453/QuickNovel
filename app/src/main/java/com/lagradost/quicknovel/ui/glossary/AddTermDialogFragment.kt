package com.lagradost.quicknovel.ui.glossary

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.lagradost.quicknovel.databinding.DialogAddGlossaryTermBinding
import com.lagradost.quicknovel.translator.db.GlossaryTerm

class AddTermDialogFragment : DialogFragment() {
    private val viewModel: GlossaryViewModel by activityViewModels()

    companion object {
        private const val ARG_TERM_ID = "term_id"
        val CATEGORIES = listOf(
            "character", "location", "rank", "technique", "sect", "artifact", "creature", "title", "other"
        )

        fun newInstance(existingTerm: GlossaryTerm? = null) = AddTermDialogFragment().apply {
            if (existingTerm != null) {
                arguments = Bundle().apply {
                    putLong(ARG_TERM_ID, existingTerm.id)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val binding = DialogAddGlossaryTermBinding.inflate(layoutInflater)

        val termId = arguments?.getLong(ARG_TERM_ID, -1L) ?: -1L
        val existingTerm = if (termId > 0) {
            viewModel.terms.value?.find { it.id == termId }
        } else null

        // Populate category spinner
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, CATEGORIES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.termCategorySpinner.adapter = adapter

        existingTerm?.let { term ->
            binding.termChineseInput.setText(term.chinese)
            binding.termEnglishInput.setText(term.english)
            binding.termDescriptionInput.setText(term.description)
            val catIndex = CATEGORIES.indexOf(term.category)
            if (catIndex >= 0) binding.termCategorySpinner.setSelection(catIndex)
        }

        return AlertDialog.Builder(ctx)
            .setTitle(if (existingTerm != null) "Edit Term" else "Add Glossary Term")
            .setView(binding.root)
            .setPositiveButton("Save") { _, _ ->
                val chinese = binding.termChineseInput.text.toString().trim()
                val english = binding.termEnglishInput.text.toString().trim()
                val category = CATEGORIES[binding.termCategorySpinner.selectedItemPosition]
                val description = binding.termDescriptionInput.text.toString().trim()

                if (chinese.isNotBlank() && english.isNotBlank()) {
                    viewModel.addTerm(
                        GlossaryTerm(
                            id = if (existingTerm != null) existingTerm.id else 0,
                            novelId = viewModel.novelId,
                            chinese = chinese,
                            english = english,
                            category = category,
                            description = description
                        )
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
