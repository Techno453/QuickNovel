package com.lagradost.quicknovel.ui.result

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.databinding.SimpleChapterBinding
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.ui.BaseDiffCallback
import com.lagradost.quicknovel.ui.NoStateAdapter
import com.lagradost.quicknovel.ui.ViewHolderState
import com.lagradost.quicknovel.util.GoogleTranslate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lagradost.quicknovel.ui.newSharedPool
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.translator.TranslatorNovelId
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import kotlinx.coroutines.delay

class ChapterAdapter(
    val viewModel: ResultViewModel,
    /** Whether this novel comes from a translatable (zh/ja/ko) source. Updated once API is loaded. */
    private var isTranslatableSource: Boolean = false
) :
    NoStateAdapter<ChapterData>(
        diffCallback = BaseDiffCallback(
            itemSame = { a, b -> a.url == b.url },
            contentSame = { a, b -> a == b }
        )) {

    companion object {
        private const val STATUS_REFRESH_MS = 4000L

        val sharedPool =
            newSharedPool {
                setMaxRecycledViews(CONTENT, 10)
            }
    }

    /** Call this once the API is loaded so chapter names get translated if appropriate. */
    fun setIsTranslatableSource(value: Boolean) {
        if (isTranslatableSource != value) {
            isTranslatableSource = value
            notifyItemRangeChanged(0, itemCount)
        }
    }

    // ======================= translation status chips =======================

    /** Where the chapter translation statuses come from (translator DB + queues). */
    var statusSource: ChapterStatusSource = TranslatorChapterStatusSource

    private var statuses: Map<Int, ChapterTranslationStatus> = emptyMap()
    private var statusJob: Job? = null
    private val boundRows = HashMap<SimpleChapterBinding, ChapterData>()

    private var indexData: List<ChapterData>? = null
    private var indexByUrl: Map<String, Int> = emptyMap()

    private fun stream(): StreamResponse? =
        ((viewModel.loadResponse.value as? Resource.Success)?.value as? StreamResponse)

    /** Index of [chapter] in the novel (what the translator stores), not its list position. */
    private fun chapterIndexOf(chapter: ChapterData): Int? {
        val data = stream()?.data ?: return null
        if (data !== indexData) {
            indexData = data
            indexByUrl = data.withIndex().associate { (i, c) -> c.url to i }
        }
        return indexByUrl[chapter.url]
    }

    private fun novelId(): String? {
        if (!isTranslatableSource) return null
        val stream = stream() ?: return null
        return TranslatorNovelId.from(stream.apiName, stream.name)
    }

    /**
     * Keeps the statuses fresh while the list is on screen (a cheap DB query every few seconds,
     * so queued chapters turn into translated ones as the worker goes). Bound rows are updated
     * in place, without rebinding them.
     */
    private fun startStatusUpdates(parent: View) {
        if (statusJob != null) return
        val owner = parent.findViewTreeLifecycleOwner() ?: return
        statusJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val id = novelId()
                    val next = if (id == null) emptyMap() else withContext(Dispatchers.IO) {
                        ChapterStatusSource.statuses(statusSource, parent.context.applicationContext, id)
                    }
                    if (next != statuses) {
                        statuses = next
                        for ((row, chapter) in boundRows) bindStatus(row, chapter)
                    }
                    delay(STATUS_REFRESH_MS)
                }
            }
        }
    }

    private fun bindStatus(binding: SimpleChapterBinding, chapter: ChapterData) {
        val status = chapterIndexOf(chapter)?.let { statuses[it] } ?: ChapterTranslationStatus.NONE
        val chip = binding.translationStatus
        val ctx = chip.context
        chip.isVisible = status != ChapterTranslationStatus.NONE
        when (status) {
            ChapterTranslationStatus.NONE -> {}
            ChapterTranslationStatus.TRANSLATED -> {
                chip.setText(R.string.chapter_status_translated)
                chip.setTextColor(ctx.colorFromAttribute(R.attr.colorPrimary))
            }

            ChapterTranslationStatus.STALE -> {
                chip.setText(R.string.chapter_status_stale)
                chip.setTextColor(ContextCompat.getColor(ctx, R.color.ratingColorBg))
            }

            ChapterTranslationStatus.QUEUED -> {
                chip.setText(R.string.chapter_status_queued)
                chip.setTextColor(ctx.colorFromAttribute(R.attr.grayTextColor))
            }
        }
        // keep the title clear of the chip
        val pad = if (chip.isVisible) ctx.resources.getDimensionPixelSize(R.dimen.chapter_status_chip_space) else 0
        binding.chapterText.updatePaddingRelative(end = binding.chapterText.paddingStart + pad)
    }

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        startStatusUpdates(parent)
        return ViewHolderState(
            SimpleChapterBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    private fun refresh(
        binding: SimpleChapterBinding,
        card: ChapterData,
        viewModel: ResultViewModel
    ) {
        val alpha = if (viewModel.hasReadChapter(chapter = card)) 0.5F else 1.0F
        binding.name.alpha = alpha
        binding.releaseDate.alpha = alpha
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ChapterData, position: Int) {
        val binding = holder.view as? SimpleChapterBinding ?: return
        binding.apply {
            name.text = item.name
            releaseDate.text = item.dateOfRelease
            releaseDate.isGone = item.dateOfRelease.isNullOrBlank()
            root.setOnClickListener {
                viewModel.streamRead(item)
                viewModel.isResume = true//to update read status
            }
            root.setOnLongClickListener {
                viewModel.setReadChapter(chapter = item, !viewModel.hasReadChapter(item))
                refresh(binding, item, viewModel)
                return@setOnLongClickListener true
            }
            refresh(binding, item, viewModel)
            boundRows[binding] = item
            bindStatus(binding, item)

            // Cancel any in-flight translation job on this view
            (name.tag as? Job)?.cancel()
            name.tag = null

            val ctx = name.context
            if (isTranslatableSource && SecurePrefs.isGoogleTranslateTitlesEnabled(ctx)) {
                val job = CoroutineScope(Dispatchers.Main).launch {
                    val translated = withContext(Dispatchers.IO) {
                        GoogleTranslate.translate(item.name)
                    }
                    // Only update if this view is still bound to the same item
                    if (name.tag === this.coroutineContext[Job]) {
                        name.text = translated
                        name.tag = null
                    }
                }
                name.tag = job
            }
        }
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        val binding = holder.view as? SimpleChapterBinding ?: return
        boundRows.remove(binding)
        (binding.name.tag as? Job)?.cancel()
        binding.name.tag = null
    }
}
