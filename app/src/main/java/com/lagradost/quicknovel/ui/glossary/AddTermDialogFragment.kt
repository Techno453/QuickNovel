package com.lagradost.quicknovel.ui.glossary

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.DialogAddGlossaryTermBinding
import com.lagradost.quicknovel.translator.TermAliases
import com.lagradost.quicknovel.translator.db.GlossaryTerm

/** Add / edit a Story Bible entry: source, English, category, gender, aliases, notes, locked. */
class AddTermDialogFragment : DialogFragment() {
    private val viewModel: GlossaryViewModel by activityViewModels()

    companion object {
        private const val ARG_TERM_ID = "term_id"

        /** Category order shown in pickers (characters first). */
        val CATEGORIES: List<String> = GlossaryTerm.CATEGORIES

        private val GENDER_BUTTONS = mapOf(
            GlossaryTerm.GENDER_MALE to R.id.gender_male,
            GlossaryTerm.GENDER_FEMALE to R.id.gender_female,
            GlossaryTerm.GENDER_NEUTRAL to R.id.gender_neutral,
            GlossaryTerm.GENDER_UNKNOWN to R.id.gender_unknown,
        )

        fun genderForButton(id: Int): String =
            GENDER_BUTTONS.entries.firstOrNull { it.value == id }?.key ?: GlossaryTerm.GENDER_UNKNOWN

        fun buttonForGender(gender: String): Int =
            GENDER_BUTTONS[gender] ?: R.id.gender_unknown

        /** Localized category label for spinners and chips. */
        fun categoryLabel(ctx: Context, category: String): String = ctx.getString(
            when (category) {
                GlossaryTerm.CATEGORY_CHARACTER -> R.string.glossary_cat_character
                "location" -> R.string.glossary_cat_location
                "sect" -> R.string.glossary_cat_sect
                "rank" -> R.string.glossary_cat_rank
                "technique" -> R.string.glossary_cat_technique
                "artifact" -> R.string.glossary_cat_artifact
                "creature" -> R.string.glossary_cat_creature
                "title" -> R.string.glossary_cat_title
                else -> R.string.glossary_cat_other
            }
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

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, CATEGORIES.map { categoryLabel(ctx, it) })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.termCategorySpinner.adapter = adapter

        if (existingTerm != null) {
            binding.termChineseInput.setText(existingTerm.source)
            binding.termEnglishInput.setText(existingTerm.english)
            binding.termNotesInput.setText(existingTerm.effectiveNotes)
            binding.termAliasesInput.setText(TermAliases.toCommaSeparated(existingTerm.aliases))
            val catIndex = CATEGORIES.indexOf(GlossaryTerm.normalizeCategory(existingTerm.category))
            if (catIndex >= 0) binding.termCategorySpinner.setSelection(catIndex)
            binding.termGenderGroup.check(buttonForGender(existingTerm.gender))
            binding.termLockedSwitch.isChecked = existingTerm.locked
        } else {
            binding.termGenderGroup.check(R.id.gender_unknown)
            binding.termLockedSwitch.isChecked = true // entered by the user = confirmed
        }

        return AlertDialog.Builder(ctx)
            .setTitle(if (existingTerm != null) R.string.term_dialog_edit_title else R.string.term_dialog_add_title)
            .setView(binding.root)
            .setPositiveButton(R.string.term_save) { _, _ ->
                val sourceTerm = binding.termChineseInput.text.toString().trim()
                val english = binding.termEnglishInput.text.toString().trim()
                if (sourceTerm.isBlank() || english.isBlank()) return@setPositiveButton

                val category = CATEGORIES[binding.termCategorySpinner.selectedItemPosition.coerceAtLeast(0)]
                val gender = genderForButton(binding.termGenderGroup.checkedButtonId)
                val notes = binding.termNotesInput.text.toString().trim()
                val aliases = TermAliases.fromCommaSeparated(binding.termAliasesInput.text.toString())
                val locked = binding.termLockedSwitch.isChecked

                val base = existingTerm ?: GlossaryTerm(novelId = viewModel.novelId, source = sourceTerm, english = english)
                val term = base.copy(
                    novelId = viewModel.novelId,
                    source = sourceTerm,
                    english = english,
                    category = category,
                    gender = gender,
                    notes = notes,
                    // Notes replace the legacy description once edited here.
                    description = if (notes.isNotEmpty() && base.notes.isBlank()) "" else base.description,
                    aliases = aliases,
                    locked = locked,
                    // Saving through this dialog counts as reviewing an auto-added term.
                    autoAdded = false,
                )
                viewModel.saveTerm(term, existingTerm)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
