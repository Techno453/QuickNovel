package com.lagradost.quicknovel.ui.glossary

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.DialogTranslationStyleBinding
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationStyle

/** Per-novel translation style: name style, honorifics, free-text notes and the story premise. */
class TranslationStyleDialogFragment : DialogFragment() {
    private val viewModel: GlossaryViewModel by activityViewModels()

    private fun nameLabel(style: String): Int = when (style) {
        TranslationStyle.NAME_MEANING -> R.string.style_name_meaning
        TranslationStyle.NAME_MIXED -> R.string.style_name_mixed
        TranslationStyle.NAME_FAMILY_GIVEN -> R.string.style_name_family_given
        TranslationStyle.NAME_GIVEN_FAMILY -> R.string.style_name_given_family
        else -> R.string.style_name_pinyin
    }

    private fun honorificLabel(style: String): Int = when (style) {
        TranslationStyle.HONORIFICS_DROP -> R.string.style_hon_drop
        TranslationStyle.HONORIFICS_ADAPT -> R.string.style_hon_adapt
        else -> R.string.style_hon_keep
    }

    private fun fill(group: RadioGroup, options: List<String>, selected: String, label: (String) -> Int) {
        group.removeAllViews()
        options.forEach { option ->
            val button = RadioButton(group.context).apply {
                id = View.generateViewId()
                text = context.getString(label(option))
                tag = option
            }
            group.addView(button)
            if (option == selected) group.check(button.id)
        }
    }

    private fun selected(group: RadioGroup): String? =
        group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val binding = DialogTranslationStyleBinding.inflate(layoutInflater)
        val lang = viewModel.sourceLang
        val current = viewModel.novelContext.value

        val resolved = TranslationStyle.resolve(
            lang = lang,
            nameStyle = current?.nameStyle,
            honorificStyle = current?.honorificStyle,
            styleNotes = current?.styleNotes,
            fallbackName = SecurePrefs.getDefaultNameStyle(ctx, lang),
            fallbackHonorific = SecurePrefs.getDefaultHonorificStyle(ctx, lang),
        )
        fill(binding.styleNameGroup, TranslationStyle.nameStyleOptions(lang), resolved.nameStyle, ::nameLabel)
        fill(binding.styleHonorificGroup, TranslationStyle.HONORIFIC_OPTIONS, resolved.honorificStyle, ::honorificLabel)
        binding.styleNotesInput.setText(current?.styleNotes.orEmpty())
        binding.stylePremiseInput.setText(current?.premise.orEmpty())

        return AlertDialog.Builder(ctx)
            .setTitle(R.string.style_title)
            .setView(binding.root)
            .setPositiveButton(R.string.term_save) { _, _ ->
                viewModel.saveStyle(
                    nameStyle = selected(binding.styleNameGroup) ?: resolved.nameStyle,
                    honorificStyle = selected(binding.styleHonorificGroup) ?: resolved.honorificStyle,
                    styleNotes = binding.styleNotesInput.text.toString().trim(),
                    premise = binding.stylePremiseInput.text.toString().trim(),
                )
                Toast.makeText(ctx, R.string.style_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
