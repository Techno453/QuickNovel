package com.lagradost.quicknovel.ui.home

import android.view.View
import androidx.fragment.app.viewModels
import com.lagradost.quicknovel.databinding.FragmentHomeBinding
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.ui.BaseFragment
import com.lagradost.quicknovel.util.AdaptiveLayout
import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar

class HomeFragment : BaseFragment<FragmentHomeBinding>(
    BindingCreator.Inflate(FragmentHomeBinding::inflate)
) {
    private val viewModel: HomeViewModel by viewModels()

    override fun fixLayout(view: View) {
        // columns from the available width, not the orientation (foldables, split screen)
        binding?.homeBrowselist?.setAdaptiveColumns(AdaptiveLayout.POSTER_MIN_CELL_DP, minCount = 3)
    }

    override fun onBindingCreated(binding: FragmentHomeBinding) {
        val browseAdapter = BrowseAdapter()
        binding.homeBrowselist.apply {
            adapter = browseAdapter
            // layoutManager = GridLayoutManager(context, 1)
            setHasFixedSize(true)
        }

        observe(viewModel.homeApis) { list ->
            browseAdapter.submitList(list)
        }

        activity?.fixPaddingStatusbar(binding.homeToolbar)
    }
}