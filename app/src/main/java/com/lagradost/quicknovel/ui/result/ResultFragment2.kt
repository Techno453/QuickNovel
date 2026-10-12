package com.lagradost.quicknovel.ui.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.state.ObserveEffect
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.perfToColor
import com.lagradost.cloudstream4.theme.perfToMode
import com.lagradost.quicknovel.MainActivity.Companion.navigate
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.glossary.AnalyzeNovelDialogFragment
import com.lagradost.quicknovel.ui.glossary.GlossaryFragment
import com.lagradost.quicknovel.ui.mainpage.MainPageFragment
import com.lagradost.quicknovel.ui.translate.NovelChapterSource
import com.lagradost.quicknovel.ui.translate.TranslateRangeDialogFragment
import com.lagradost.quicknovel.ui.translate.TranslateViewModel
import com.lagradost.quicknovel.ui.translate.TranslationProgress
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UIHelper.hideKeyboard
import com.mihon.presentation.settings.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The result page (docs/result-page.md): [ResultScreen] state + actions + effects on
 * [ResultViewModel2]. The XML dialogs of the translation pipeline (translate range, analyze) and
 * the glossary screen are opened from here, with the same arguments the old page used.
 */
class ResultFragment2 : Fragment() {
    companion object {
        /** How often the chapter chips (Translated / Source changed / Queued) are refreshed. */
        const val STATUS_REFRESH_MS = 4000L

        fun newInstance(url: String, apiName: String, startAction: Int = 0): Bundle = Bundle().apply {
            putString("url", url)
            putString("apiName", apiName)
            putInt("startAction", startAction)
        }

        /** The completed run that was last announced (the progress LiveData keeps its final value). */
        private var announcedCompletion: TranslationProgress? = null
    }

    private val viewModel: ResultViewModel2 by viewModels {
        ResultViewModel2.provideFactory(requireArguments())
    }
    private val translateViewModel: TranslateViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val settings = rememberAppSettings()
            val mode by settings.ui.theme.collectAsState()
            val primaryColor by settings.ui.primaryColor.collectAsState()
            CloudStreamTheme(
                mode = perfToMode(mode),
                primaryColor = perfToColor(primaryColor),
            ) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ObserveEffect(viewModel.effect, ::onEffect)
                val action = remember(viewModel) { viewModel::onAction }
                ResultScreen(state, action)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.window?.decorView?.clearFocus()
        hideKeyboard()

        translateViewModel.progress.observe(viewLifecycleOwner) { progress ->
            viewModel.onAction(ResultPageAction.TranslationProgressChanged(progress, translateViewModel.currentNovelId))
            val s = viewModel.state.value
            val novelId = s.novelId ?: return@observe
            val name = s.response?.name?.let { ResultPageLogic.displayedTitle(it, s.translatedTitle, s.showOriginal) }
                ?: return@observe
            if (novelId != translateViewModel.currentNovelId || progress == null) return@observe
            if (progress.completed && announcedCompletion !== progress) {
                announcedCompletion = progress
                announceTranslationFinished(progress, novelId, name)
            } else if (progress.error != null && announcedCompletion !== progress) {
                announcedCompletion = progress
                Toast.makeText(requireContext(), getString(R.string.tr_job_failed, progress.error), Toast.LENGTH_LONG).show()
            }
        }

        // Chapter chips: a cheap DB query every few seconds while the page is on screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    try {
                        viewModel.refreshChapterStatuses()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        logError(t)
                    }
                    delay(STATUS_REFRESH_MS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.apply {
            window?.navigationBarColor = colorFromAttribute(R.attr.primaryBlackBackground)
        }
        viewModel.onAction(ResultPageAction.Resumed)
    }

    private fun onEffect(effect: ResultPageEffect) {
        val ctx = context ?: return
        when (effect) {
            is ResultPageEffect.Toast -> Toast.makeText(ctx, effect.text, Toast.LENGTH_SHORT).show()
            is ResultPageEffect.ToastText -> Toast.makeText(ctx, effect.text, Toast.LENGTH_LONG).show()
            is ResultPageEffect.Error -> Toast.makeText(
                ctx, effect.t.message ?: ctx.getString(R.string.error_loading_novel), Toast.LENGTH_LONG
            ).show()

            is ResultPageEffect.OpenTranslateDialog -> TranslateRangeDialogFragment.newInstance(
                novelId = effect.novelId,
                apiName = effect.apiName,
                chapters = effect.chapters,
                novelAuthor = effect.author,
                novelName = effect.name,
                startChapter = effect.startChapter,
            ).show(childFragmentManager, "translate_range")

            is ResultPageEffect.OpenAnalyze -> {
                NovelChapterSource.remember(effect.novelId, effect.apiName, effect.name, effect.author, effect.chapters)
                AnalyzeNovelDialogFragment.newInstance(effect.novelId).show(childFragmentManager, "analyze_novel")
            }

            is ResultPageEffect.OpenGlossary -> openGlossary(effect.novelId, effect.name)
            is ResultPageEffect.OpenTag -> activity?.navigate(
                R.id.global_to_navigation_mainpage,
                MainPageFragment.newInstance(effect.apiName, tag = effect.index)
            )
        }
    }

    private fun openGlossary(novelId: String, novelName: String) {
        activity?.navigate(R.id.global_to_navigation_glossary, GlossaryFragment.newInstance(novelId, novelName))
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
                ctx.resources.getQuantityString(R.plurals.new_terms_to_review, progress.newTermsFound, progress.newTermsFound)
            )
        }
        val msg = parts.joinToString(" · ")
        val root = view
        if (progress.newTermsFound > 0 && root != null) {
            Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
                .setDuration(8000)
                .setAction(R.string.glossary_review_new_terms) { openGlossary(novelId, novelName) }
                .apply {
                    view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 4
                }
                .show()
        } else {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}
