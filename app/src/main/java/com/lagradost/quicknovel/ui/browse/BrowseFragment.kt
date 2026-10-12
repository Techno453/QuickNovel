package com.lagradost.quicknovel.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.perfToColor
import com.lagradost.cloudstream4.theme.perfToMode
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.MainActivity.Companion.navigate
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.common.SearchResponseAction
import com.lagradost.quicknovel.ui.mainpage.MainPageFragment
import com.mihon.presentation.settings.collectAsState

/** The Browse tab (docs/browse.md). */
class BrowseFragment : Fragment() {
    companion object {
        /** Opens the per-source browse page; its last listing is restored there. */
        fun openSource(apiName: String) {
            activity?.navigate(R.id.global_to_navigation_mainpage, MainPageFragment.newInstance(apiName))
        }
    }

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
            val viewModel: BrowseViewModel = viewModel()
            CloudStreamTheme(mode = perfToMode(mode), primaryColor = perfToColor(primaryColor)) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val actions = remember(viewModel) { Actions(viewModel) }
                BrowseScreen(state, actions)
            }
        }
    }

    private class Actions(private val vm: BrowseViewModel) : BrowseActions {
        override fun ensureRail(apiName: String) = vm.ensureRail(apiName)
        override fun retryRail(apiName: String) = vm.ensureRail(apiName, refresh = true)
        override fun openSource(apiName: String) = BrowseFragment.openSource(apiName)
        override fun toggleOther() = vm.toggleOther()
        override fun openAllSources() = vm.openAllSources()
        override fun closeAllSources() = vm.closeAllSources()
        override fun selectFeed(language: String?, kind: BrowseCatalog.ListingKind) = vm.selectFeed(language, kind)
        override fun feedMore() {
            vm.feedMore()
        }

        override fun refreshFeed() = vm.refreshFeed()
        override fun onPoster(action: SearchResponseAction) {
            try {
                action.doAction() // Open → result page (loadResult), long press → preview sheet
            } catch (e: NotImplementedError) {
                logError(e)
            }
        }
    }
}
