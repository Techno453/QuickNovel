package com.lagradost.quicknovel.ui.result

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.EpubResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainActivity.Companion.navigate
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.databinding.ChapterDialogBinding
import com.lagradost.quicknovel.databinding.ChapterFilterPopupBinding
import com.lagradost.quicknovel.databinding.FragmentResultBinding
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.debugException
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.mvvm.observeNullable
import com.lagradost.quicknovel.ui.BaseFragment
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.ui.SortingMethodAdapter
import com.lagradost.quicknovel.ui.mainpage.MainAdapter
import com.lagradost.quicknovel.ui.mainpage.MainPageFragment
import com.lagradost.quicknovel.ui.setRecycledViewPool
import com.lagradost.quicknovel.ui.common.MetadataTranslationViews
import com.lagradost.quicknovel.util.SettingsHelper.getRating
import com.lagradost.quicknovel.util.SingleSelectionHelper.showBottomDialog
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.TranslatorNovelId
import com.lagradost.quicknovel.imported.ImportedBooks
import com.lagradost.quicknovel.ui.glossary.AnalyzeNovelDialogFragment
import com.lagradost.quicknovel.ui.glossary.GlossaryFragment
import com.lagradost.quicknovel.ui.translate.NovelChapterSource
import com.lagradost.quicknovel.ui.translate.TranslateRangeDialogFragment
import com.lagradost.quicknovel.ui.translate.TranslateViewModel
import com.lagradost.quicknovel.ui.translate.TranslationProgress
import com.lagradost.quicknovel.util.UIHelper
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar
import com.lagradost.quicknovel.util.AdaptiveLayout
import com.lagradost.quicknovel.util.UIHelper.fixSystemBarsPadding
import com.lagradost.quicknovel.util.UIHelper.getStatusBarHeight
import com.lagradost.quicknovel.util.UIHelper.hideKeyboard
import com.lagradost.quicknovel.util.UIHelper.html
import com.lagradost.quicknovel.util.UIHelper.humanReadableByteCountSI
import com.lagradost.quicknovel.util.UIHelper.popupMenu
import com.lagradost.quicknovel.util.UIHelper.setImage
import com.lagradost.quicknovel.util.GoogleTranslate
import com.lagradost.quicknovel.util.toPx
import androidx.lifecycle.lifecycleScope
import kotlin.collections.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAX_SYNO_LENGH = 300

