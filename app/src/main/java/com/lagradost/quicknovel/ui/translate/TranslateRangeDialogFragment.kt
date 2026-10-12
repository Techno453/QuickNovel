package com.lagradost.quicknovel.ui.translate

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.DialogTranslateRangeBinding
import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.ui.glossary.AnalyzeNovelDialogFragment
import kotlinx.coroutines.launch

class TranslateRangeDialogFragment : DialogFragment() {
    private val viewModel: TranslateViewModel by activityViewModels()

    companion object {
        // Temporary in-memory store to avoid needing Parcelable for ChapterData
        private var pendingChapters: List<ChapterData>? = null

        fun newInstance(
            novelId: String,
            apiName: String,
            chapters: List<ChapterData>,
            novelAuthor: String? = null,
            novelName: String? = null
        ): TranslateRangeDialogFragment {
            pendingChapters = chapters
            return TranslateRangeDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("novel_id", novelId)
                    putString("api_name", apiName)
                    putInt("chapter_count", chapters.size)
                    putString("novel_author", novelAuthor)
                    putString("novel_name", novelName)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val binding = DialogTranslateRangeBinding.inflate(layoutInflater)

        val novelId = arguments?.getString("novel_id") ?: ""
        val apiName = arguments?.getString("api_name") ?: ""
        val total = arguments?.getInt("chapter_count") ?: 0
        val novelAuthor = arguments?.getString("novel_author")
        val novelName = arguments?.getString("novel_name")
        val chapters = pendingChapters ?: emptyList()
        val sourceLang = TranslationLanguages.langOfApi(apiName) ?: TranslationLanguages.DEFAULT

        viewModel.loadTranslatedNums(novelId)
        NovelChapterSource.remember(novelId, apiName, novelName.orEmpty(), novelAuthor, chapters)

        // Story Bible status; suggest the analysis pre-pass when there are no characters yet.
        lifecycleScope.launch {
            val dao = TranslatorDatabase.getInstance(ctx).glossaryDao()
            val characters = dao.countCharacters(novelId)
            val totalTerms = dao.countForNovel(novelId)
            binding.bibleStatus.text = getString(R.string.bible_status, characters, totalTerms - characters)
            val showHint = characters == 0 && chapters.isNotEmpty()
            binding.bibleAnalyzeHint.visibility = if (showHint) View.VISIBLE else View.GONE
            binding.bibleAnalyzeButton.visibility = if (showHint) View.VISIBLE else View.GONE
        }
        binding.bibleAnalyzeButton.setOnClickListener {
            AnalyzeNovelDialogFragment.newInstance(novelId).show(parentFragmentManager, "analyze_novel")
            dismiss() // translate after the bible is built (only one job runs at a time)
        }

        binding.rangeFrom.setText("1")
        binding.rangeTo.setText(minOf(50, total).toString())
        binding.rangeAvailable.text = "Available: 1–$total"

        fun updateEstimates() {
            val from = (binding.rangeFrom.text.toString().toIntOrNull() ?: 1) - 1
            val to = (binding.rangeTo.text.toString().toIntOrNull() ?: 1) - 1
            val alreadyTranslated = viewModel.translatedChapterNums.value ?: emptyList()
            val rangeIndices = (from..to).toSet()
            val alreadyDone = alreadyTranslated.filter { it in rangeIndices }.size
            val toTranslate = maxOf(0, rangeIndices.size - alreadyDone)

            binding.alreadyTranslated.text = if (alreadyDone > 0)
                "Already translated: $alreadyDone chapters (will be skipped)"
            else ""
            binding.alreadyTranslated.visibility = if (alreadyDone > 0) View.VISIBLE else View.GONE

            binding.chaptersToTranslate.text = "Chapters to translate: $toTranslate"

            val model = SecurePrefs.getModel(ctx)
            val estimate = CostEstimator.estimateForChapters(toTranslate, model, sourceLang)
            binding.estimatedCost.text = "Est. cost: ${CostEstimator.formatEstimate(estimate)} — $model, ${TranslationLanguages.displayName(sourceLang)} → English"
        }

        viewModel.translatedChapterNums.observe(this) { updateEstimates() }
        binding.rangeFrom.doOnTextChanged { _, _, _, _ -> updateEstimates() }
        binding.rangeTo.doOnTextChanged { _, _, _, _ -> updateEstimates() }
        updateEstimates()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Translate Chapters")
            .setView(binding.root)
            .setPositiveButton("Start", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = binding.rangeFrom.text.toString().toIntOrNull()
                val to = binding.rangeTo.text.toString().toIntOrNull()

                if (from == null || to == null || from < 1 || to < from || to > total) {
                    Toast.makeText(ctx, "Invalid chapter range (1–$total)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (SecurePrefs.getApiKey(ctx).isNullOrBlank()) {
                    Toast.makeText(ctx, R.string.translator_no_api_key, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                if (AnalysisState.isRunning) {
                    Toast.makeText(ctx, R.string.translator_busy, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                viewModel.startTranslation(
                    novelId = novelId,
                    apiName = apiName,
                    chapters = chapters,
                    fromIndex = from - 1,
                    toIndex = to - 1,
                    novelAuthor = novelAuthor,
                    novelName = novelName
                )
                pendingChapters = null
                dialog.dismiss()
            }
        }

        return dialog
    }
}
