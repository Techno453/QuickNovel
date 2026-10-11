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

class ChapterAdapter(
    val viewModel: ResultViewModel,
    /** Whether this novel comes from a Chinese (zh) source. Updated once API is loaded. */
    private var isChineseSource: Boolean = false
) :
    NoStateAdapter<ChapterData>(
        diffCallback = BaseDiffCallback(
            itemSame = { a, b -> a.url == b.url },
            contentSame = { a, b -> a == b }
        )) {

    companion object {
        val sharedPool =
            newSharedPool {
                setMaxRecycledViews(CONTENT, 10)
            }
    }

    /** Call this once the API is loaded so chapter names get translated if appropriate. */
    fun setIsChineseSource(value: Boolean) {
        if (isChineseSource != value) {
            isChineseSource = value
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
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

            // Cancel any in-flight translation job on this view
            (name.tag as? Job)?.cancel()
            name.tag = null

            val ctx = name.context
            if (isChineseSource && SecurePrefs.isGoogleTranslateTitlesEnabled(ctx)) {
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
        (binding.name.tag as? Job)?.cancel()
        binding.name.tag = null
    }
}