class ResultFragment : BaseFragment<FragmentResultBinding>(
    BindingCreator.Inflate(FragmentResultBinding::inflate)
) {
    private val viewModel: ResultViewModel by viewModels()
    private val translateViewModel: TranslateViewModel by activityViewModels()

    companion object {
        fun newInstance(url: String, apiName: String, startAction: Int = 0): Bundle =
            Bundle().apply {
                //println(data)
                putString("url", url)
                putString("apiName", apiName)
                putInt("startAction", startAction)
            }

        /**
         * The completed run that was last announced. The activity-scoped progress LiveData keeps
         * its final value, so without this every reopened result page would announce it again.
         */
        private var announcedCompletion: TranslationProgress? = null
    }

    private fun openGlossary(novelId: String, novelName: String) {
        activity?.navigate(
            R.id.global_to_navigation_glossary,
            GlossaryFragment.newInstance(novelId, novelName)
        )
    }

    /** "Translation finished: 3 of 3 chapters translated · 2 glossary deviations · 4 new terms". */
    private fun announceTranslationFinished(progress: TranslationProgress, novelId: String, novelName: String) {
        val ctx = context ?: return
        val parts = buildList {
            add(
                if (progress.total > 0) ctx.getString(R.string.translation_finished_title) + ": " +
                    ctx.getString(R.string.translation_finished_text, progress.current, progress.total)
                else ctx.getString(R.string.translation_finished_title)
            )
            if (progress.total > 0) add(progress.qualitySummary(ctx))
            progress.fallbackSummary(ctx)?.let { add(it) }
            if (progress.newTermsFound > 0) add(
                ctx.resources.getQuantityString(
                    R.plurals.new_terms_to_review, progress.newTermsFound, progress.newTermsFound
                )
            )
        }
        val msg = parts.joinToString(" · ")
        if (progress.newTermsFound > 0) {
            val root = binding?.root ?: return
            Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
                .setDuration(8000)
                .setAction(R.string.glossary_review_new_terms) { openGlossary(novelId, novelName) }
                .apply {
                    view.findViewById<android.widget.TextView>(
                        com.google.android.material.R.id.snackbar_text
                    )?.maxLines = 4
                }
                .show()
        } else {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun fixLayout(view: View) {
        // columns from the available width, not the orientation (foldables, split screen)
        binding?.relatedList?.setAdaptiveColumns(AdaptiveLayout.POSTER_MIN_CELL_DP, minCount = 3)
        binding?.resultHolder?.post { // BUG FIX
            updateScrollHeight()
        }
        binding?.resultDownloadCard?.let {
            fixSystemBarsPadding(
                it,
                padTop = false,
                overlayCutout = false
            )
        }
    }

    //private lateinit var viewModel: ResultViewModel

    val repo get() = viewModel.repo


    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        //only if really is onResume
        if (viewModel.isResume) {
            (binding?.chapterList?.adapter as? ChapterAdapter)?.notifyDataSetChanged()
            viewModel.isResume = false
        }

        activity?.apply {
            window?.navigationBarColor =
                colorFromAttribute(R.attr.primaryBlackBackground)
        }
    }

    /** Height the result page actually has: the scroll view, not the whole display, so that
     * split screen, tabletop/Flex mode and fold/unfold get the right sizes. */
    private fun availableHeight(): Int {
        val binding = binding ?: return 0
        val measured = binding.resultMainscroll.height
        if (measured > 0) return measured
        return context?.resources?.displayMetrics?.heightPixels ?: 0
    }

    private fun updateScrollHeight() {
        val binding = binding ?: return
        val available = availableHeight()
        if (available <= 0) return

        // The card must be at least as tall as the page so it can be scrolled up to the top.
        // A min height (instead of the old "padding = height - card height") does not depend on
        // its own previous value, so calling this repeatedly is stable.
        binding.resultNovelHolder.apply {
            if (paddingBottom != 0) setPadding(paddingLeft, paddingTop, paddingRight, 0)
        }
        if (binding.resultDownloadCard.minimumHeight != available) {
            binding.resultDownloadCard.minimumHeight = available
        }

        val parameter = binding.chapterList.layoutParams
        val chapterHeight = maxOf(
            0,
            available - binding.viewsAndRating.height - binding.resultTabs.height - binding.resultScrollPadding.paddingTop
        )
        if (parameter.height == chapterHeight) return
        parameter.height = chapterHeight
        binding.chapterList.layoutParams = parameter
        //ViewGroup.LayoutParams(binding.chapterList.layoutParams.width,displayMetrics.heightPixels)
        /*binding.hiddenView.apply {
            setPadding(
                paddingLeft,
                paddingTop,
                paddingRight,
                maxOf(0, total)
            )
        }*/
    }


    private fun newState(loadResponse: Resource<LoadResponse>?) {
        if (loadResponse == null) return
        val binding = binding ?: return
        //activity?.window?.navigationBarColor =
        //    requireContext().colorFromAttribute(R.attr.bitDarkerGrayBackground)

        when (loadResponse) {
            is Resource.Failure -> {
                binding.apply {
                    resultLoading.isVisible = false
                    resultLoadingError.isVisible = true
                    resultHolder.isVisible = false
                    resultErrorText.text = loadResponse.errorString
                    resultPosterBlur.isVisible = false
                }
            }

            is Resource.Loading -> {
                binding.apply {
                    resultLoading.isVisible = true
                    resultLoadingError.isVisible = false
                    resultHolder.isVisible = false
                    resultPosterBlur.isVisible = false
                }
            }

            is Resource.Success -> {
                val res = loadResponse.value

                binding.apply {
                    downloadWarning.isVisible = (repo?.rateLimitTime ?: 0) > 2000

                    res.image?.let { img ->
                        resultEmptyView.setOnClickListener {
                            UIHelper.showImage(it.context, img)
                        }
                    }

                    resultPoster.setImage(res.image)
                    resultPosterBlur.setImage(res.image, radius = 100, sample = 3)

                    resultTitle.text = res.name
                    resultAuthor.text = res.author ?: getString(R.string.no_author)

                    // The translated title (and synopsis) are bound with the synopsis below.
                    val isTranslatableForTitle = viewModel.sourceLang() != null
                    // Update chapter adapter with correct source flag now that repo is known
                    (chapterList.adapter as? ChapterAdapter)?.setIsTranslatableSource(isTranslatableForTitle)

                    resultRatingVotedCount.text = getString(R.string.no_data)
                    res.rating?.let { rating ->
                        resultRating.text = context?.getRating(rating)
                        val votes = res.peopleVoted
                        if (votes != null) {
                            resultRatingVotedCount.text =
                                getString(R.string.votes_format).format(votes)
                        }
                    }
                    resultViews.text =
                        res.views?.let { views -> humanReadableByteCountSI(views) }
                            ?: getString(R.string.no_data)

                    resultBack.setColorFilter(Color.WHITE)
                    resultTabs.removeAllTabs()
                    resultTabs.isVisible = false
                    val hasRelated = !res.related.isNullOrEmpty()
                    val hasChapters =
                        (res is StreamResponse && res.data.isNotEmpty()) || res is EpubResponse
                    val api = repo
                    // Imported EPUB/TXT books have no provider but a chapter list.
                    val imported = viewModel.isImportedBook()
                    resultOpeninbrower.isVisible = !imported
                    resultShare.isVisible = !imported

                    if ((api != null || imported) && (api?.hasReviews == true || hasRelated || hasChapters)) {
                        resultTabs.isVisible = true
                        resultTabs.addTab(resultTabs.newTab().setText(R.string.novel).setId(0))
                        if (api?.hasReviews == true) {
                            resultTabs.addTab(
                                resultTabs.newTab().setText(R.string.reviews).setId(1)
                            )
                        }
                        if (hasRelated) {
                            resultTabs.addTab(
                                resultTabs.newTab().setText(R.string.related).setId(2)
                            )
                            relatedList.apply {
                                setRecycledViewPool(MainAdapter.sharedPool)
                                val mainPageAdapter = MainAdapter(this, 0)
                                adapter = mainPageAdapter
                                mainPageAdapter.submitList(res.related)
                            }
                            fixLayout(binding.root)
                        }
                        if (hasChapters) {
                            resultTabs.addTab(
                                resultTabs.newTab().setText(R.string.read_action_chapters).setId(3)
                            )
                        }
                    }
                    val target = viewModel.currentTabIndex.value
                    if (target != null) {
                        resultTabs.getTabAt(target)?.let { new ->
                            resultTabs.selectTab(new)
                        }
                    }


                    //viewsAndRating.isVisible = res.views != null || res.peopleVoted != null

                    val readStatusText = res.status?.resource?.let { getString(it) } ?: ""
                    resultStatus.text = readStatusText
                    resultStatus.isVisible = readStatusText.isNotBlank()

                    resultTag.removeAllViews()
                    if (res.tags == null && res.status == null) {
                        // resultTagHolder.isVisible = false
                    } else {
                        // resultTagHolder.isGone = res.tags.isNullOrEmpty()
                        resultTag.apply {

                            val map =
                                api?.tags?.mapIndexed { i, (value, _) -> value to i }
                                    ?.associate { it } ?: emptyMap()

                            res.tags?.forEach { tag ->
                                val chip = Chip(context)
                                val chipDrawable = ChipDrawable.createFromAttributes(
                                    context,
                                    null,
                                    0,
                                    R.style.ChipFilled
                                )
                                chip.setChipDrawable(chipDrawable)
                                chip.text = tag
                                chip.isChecked = false
                                chip.isCheckable = false
                                chip.isFocusable = false
                                chip.isClickable = false

                                map[tag]?.let { index ->
                                    chip.isClickable = true
                                    chip.setOnClickListener {
                                        val api = repo
                                        if (api != null)
                                            activity?.navigate(
                                                R.id.global_to_navigation_mainpage,
                                                MainPageFragment.newInstance(
                                                    api.name,
                                                    tag = index
                                                )
                                            )
                                    }
                                }

                                chip.setTextColor(context.colorFromAttribute(R.attr.textColor))
                                addView(chip)
                            }
                        }
                    }
                    // Title + synopsis, translated (AI or Google, cached) for zh/ja/ko sources with a
                    // "Show original" toggle under the title; originals until the translation arrives.
                    MetadataTranslationViews.bindResult(
                        owner = viewLifecycleOwner,
                        apiName = viewModel.apiName,
                        name = res.name,
                        synopsis = res.synopsis,
                        titleView = resultTitle,
                        synopsisView = resultSynopsisText,
                        maxSynopsis = MAX_SYNO_LENGH,
                        lang = viewModel.sourceLang(),
                    )

                    if (res is StreamResponse) {
                        resultChaptersInfoHolder.isVisible = true
                        resultChapters.text = res.data.size.toString()
                        resultChaptersInfo.text =
                            if (res.data.size == 1) getString(R.string.chapter) else getString(R.string.chapters)
                        // An imported book reads its stored EPUB ("Read"); there is nothing to stream.
                        resultQuickstream.isVisible = !imported
                        resultTotalChapters.isVisible = true
                        // Imported books always offer it: an undetected language is chosen on the first tap.
                        val showTranslate = (viewModel.sourceLang() != null || imported) &&
                            SecurePrefs.isTranslationEnabled(requireContext())
                        resultTranslateHolder.isVisible = showTranslate
                        if (res.data.isNotEmpty()) {
                            resultTotalChapters.text =
                                getString(R.string.latest_format).format(res.data.last().name)
                        } else {
                            resultTotalChapters.text = getString(R.string.no_chapters)
                        }
                    } else {
                        resultChaptersInfoHolder.isVisible = false
                        resultTotalChapters.isVisible = false
                        resultQuickstream.isVisible = false
                    }

                    resultLoading.isVisible = false
                    resultLoadingError.isVisible = false
                    resultHolder.isVisible = true
                    resultPosterBlur.isVisible = true
                    resultHolder.doOnNextLayout {
                        updateScrollHeight()
                    }
                    resultHolder.post {
                        updateScrollHeight()
                        resultHolder.post {
                            updateScrollHeight()
                            resultHolder.post {
                                updateScrollHeight()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Source language of an imported book, chosen by hand (detection found too little CJK text,
     * or picked the wrong language). Stored as the book's override; [then] runs after a choice.
     */
    private fun chooseImportedLanguage(novelId: String, then: () -> Unit) {
        val ctx = context ?: return
        val languages = TranslationLanguages.all
        val current = ImportedBooks.lang(novelId)
        val detected = ImportedBooks.record(novelId)?.detectedLang
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(
                if (detected == null) getString(R.string.imported_language_unknown)
                else getString(R.string.imported_language_title, TranslationLanguages.displayName(detected))
            )
            .setSingleChoiceItems(
                languages.map { it.displayName }.toTypedArray(),
                languages.indexOfFirst { it.code == current },
            ) { dialog, which ->
                ImportedBooks.setLangOverride(novelId, languages[which].code)
                dialog.dismiss()
                // Chapter chips and title translation follow the new language.
                (binding?.chapterList?.adapter as? ChapterAdapter)?.setIsTranslatableSource(true)
                then()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doAction(action: Int) {
        when (action) {
            R.string.resume -> {
                viewModel.download()
            }

            R.string.download -> {
                viewModel.downloadFrom(null)
            }

            R.string.re_downloaded -> {
                viewModel.download()
            }

            R.string.download_from_chapter -> {
                val chapters =
                    ((viewModel.loadResponse.value as? Resource.Success)?.value as? StreamResponse)?.data
                        ?: return

                val act = CommonActivity.activity ?: return

                val builder: AlertDialog.Builder =
                    AlertDialog.Builder(act, R.style.AlertDialogCustom)

                val binding = ChapterDialogBinding.inflate(layoutInflater, null, false)
                val dialogClickListener =
                    DialogInterface.OnClickListener { _, which ->
                        when (which) {
                            DialogInterface.BUTTON_POSITIVE -> {
                                viewModel.downloadFrom(
                                    binding.chapterEdit.text?.toString()?.toIntOrNull()
                                )
                            }

                            DialogInterface.BUTTON_NEGATIVE -> {
                            }
                        }
                    }

                builder.setView(binding.root)
                    .setTitle(R.string.download_from_chapter)
                    .setPositiveButton(R.string.download, dialogClickListener)
                    .setNegativeButton(R.string.cancel, dialogClickListener)
                    .show()

                binding.chapterEdit.doOnTextChanged { text, _, _, _ ->
                    val parsedInt = text?.toString()?.toIntOrNull()
                    if (parsedInt == null || parsedInt < 0 || parsedInt >= chapters.size) {
                        binding.chapterEdit.error = act.getString(R.string.error_outside_chapter)
                    } else {
                        binding.chapterEdit.error = null
                    }
                }
            }

            R.string.delete -> {
                viewModel.deleteAlert()
            }

            R.string.pause -> {
                viewModel.pause()
            }

            R.string.stop -> {
                viewModel.stop()
            }
        }
    }

    private fun getActions(): List<Int>? {
        val items = mutableListOf<Int>()
        val progressState =
            viewModel.downloadState.value ?: return null
        val canDownload =
            progressState.progress < progressState.total
        val canPartialDownload =
            progressState.downloaded < progressState.total && progressState.total > 1

        when (progressState.state) {
            DownloadState.IsPaused -> {
                items.add(R.string.resume)
                items.add(R.string.stop)
            }

            DownloadState.IsDownloading -> {
                items.add(R.string.pause)
                items.add(R.string.stop)
            }

            DownloadState.IsDone -> {
                if (canPartialDownload) {
                    items.add(R.string.download_from_chapter)
                }
            }

            DownloadState.IsPending -> {
            }

            DownloadState.IsFailed, DownloadState.IsStopped -> {
                if (canDownload) {
                    items.add(R.string.re_downloaded)
                }

                if (canPartialDownload) {
                    items.add(R.string.download_from_chapter)
                }
            }

            DownloadState.Nothing -> {
                if (canDownload) {
                    items.add(R.string.download)
                }

                if (canPartialDownload) {
                    items.add(R.string.download_from_chapter)
                }
            }
        }

        if (progressState.progress > 0) {
            items.add(R.string.delete)
        }
        return items
    }

    override fun onBindingCreated(binding: FragmentResultBinding, savedInstanceState: Bundle?) {
        val url = savedInstanceState?.getString("url") ?: arguments?.getString("url")
        ?: throw NotImplementedError()
        val apiName = savedInstanceState?.getString("apiName") ?: arguments?.getString("apiName")
        ?: throw NotImplementedError()

        activity?.window?.decorView?.clearFocus()
        hideKeyboard()

        // Re-size the card and chapter list whenever the page height changes: fold/unfold,
        // split screen resizing, tabletop posture, IME, ...
        binding.resultMainscroll.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                v.post { updateScrollHeight() }
            }
        }

        if (viewModel.loadResponse.value == null || !viewModel.isGetLoaded)
            viewModel.initState(apiName, url)

        binding.apply {
            activity?.fixPaddingStatusbar(resultInfoHeader)

            //resultOpeninbrowerText.text = apiName //""// resultUrl

            resultReloadConnectionerror.setOnClickListener {
                viewModel.initState(apiName, url)
            }

            resultOpeninbrower.setOnClickListener {
                viewModel.openInBrowser()
            }

            resultReloadConnectionOpenInBrowser.setOnClickListener {
                viewModel.openInBrowser()
            }

            reviewsFab.setOnClickListener {
                resultReviews.smoothScrollToPosition(0) // NEEDS THIS TO RESET VELOCITY
                resultMainscroll.smoothScrollTo(0, 0)
            }

            val backParameter = resultBack.layoutParams as CoordinatorLayout.LayoutParams
            backParameter.setMargins(
                backParameter.leftMargin,
                backParameter.topMargin + (activity?.getStatusBarHeight() ?: 0),
                backParameter.rightMargin,
                backParameter.bottomMargin
            )
            resultBack.layoutParams = backParameter

            resultBack.setOnClickListener {
                activity?.onBackPressedDispatcher?.onBackPressed()
                //activity?.onBackPressed()
                //    (activity as? AppCompatActivity)?.backPressed()
            }

            val parameter = resultEmptyView.layoutParams as LinearLayout.LayoutParams
            parameter.setMargins(
                parameter.leftMargin,
                parameter.topMargin + (activity?.getStatusBarHeight() ?: 0),
                parameter.rightMargin,
                parameter.bottomMargin
            )
            resultEmptyView.layoutParams = parameter

            resultShare.setOnClickListener {
                viewModel.share()
            }

            val reviewAdapter = ReviewAdapter()
            resultReviews.setRecycledViewPool(ReviewAdapter.sharedPool)
            resultReviews.adapter = reviewAdapter
            resultReviews.layoutManager = GridLayoutManager(context, 1)

            observe(viewModel.reviews) { reviews ->
                when (reviews) {
                    is Resource.Success -> {
                        resultviewReviewsLoading.isVisible = false
                        resultviewReviewsLoadingShimmer.startShimmer()
                        resultReviews.isVisible = true
                        // fuck jvm, we have to do a copy because otherwise it wont fucking register
                        reviewAdapter.submitList(reviews.value.map { it.copy() })
                    }

                    is Resource.Loading -> {
                        resultviewReviewsLoadingShimmer.stopShimmer()
                        resultviewReviewsLoading.isVisible = true
                        resultReviews.isVisible = false
                    }

                    is Resource.Failure -> {
                        debugException { "This should never happened" }
                    }
                }
            }

            resultTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    viewModel.switchTab(tab?.id, resultTabs.selectedTabPosition)
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })

            resultBookmark.setOnClickListener { view ->
                val context = view.context ?: return@setOnClickListener
                context.showBottomDialog(
                    ReadType.entries.map { context.getString(it.stringRes) },
                    selectedIndex = ReadType.entries.map { it.prefValue }
                        .indexOf(viewModel.readState.value?.prefValue),
                    context.getString(R.string.bookmark), false, {}
                ) { selected ->
                    viewModel.bookmark(ReadType.entries[selected].prefValue)
                }
            }

            resultDownloadGenerateEpub.setOnClickListener {
                viewModel.readEpub()
            }

            resultDownloadBtt.setOnLongClickListener { v ->
                val items = getActions() ?: return@setOnLongClickListener true
                v.popupMenu(items.map { it to it }, null) {
                    doAction(itemId)
                }
                return@setOnLongClickListener true
            }

            resultDownloadBtt.setOnClickListener { v ->
                val actions = getActions()
                if (actions == null) {
                    viewModel.downloadOrPause()
                    return@setOnClickListener
                }
                if (actions.size == 1) {
                    doAction(actions[0])
                } else if (actions.contains(R.string.download) || actions.contains(R.string.pause)) {
                    viewModel.downloadOrPause()
                } else {
                    v.popupMenu(actions.map { it to it }, null) {
                        doAction(itemId)
                    }
                }
            }

            resultQuickstream.setOnClickListener {
                viewModel.streamRead()
            }

            resultTranslateBtn.setOnClickListener {
                val response = (viewModel.loadResponse.value as? Resource.Success)?.value as? StreamResponse
                    ?: return@setOnClickListener
                val apiName = viewModel.apiName
                val novelId = viewModel.translatorNovelId(response)
                val chapters = ArrayList(response.data)

                fun openDialog() = TranslateRangeDialogFragment.newInstance(
                    novelId = novelId,
                    apiName = apiName,
                    chapters = chapters,
                    novelAuthor = response.author,
                    novelName = response.name
                ).show(childFragmentManager, "translate_range")

                // An imported book whose language was not detected: ask once, then translate.
                if (viewModel.isImportedBook() && viewModel.sourceLang() == null) {
                    chooseImportedLanguage(novelId) { openDialog() }
                } else {
                    openDialog()
                }
            }

            // Long-press: "Analyze novel…" with the chapter list already loaded on this page
            // (imported books also get "Source language…").
            resultTranslateBtn.setOnLongClickListener { v ->
                val response = (viewModel.loadResponse.value as? Resource.Success)?.value as? StreamResponse
                    ?: return@setOnLongClickListener false
                val imported = viewModel.isImportedBook()
                val items = listOfNotNull(
                    R.string.glossary_menu_analyze to R.string.glossary_menu_analyze,
                    if (imported) R.string.imported_menu_language to R.string.imported_menu_language else null,
                )
                v.popupMenu(items, null) {
                    val apiName = viewModel.apiName
                    val novelId = viewModel.translatorNovelId(response)
                    if (itemId == R.string.imported_menu_language) {
                        chooseImportedLanguage(novelId) {}
                        return@popupMenu
                    }
                    fun analyze() {
                        NovelChapterSource.remember(novelId, apiName, response.name, response.author, response.data)
                        AnalyzeNovelDialogFragment.newInstance(novelId)
                            .show(childFragmentManager, "analyze_novel")
                    }
                    if (imported && viewModel.sourceLang() == null) chooseImportedLanguage(novelId) { analyze() } else analyze()
                }
                true
            }

            resultGlossaryBtn.setOnClickListener {
                val response = (viewModel.loadResponse.value as? Resource.Success)?.value
                    ?: return@setOnClickListener
                openGlossary(viewModel.translatorNovelId(response), response.name)
            }

            /*downloadDeleteTrashFromResult.setOnClickListener {
                viewModel.deleteAlert()
            }*/
        }

        observe(viewModel.currentTabIndex) { pos ->
            binding.apply {
                resultNovelHolder.isVisible = 0 == pos
                // hiddenView.isGone = 0 == pos
                resultReviewsholder.isVisible = 1 == pos
                reviewsFab.isVisible = 1 == pos
                chaptersFab.isVisible = 3 == pos
                resultRelatedholder.isVisible = 2 == pos
                resultChapterholder.isVisible = 3 == pos
            }
        }

        observe(viewModel.currentTabPosition) { pos ->
            if (binding.resultTabs.selectedTabPosition != pos) {
                binding.resultTabs.selectTab(binding.resultTabs.getTabAt(pos))
            }
        }

        observe(viewModel.readState) { state ->
            binding.resultBookmark.setText(if (state == ReadType.NONE) R.string.bookmark else state.stringRes)
            binding.resultBookmark.setCompoundDrawablesWithIntrinsicBounds(
                0,
                0,
                0,
                if (state == ReadType.NONE) R.drawable.ic_baseline_bookmark_border_24 else R.drawable.ic_baseline_bookmark_24
                //if (it == ReadType.NONE) R.drawable.ic_baseline_bookmark_border_24 else R.drawable.ic_baseline_bookmark_24
            )
        }
        observe(viewModel.loadResponse, ::newState)

        observe(translateViewModel.progress) { progress ->
            // Compute the novelId for the book currently shown on this screen.
            val thisNovelId = (viewModel.loadResponse.value as? Resource.Success)
                ?.value?.let { r -> viewModel.translatorNovelId(r) }

            // Only apply button state changes when the translation belongs to this book.
            val isForThisBook = thisNovelId != null &&
                thisNovelId == translateViewModel.currentNovelId

            fun setTranslateEnabled(enabled: Boolean) {
                val ctx = binding.resultTranslateBtn.context
                binding.resultTranslateBtn.isEnabled = enabled
                binding.resultTranslateBtn.backgroundTintList = ColorStateList.valueOf(
                    if (enabled) ctx.colorFromAttribute(R.attr.textColor)
                    else ctx.colorFromAttribute(R.attr.iconGrayBackground)
                )
            }

            when {
                progress == null || !isForThisBook -> {
                    // No active translation for this book — ensure button is usable.
                    setTranslateEnabled(true)
                    binding.resultTranslateBtn.text = "Translate"
                }
                progress.completed -> {
                    setTranslateEnabled(true)
                    binding.resultTranslateBtn.text = "Translate"
                    val name = (viewModel.loadResponse.value as? Resource.Success)?.value?.name
                    if (announcedCompletion !== progress && thisNovelId != null && name != null) {
                        announcedCompletion = progress
                        announceTranslationFinished(progress, thisNovelId, name)
                    }
                }
                progress.error != null -> {
                    setTranslateEnabled(true)
                    binding.resultTranslateBtn.text = "Translate"
                    android.widget.Toast.makeText(requireContext(), "Translation error: ${progress.error}", android.widget.Toast.LENGTH_LONG).show()
                }
                else -> {
                    // In progress for this book — grey out and show count.
                    setTranslateEnabled(false)
                    binding.resultTranslateBtn.text = if (progress.analyzing)
                        progress.currentChapterName // "Analyzing novel… 1/3" before the first translation
                    else if (progress.total > 0)
                        "${progress.current}/${progress.total}"
                    else
                        "Starting…"
                }
            }
        }

        binding.chapterList.apply {
            setRecycledViewPool(ChapterAdapter.sharedPool)
            val isTranslatableSource = viewModel.sourceLang() != null
            val mainPageAdapter = ChapterAdapter(viewModel, isTranslatableSource = isTranslatableSource)
            adapter = mainPageAdapter
            setHasFixedSize(true)
        }

        observeNullable(viewModel.chapters) { chapters ->
            (binding.chapterList.adapter as? ChapterAdapter)?.let { adapter ->
                if (chapters == null || chapters.size > 300) {
                    // if we have too many it takes a long time to diff
                    adapter.submitIncomparableList(chapters)
                } else {
                    adapter.submitList(chapters)
                }
            }
        }

        binding.chaptersFab.setOnClickListener { _ ->
            val act = activity ?: return@setOnClickListener
            val bottomSheetDialog = BottomSheetDialog(act)
            val binding = ChapterFilterPopupBinding.inflate(act.layoutInflater, null, false)
            bottomSheetDialog.setContentView(binding.root)

            val filterTab = binding.filterTabs.newTab().setText(getString(R.string.mainpage_filter))
            val sortTab = binding.filterTabs.newTab()
                .setText(getString(R.string.mainpage_sort_by_button_text))
            binding.filterTabs.addTab(filterTab)
            binding.filterTabs.addTab(sortTab)
            binding.filterTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    val position = tab?.position
                    binding.filterContent.isVisible = position == 0
                    binding.sortContent.isVisible = position == 1
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
                override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            })
            binding.filterTabs.selectTab(filterTab)

            binding.filterBookmarked.isChecked = ResultViewModel.filterChapterByBookmarked
            binding.filterRead.isChecked = ResultViewModel.filterChapterByRead
            binding.filterUnread.isChecked = ResultViewModel.filterChapterByUnread
            binding.filterDownloaded.isChecked = ResultViewModel.filterChapterByDownloads

            binding.filterBookmarked.setOnCheckedChangeListener { _, isChecked ->
                ResultViewModel.filterChapterByBookmarked = isChecked
                viewModel.reorderChapters()
            }

            binding.filterRead.setOnCheckedChangeListener { _, isChecked ->
                ResultViewModel.filterChapterByRead = isChecked
                viewModel.reorderChapters()
            }

            binding.filterUnread.setOnCheckedChangeListener { _, isChecked ->
                ResultViewModel.filterChapterByUnread = isChecked
                viewModel.reorderChapters()
            }

            binding.filterDownloaded.setOnCheckedChangeListener { _, isChecked ->
                ResultViewModel.filterChapterByDownloads = isChecked
                viewModel.reorderChapters()
            }

            val adapter =
                SortingMethodAdapter(ResultViewModel.sortChapterBy) { item, position, newId ->
                    binding.sortContent.adapter?.notifyItemRangeChanged(
                        0,
                        ResultViewModel.chapterSortingMethods.size
                    )
                    ResultViewModel.sortChapterBy = newId
                    viewModel.reorderChapters()
                }
            adapter.submitList(ResultViewModel.chapterSortingMethods.toList())
            binding.sortContent.adapter = adapter
            bottomSheetDialog.show()
        }

        observe(viewModel.downloadState) { progressState ->
            if (progressState == null) {
                //binding.downloadDeleteTrashFromResult.isVisible = false
                return@observe
            }
            //val hasDownload = progressState.progress > 0

            /*binding.downloadDeleteTrashFromResult.apply {
                isVisible = hasDownload
                isClickable = hasDownload
            }*/
            binding.resultDownloadProgressText.text =
                "${progressState.progress}/${progressState.total}"

            binding.resultDownloadProgressBarNotDownloaded.apply {
                println("progressState: ${progressState}")
                max = progressState.total.toInt() * 100
                val animation: ObjectAnimator = ObjectAnimator.ofInt(
                    this,
                    "progress",
                    this.progress,
                    (progressState.progress - progressState.downloaded).toInt() * 100
                )
                animation.duration = 500
                animation.setAutoCancel(true)
                animation.interpolator = DecelerateInterpolator()
                animation.start()
            }

            binding.resultDownloadProgressBar.apply {
                max = progressState.total.toInt() * 100

                val animation: ObjectAnimator = ObjectAnimator.ofInt(
                    this,
                    "progress",
                    this.progress,
                    progressState.progress.toInt() * 100
                )
                animation.duration = 500
                animation.setAutoCancel(true)
                animation.interpolator = DecelerateInterpolator()
                animation.start()
            }

            val ePubGeneration = progressState.progress > 0
            binding.resultDownloadGenerateEpub.apply {
                isClickable = ePubGeneration
                alpha = if (ePubGeneration) 1f else 0.5f
            }

            val canDownload =
                progressState.progress < progressState.total

            val canClick = progressState.total > 0
            binding.resultDownloadBtt.apply {
                isClickable = canClick
                alpha = if (canClick) 1f else 0.5f

                //iconSize = 30.toPx
                setText(
                    when (progressState.state) {
                        DownloadState.IsDone -> R.string.manage
                        DownloadState.IsDownloading -> R.string.pause
                        DownloadState.IsPaused -> R.string.resume
                        DownloadState.IsFailed -> R.string.re_downloaded
                        DownloadState.IsStopped -> R.string.downloaded
                        DownloadState.Nothing -> if (canDownload) {
                            R.string.download
                        } else {
                            R.string.manage
                        }

                        DownloadState.IsPending -> R.string.loading
                    }
                )
                setIconResource(
                    when (progressState.state) {
                        DownloadState.IsDownloading -> R.drawable.ic_baseline_pause_24
                        DownloadState.IsPaused -> R.drawable.netflix_play
                        DownloadState.IsFailed -> R.drawable.ic_baseline_autorenew_24
                        DownloadState.IsDone -> R.drawable.ic_outline_settings_24
                        DownloadState.Nothing -> if (canDownload) {
                            R.drawable.netflix_download
                        } else {
                            R.drawable.ic_outline_settings_24
                        }

                        else -> R.drawable.netflix_download
                    }
                )
            }
        }


        //result_container.setBackgroundColor(requireContext().colorFromAttribute(R.attr.bitDarkerGrayBackground))

        binding.resultMainscroll.setOnScrollChangeListener { v: NestedScrollView, _, scrollY, _, oldScrollY ->
            val arr = IntArray(2)
            binding.resultScrollPadding.getLocationOnScreen(arr)
            /*context?.resources?.displayMetrics?.let { displayMetrics->
                binding.scrollHolder.setPadding(0, (displayMetrics.heightPixels - arr[1]),0,0)
                println("scrolled: ${binding.scrollHolder.paddingTop}")

            }*/
            if (viewModel.isInReviews()) {
                binding.reviewsFab.alpha = scrollY / 50.toPx.toFloat()
            }

            val scrollFade = maxOf(0f, 1 - scrollY / 170.toPx.toFloat())
            binding.resultInfoHeader.apply {
                alpha = scrollFade
                scaleX = 0.95f + scrollFade * 0.05f
                scaleY = 0.95f + scrollFade * 0.05f
            }

            val crossFade = maxOf(0f, 1 - scrollY / 140.toPx.toFloat())
            binding.resultBack.apply {
                alpha = crossFade
                isEnabled = crossFade > 0
            }

            //REVIEWS
            val dy = scrollY - oldScrollY
            if (dy > 0) { //check for scroll down
                val max = (v.getChildAt(0).measuredHeight - v.measuredHeight)
                if (scrollY >= max) {
                    viewModel.loadMoreReviews()
                }
            }
        }
    }
}