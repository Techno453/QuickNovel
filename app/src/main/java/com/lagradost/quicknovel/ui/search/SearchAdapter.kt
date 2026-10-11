package com.lagradost.quicknovel.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.databinding.SearchResultGridBinding
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.ui.BaseDiffCallback
import com.lagradost.quicknovel.ui.NoStateAdapter
import com.lagradost.quicknovel.ui.ViewHolderState
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.GoogleTranslate
import com.lagradost.quicknovel.util.UIHelper.hideKeyboard
import com.lagradost.quicknovel.util.UIHelper.setImage
import com.lagradost.quicknovel.widget.AutofitRecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SearchAdapter(
    private val viewModel: SearchViewModel,
    private val resView: AutofitRecyclerView,
) :
    NoStateAdapter<SearchResponse>(BaseDiffCallback(itemSame = { a, b ->
        a.url == b.url
    }, contentSame = { a, b ->
        a == b
    })) {

    companion object {
        val sharedPool =
            RecyclerView.RecycledViewPool().apply {
                this.setMaxRecycledViews(CONTENT, 10)
            }
    }

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return ViewHolderState(
            SearchResultGridBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: SearchResponse, position: Int) {
        val binding = holder.view as? SearchResultGridBinding ?: return
        binding.apply {
            val coverHeight: Int = (resView.itemWidth / 0.68).roundToInt()
            imageView.apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    coverHeight
                )
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                setImage(item.image)

                setOnClickListener {
                    viewModel.load(item)
                }

                setOnLongClickListener { view ->
                    hideKeyboard(view)
                    viewModel.showMetadata(item)
                    return@setOnLongClickListener true
                }
            }

            // Set original name immediately so the cell is never blank
            imageText.text = item.name

            // Cancel any in-flight translation job on this view
            (imageText.tag as? Job)?.cancel()
            imageText.tag = null

            val ctx = imageText.context
            val isTranslatableSource = TranslationLanguages.isTranslatableApi(item.apiName)
            if (isTranslatableSource && SecurePrefs.isGoogleTranslateTitlesEnabled(ctx)) {
                val job = CoroutineScope(Dispatchers.Main).launch {
                    val translated = withContext(Dispatchers.IO) {
                        GoogleTranslate.translate(item.name)
                    }
                    // Only update if this view is still bound to the same item (tag == this job)
                    if (imageText.tag === this.coroutineContext[Job]) {
                        imageText.text = translated
                        imageText.tag = null
                    }
                }
                imageText.tag = job
            }
        }
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        val binding = holder.view as? SearchResultGridBinding ?: return
        (binding.imageText.tag as? Job)?.cancel()
        binding.imageText.tag = null
    }
}
