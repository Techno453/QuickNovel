package com.lagradost.quicknovel.ui.glossary

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.DialogAnalyzeNovelBinding
import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.ui.translate.NovelChapterSource
import com.lagradost.quicknovel.ui.translate.TranslationForegroundService
import kotlinx.coroutines.launch

/**
 * Confirmation for the "Analyze novel" pre-pass: chapter count (1–20) with a cost estimate.
 * Used from the Glossary menu and from the translate-range dialog hint.
 */
class AnalyzeNovelDialogFragment : DialogFragment() {
    companion object {
        private const val ARG_NOVEL_ID = "novel_id"

        fun newInstance(novelId: String) = AnalyzeNovelDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_NOVEL_ID, novelId) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val binding = DialogAnalyzeNovelBinding.inflate(layoutInflater)
        val novelId = arguments?.getString(ARG_NOVEL_ID).orEmpty()
        val apiName = NovelChapterSource.apiNameOf(novelId)
        val lang = TranslationLanguages.langOfApi(apiName) ?: TranslationLanguages.DEFAULT
        // The "Analysis model" setting (default: the translation model).
        val analysisConfig = SecurePrefs.analysisEngineConfig(ctx)
        val model = analysisConfig.model
        var entry: NovelChapterSource.Entry? = null
        var count = SecurePrefs.getAnalyzeChapterCount(ctx)

        fun update() {
            binding.analyzeCountLabel.text = getString(R.string.analyze_chapters, count)
            // Analysis makes no strict-mode retries; the estimate depends on the model and source language.
            val estimate = CostEstimator.estimateAnalysis(count, model, lang)
            binding.analyzeEstimate.text = getString(R.string.analyze_estimate, CostEstimator.formatEstimate(estimate), model)
        }

        binding.analyzeCountSlider.value = count.toFloat()
        binding.analyzeCountSlider.addOnChangeListener { _, value, _ ->
            count = value.toInt()
            update()
        }
        binding.analyzeStatus.setText(R.string.analyze_loading)
        update()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.analyze_title)
            .setView(binding.root)
            .setPositiveButton(R.string.analyze_start, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            start.isEnabled = false
            lifecycleScope.launch {
                entry = NovelChapterSource.resolve(novelId)
                val chapters = entry?.chapters.orEmpty()
                if (chapters.isEmpty()) {
                    binding.analyzeStatus.setText(R.string.analyze_no_source)
                    return@launch
                }
                binding.analyzeStatus.visibility = View.GONE
                val max = minOf(SecurePrefs.MAX_ANALYZE_CHAPTERS, chapters.size)
                if (max <= 1) {
                    count = 1
                    binding.analyzeCountSlider.visibility = View.GONE
                } else {
                    count = count.coerceIn(1, max)
                    binding.analyzeCountSlider.valueTo = max.toFloat()
                    binding.analyzeCountSlider.value = count.toFloat()
                }
                update()
                start.isEnabled = true
            }
            start.setOnClickListener {
                val e = entry ?: return@setOnClickListener
                if (analysisConfig.apiKey.isBlank()) {
                    Toast.makeText(ctx, R.string.translator_no_api_key, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                SecurePrefs.setAnalyzeChapterCount(ctx, count)
                val started = TranslationForegroundService.startAnalysis(appCtx, novelId, e.apiName, e.chapters, count)
                if (!started) Toast.makeText(ctx, R.string.translator_busy, Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
        }
        return dialog
    }
}
