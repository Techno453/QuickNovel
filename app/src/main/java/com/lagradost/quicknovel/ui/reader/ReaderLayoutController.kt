package com.lagradost.quicknovel.ui.reader

import android.content.res.Configuration
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.util.AdaptiveLayout
import kotlinx.coroutines.launch

/**
 * Geometry of the text area: the user's side/top margins, the centred text column capped at
 * [ReadActivityViewModel.maxTextWidth] on wide windows (foldable inner screen, tablets,
 * landscape), density changes between the cover and inner screen, and the tabletop posture
 * (text kept above a horizontal hinge, controls and settings below it).
 */
class ReaderLayoutController(
    private val activity: AppCompatActivity,
    private val binding: ReadMainBinding,
    private val viewModel: ReadActivityViewModel,
    private val host: Host,
) {
    interface Host {
        /** The text padding changed (margins, column cap, window width): keep the position. */
        fun onTextAreaChanged()

        /** Density changed: rebind the text so sp sizes use the new density. */
        fun onDensityChanged()

        /** Before a configuration change or posture change: re-seek to the saved position once laid out. */
        fun keepPositionAfterLayout()
    }

    /** Height of the lower half below a horizontal hinge in tabletop posture, 0 otherwise. */
    var tabletopLowerHeight = 0
        private set

    /** Current horizontal and vertical padding of the text area in px. */
    var horizontalPadding = 0
        private set
    var verticalPadding = 0
        private set

    private var lastDensityDpi = 0

    fun bind() {
        activity.observe(viewModel.paddingHorizontalLive) {
            updatePadding()
        }

        activity.observe(viewModel.paddingVerticalLive) {
            updatePadding()
        }

        activity.observe(viewModel.maxTextWidthLive) {
            updatePadding()
        }

        lastDensityDpi = activity.resources.configuration.densityDpi
        // Re-centre the text column whenever the window width changes (fold/unfold, split screen,
        // rotation). The host's onTextAreaChanged keeps the reading position.
        binding.readNormalLayout.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                v.post { updatePadding() }
            }
        }

        // foldable postures, see applyWindowLayout
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(activity)
                    .windowLayoutInfo(activity)
                    .collect { info -> binding.root.post { applyWindowLayout(info) } }
            }
        }
    }

    /** Call from Activity.onConfigurationChanged after super. */
    fun onConfigurationChanged(newConfig: Configuration) {
        // Foldables can change density between the cover and the inner screen; we handle that
        // ourselves (manifest configChanges) so rebind the text so sp sizes use the new density.
        if (lastDensityDpi != 0 && newConfig.densityDpi != lastDensityDpi) {
            host.onDensityChanged()
        }
        lastDensityDpi = newConfig.densityDpi
        // the new window width arrives with the next layout, see the realText layout listener
        updatePadding()
    }

    /**
     * Tabletop posture (foldable half opened with a horizontal hinge): keep the text in the
     * upper half and leave the lower half to the controls and the settings sheet. No-op on
     * devices without a folding feature.
     */
    private fun applyWindowLayout(info: WindowLayoutInfo) {
        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
            it.state == FoldingFeature.State.HALF_OPENED &&
                    it.orientation == FoldingFeature.Orientation.HORIZONTAL
        }
        val root = binding.root
        var lowerHeight = 0
        if (fold != null && root.height > 0) {
            val location = IntArray(2)
            root.getLocationInWindow(location)
            val foldTop = fold.bounds.top - location[1]
            val foldBottom = fold.bounds.bottom - location[1]
            if (foldTop > 0 && foldTop < root.height) {
                lowerHeight = (root.height - foldBottom).coerceAtLeast(0)
                binding.readNormalLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    bottomMargin = root.height - foldTop
                }
            }
        }
        if (lowerHeight == 0) {
            binding.readNormalLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = 0
            }
        }
        if (lowerHeight != tabletopLowerHeight) {
            tabletopLowerHeight = lowerHeight
            host.keepPositionAfterLayout()
        }
    }

    fun updatePadding() {
        val density = activity.resources.displayMetrics.density
        val userPad = (viewModel.paddingHorizontal * density).toInt()
        val v = (viewModel.paddingVertical * density).toInt()
        // Cap the line length on wide windows (foldable inner screen, tablets, landscape) by
        // centring a column of at most maxTextWidth dp; never narrower than the user's margin.
        // (the whole reader width: the scroll list and the paged view fill it)
        val width = binding.readNormalLayout.width.takeIf { it > 0 }
            ?: (activity.resources.configuration.screenWidthDp * density).toInt()
        val h = AdaptiveLayout.centeredPadding(
            widthPx = width,
            userPaddingPx = userPad,
            maxMeasurePx = (viewModel.maxTextWidth * density).toInt()
        )
        // keep the bottom actions and the clock/battery overlay aligned with the text column
        val extra = h - userPad
        val overlayPad = (10 * density).toInt() + extra
        binding.readOverlay.apply {
            if (paddingLeft != overlayPad || paddingRight != overlayPad) {
                setPadding(overlayPad, paddingTop, overlayPad, paddingBottom)
            }
        }
        for (bar in listOf(binding.readerBottomView, binding.readerBottomViewTts)) {
            if (bar.paddingLeft != extra || bar.paddingRight != extra) {
                bar.setPadding(extra, bar.paddingTop, extra, bar.paddingBottom)
            }
        }
        horizontalPadding = h
        verticalPadding = v
        binding.realText.apply {
            if (paddingLeft == h && paddingRight == h && paddingBottom == v && paddingTop == v) return
            setPadding(
                h,
                v,
                h,
                v
            )
            host.onTextAreaChanged()
        }
    }
}
