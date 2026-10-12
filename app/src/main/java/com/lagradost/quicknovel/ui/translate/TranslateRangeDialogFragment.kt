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
import com.lagradost.quicknovel.translator.ChapterTranslationRepository
import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.NovelAnalyzer
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.SpendCaps
import com.lagradost.quicknovel.translator.SpendLedger
import com.lagradost.quicknovel.translator.SpendPricing
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.batch.BatchChapterStatus
import com.lagradost.quicknovel.translator.batch.BatchCost
import com.lagradost.quicknovel.translator.batch.BatchStatus
import com.lagradost.quicknovel.translator.db.BatchJob
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.ui.glossary.AnalyzeNovelDialogFragment
import kotlinx.coroutines.launch

class TranslateRangeDialogFragment : DialogFragment() {
    private val viewModel: TranslateViewModel by activityViewModels()

    companion object {
        // Temporary in-memory store to avoid needing Parcelable for ChapterData
        private var pendingChapters: List<ChapterData>? = null

        /** A finished batch stays visible in the dialog this long. */
        private const val SHOW_FINISHED_BATCH_MS = 3L * 24 * 60 * 60 * 1000

        fun newInstance(
            novelId: String,
            apiName: String,
            chapters: List<ChapterData>,
            novelAuthor: String? = null,
            novelName: String? = null,
            /** 0-based chapter to prefill as a one-chapter range (the result page's chapter menu). */
            startChapter: Int? = null,
        ): TranslateRangeDialogFragment {
            pendingChapters = chapters
            return TranslateRangeDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("novel_id", novelId)
                    putString("api_name", apiName)
                    putInt("chapter_count", chapters.size)
                    putString("novel_author", novelAuthor)
                    putString("novel_name", novelName)
                    if (startChapter != null) putInt("start_chapter", startChapter)
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
        val sourceLang = NovelChapterSource.langOf(novelId, apiName) ?: TranslationLanguages.DEFAULT

        val config = SecurePrefs.engineConfig(ctx)
        val strictMode = SecurePrefs.isStrictModeEnabled(ctx)
        val fallbackLabel = SecurePrefs.fallbackEngineConfig(ctx)?.label
        val batchAvailable = BatchManager.isAvailable(config)
        val caps = SecurePrefs.spendCaps(ctx)
        var bibleTerms = 0
        /** Chapters the automatic analysis will read before translating (0 = none). */
        var autoAnalysisChapters = 0
        var monthSpent: Double? = null
        var staleChapters: Set<Int> = emptySet()
        var inBatch: Set<Int> = emptySet()
        /** USD of the job as last shown (null = unknown price), for the cap check on Start. */
        var currentEstimate: Double? = null
        var currentCount = 0
        var updateEstimates: () -> Unit = {}

        viewModel.loadTranslatedNums(novelId)
        NovelChapterSource.remember(novelId, apiName, novelName.orEmpty(), novelAuthor, chapters)
        // Opening the dialog is a good moment to check on overnight batches.
        BatchScheduler.pollNow(ctx)

        binding.modeOvernight.setText(if (batchAvailable) R.string.translate_mode_batch else R.string.translate_mode_charging)

        fun overnight() = binding.modeOvernight.isChecked
        fun asBatch() = overnight() && batchAvailable

        // Story Bible status; suggest the analysis pre-pass when there are no characters yet.
        lifecycleScope.launch {
            val dao = TranslatorDatabase.getInstance(ctx).glossaryDao()
            val characters = dao.countCharacters(novelId)
            val totalTerms = dao.countForNovel(novelId)
            binding.bibleStatus.text = getString(R.string.bible_status, characters, totalTerms - characters)
            bibleTerms = totalTerms
            val autoAnalyze = chapters.isNotEmpty() && NovelAnalyzer.needsAutoAnalysis(ctx, novelId)
            autoAnalysisChapters = if (autoAnalyze) {
                SecurePrefs.getAutoAnalyzeChapterCount(ctx).coerceAtMost(chapters.size)
            } else 0
            monthSpent = SpendLedger.monthTotal(ctx)
            staleChapters = ChapterTranslationRepository.staleChapters(ctx, novelId).toHashSet()
            inBatch = BatchManager.chaptersInUnfinishedBatches(ctx, novelId)
            updateEstimates()
            if (autoAnalyze) binding.bibleAnalyzeHint.setText(R.string.bible_auto_analyze_hint)
            val showHint = characters == 0 && chapters.isNotEmpty()
            binding.bibleAnalyzeHint.visibility = if (showHint) View.VISIBLE else View.GONE
            binding.bibleAnalyzeButton.visibility = if (showHint) View.VISIBLE else View.GONE
            showBatchRow(binding, novelId)
        }
        binding.bibleAnalyzeButton.setOnClickListener {
            AnalyzeNovelDialogFragment.newInstance(novelId).show(parentFragmentManager, "analyze_novel")
            dismiss() // translate after the bible is built (only one job runs at a time)
        }

        val startChapter = arguments?.getInt("start_chapter", -1)?.takeIf { it in 0 until total }
        if (startChapter != null) {
            binding.rangeFrom.setText((startChapter + 1).toString())
            binding.rangeTo.setText((startChapter + 1).toString())
        } else {
            binding.rangeFrom.setText("1")
            binding.rangeTo.setText(minOf(50, total).toString())
        }
        binding.rangeAvailable.text = "Available: 1–$total"

        updateEstimates = {
            val from = (binding.rangeFrom.text.toString().toIntOrNull() ?: 1) - 1
            val to = (binding.rangeTo.text.toString().toIntOrNull() ?: 1) - 1
            val alreadyTranslated = (viewModel.translatedChapterNums.value ?: emptyList()).toHashSet()
            val rangeIndices = (from..to).toSet()
            val alreadyDone = rangeIndices.count { it in alreadyTranslated }
            val waitingInBatch = rangeIndices.count { it in inBatch && it !in alreadyTranslated }
            val staleInRange = rangeIndices.count { it in staleChapters && it in alreadyTranslated }

            binding.retranslateStale.visibility = if (staleInRange > 0) View.VISIBLE else View.GONE
            binding.retranslateStale.text = getString(R.string.translate_retranslate_stale, staleInRange)
            val includeStale = staleInRange > 0 && binding.retranslateStale.isChecked && !asBatch()
            binding.retranslateStale.isEnabled = !asBatch()

            val toTranslate = maxOf(0, rangeIndices.size - alreadyDone - waitingInBatch) + (if (includeStale) staleInRange else 0)
            currentCount = toTranslate

            val notes = buildList {
                if (alreadyDone > 0) add("Already translated: $alreadyDone chapters (will be skipped)")
                if (waitingInBatch > 0) add("Waiting in an overnight batch: $waitingInBatch chapters (will be skipped)")
            }
            binding.alreadyTranslated.text = notes.joinToString("\n")
            binding.alreadyTranslated.visibility = if (notes.isNotEmpty()) View.VISIBLE else View.GONE

            binding.chaptersToTranslate.text = "Chapters to translate: $toTranslate"

            val analysis = if (autoAnalysisChapters > 0 && toTranslate > 0) {
                CostEstimator.estimateAnalysis(autoAnalysisChapters, SecurePrefs.analysisEngineConfig(ctx).model, sourceLang)
            } else null
            if (asBatch()) {
                val batchUsd = BatchCost.estimateForChapters(config, toTranslate, sourceLang, bibleTerms)
                currentEstimate = batchUsd?.let { it + (analysis?.usd ?: 0.0) }
                binding.estimatedCost.text = buildString {
                    append("Est. cost: ")
                    append(batchUsd?.let { "${CostEstimator.formatCost(it)} at batch price (50% off)" } ?: "unknown price")
                    append(" — ${config.label}, ${TranslationLanguages.displayName(sourceLang)} → English")
                    append("\nPassages that fail the checks are re-done live at the normal price.")
                    if (analysis != null) {
                        append(getString(R.string.analyze_includes_auto, autoAnalysisChapters, CostEstimator.formatEstimate(analysis)))
                    }
                }
            } else {
                val estimate = CostEstimator.estimateForConfig(config, toTranslate, sourceLang, strictMode, bibleTerms)
                currentEstimate = estimate.usd?.let { it + (analysis?.usd ?: 0.0) }
                binding.estimatedCost.text = buildString {
                    append("Est. cost: ${CostEstimator.formatEstimate(estimate)} — ${config.label}, ")
                    append("${TranslationLanguages.displayName(sourceLang)} → English")
                    if (strictMode) append(", strict mode")
                    if (fallbackLabel != null) append("\nFallback for blocked or refused passages: $fallbackLabel")
                    if (analysis != null) {
                        append(getString(R.string.analyze_includes_auto, autoAnalysisChapters, CostEstimator.formatEstimate(analysis)))
                    }
                }
            }

            binding.modeNote.visibility = if (overnight() && !batchAvailable) View.VISIBLE else View.GONE
            binding.modeNote.setText(R.string.translate_mode_charging_note)

            monthSpent?.let { spent ->
                binding.monthSpend.visibility = View.VISIBLE
                binding.monthSpend.text = if (caps.monthlyOn) {
                    getString(R.string.spend_month_cap_line, SpendPricing.format(spent), SpendPricing.format(caps.monthlyUsd))
                } else {
                    getString(R.string.spend_month_line, SpendPricing.format(spent))
                }
            }
        }

        viewModel.translatedChapterNums.observe(this) { updateEstimates() }
        binding.rangeFrom.doOnTextChanged { _, _, _, _ -> updateEstimates() }
        binding.rangeTo.doOnTextChanged { _, _, _, _ -> updateEstimates() }
        binding.translateMode.setOnCheckedChangeListener { _, _ -> updateEstimates() }
        binding.retranslateStale.setOnCheckedChangeListener { _, _ -> updateEstimates() }
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

                lifecycleScope.launch {
                    // The estimate plus this month's spend must fit the caps.
                    val check = if (currentCount > 0) SpendLedger.checkStart(ctx, currentEstimate) else SpendCaps.StartCheck.Allowed
                    if (check !is SpendCaps.StartCheck.Allowed) {
                        AlertDialog.Builder(ctx)
                            .setTitle("Spend cap")
                            .setMessage(check.message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        return@launch
                    }
                    viewModel.startTranslation(
                        novelId = novelId,
                        apiName = apiName,
                        chapters = chapters,
                        fromIndex = from - 1,
                        toIndex = to - 1,
                        novelAuthor = novelAuthor,
                        novelName = novelName,
                        requireCharging = overnight() && !batchAvailable,
                        retranslateStale = binding.retranslateStale.isChecked && binding.retranslateStale.visibility == View.VISIBLE && !asBatch(),
                        asBatch = asBatch(),
                    )
                    pendingChapters = null
                    dialog.dismiss()
                }
            }
        }

        return dialog
    }

    /** The novel's latest overnight batch: status, progress, cancel and "translate the rest now". */
    private suspend fun showBatchRow(binding: DialogTranslateRangeBinding, novelId: String) {
        val ctx = context ?: return
        val job = BatchManager.latestForNovel(ctx, novelId) ?: return
        val status = BatchStatus.fromName(job.status)
        val recent = !status.isFinished || System.currentTimeMillis() - (job.completedAt ?: job.updatedAt) < SHOW_FINISHED_BATCH_MS
        if (!recent) return
        val counts = BatchManager.chapterCounts(ctx, job.id)
        binding.batchRow.visibility = View.VISIBLE
        binding.batchStatus.text = batchSummary(job, status, counts)

        val cancellable = status in setOf(BatchStatus.VALIDATING, BatchStatus.IN_PROGRESS, BatchStatus.FINALIZING)
        binding.batchCancel.visibility = if (cancellable) View.VISIBLE else View.GONE
        binding.batchCancel.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.batch_cancel)
                .setMessage("Cancel the overnight batch? Passages already finished are still stored (and billed).")
                .setPositiveButton(R.string.batch_cancel) { _, _ ->
                    lifecycleScope.launch {
                        val error = BatchManager.cancel(ctx, job.id)
                        Toast.makeText(ctx, error ?: "Cancelling the batch…", Toast.LENGTH_LONG).show()
                        binding.batchCancel.visibility = View.GONE
                        BatchManager.latestForNovel(ctx, novelId)?.let {
                            binding.batchStatus.text = batchSummary(it, BatchStatus.fromName(it.status), counts)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        val needsLive = counts[BatchChapterStatus.NEEDS_LIVE] ?: 0
        binding.batchTranslateRest.visibility = if (status.isFinished && needsLive > 0) View.VISIBLE else View.GONE
        binding.batchTranslateRest.setOnClickListener {
            binding.rangeFrom.setText((job.fromChapter + 1).toString())
            binding.rangeTo.setText((job.toChapter + 1).toString())
            binding.modeNow.isChecked = true
        }
    }

    private fun batchSummary(job: BatchJob, status: BatchStatus, counts: Map<BatchChapterStatus, Int>): String = buildString {
        append("Overnight batch (chapters ${job.fromChapter + 1}–${job.toChapter + 1}): ${status.label}")
        when {
            status == BatchStatus.IN_PROGRESS || status == BatchStatus.FINALIZING ->
                append(" ${job.completedCount + job.failedCount}/${job.requestCount} requests")
            status.isFinished && status != BatchStatus.FAILED -> {
                append(" · ${counts[BatchChapterStatus.STORED] ?: 0} chapters stored")
                (counts[BatchChapterStatus.NEEDS_LIVE] ?: 0).takeIf { it > 0 }?.let { append(", $it left for normal translation") }
            }
        }
        val cost = job.actualUsd?.let { "cost ${SpendPricing.format(it)}" } ?: "est. ${SpendPricing.format(job.estimatedUsd)}"
        append(" · $cost")
        if (status == BatchStatus.FAILED && !job.error.isNullOrBlank()) append("\n${job.error}")
    }
}
