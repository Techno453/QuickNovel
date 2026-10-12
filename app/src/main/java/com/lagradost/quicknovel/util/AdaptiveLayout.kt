package com.lagradost.quicknovel.util

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.max

/**
 * Width breakpoints and helpers for foldables, tablets and split screen.
 *
 * Everything is decided from the *window* width in dp (Configuration.screenWidthDp, or the
 * measured width of the view in question), never from the orientation: the inner screen of a
 * book-style foldable is landscape at ~980 dp, the cover screen portrait at ~475 dp, and an app in
 * split screen can be either. Breakpoints follow the Material window size classes:
 * compact < 600 dp <= medium < 840 dp <= expanded.
 */
object AdaptiveLayout {
    /** Navigation rail instead of a bottom bar from this window width on (medium width class). */
    const val NAV_RAIL_MIN_WIDTH_DP = 600

    /** Treat the window as "wide" for bottom sheets/dialogs from this width on. */
    const val WIDE_WINDOW_MIN_WIDTH_DP = 600

    /** Lists, rows, settings and search fields are centred and capped at this width. */
    const val MAX_CONTENT_WIDTH_DP = 840

    /** Max width of bottom sheets and dialogs on wide windows (Material 3 sheet default). */
    const val MAX_SHEET_WIDTH_DP = 640

    /** Default reader text measure cap (about 75-90 characters per line at the default size). */
    const val DEFAULT_READER_MEASURE_DP = 640

    /** Minimum width of a poster cell. 3 columns on any phone in portrait, 6 in landscape. */
    const val POSTER_MIN_CELL_DP = 128

    /** Minimum width of a list row: 1 column in portrait, 2 on landscape phones/inner screens. */
    const val ROW_MIN_CELL_DP = 360

    /** Windows shorter than this open bottom sheets expanded (landscape phones). */
    private const val SHORT_WINDOW_MAX_HEIGHT_DP = 480

    fun columnCount(availablePx: Int, minCellPx: Int, spacingPx: Int = 0, minCount: Int = 1): Int {
        if (minCellPx <= 0) return max(1, minCount)
        val fit = (availablePx + spacingPx) / (minCellPx + spacingPx)
        return max(max(1, minCount), fit)
    }

    /**
     * Side padding that centres a text column of at most [maxMeasurePx] inside [widthPx], but is
     * never smaller than the user's own padding. [maxMeasurePx] <= 0 disables the cap.
     */
    fun centeredPadding(widthPx: Int, userPaddingPx: Int, maxMeasurePx: Int): Int {
        if (maxMeasurePx <= 0 || widthPx <= 0) return userPaddingPx
        return max(userPaddingPx, (widthPx - maxMeasurePx) / 2)
    }

    /**
     * Caps this view's width at [maxDp] inside its parent (the view should be centred with
     * layout_gravity="center_horizontal"). Re-evaluated whenever the parent is resized.
     */
    fun View.capWidth(maxDp: Int = MAX_CONTENT_WIDTH_DP) {
        val parentView = parent as? View ?: return
        val maxPx = (maxDp * resources.displayMetrics.density).toInt()
        fun apply() {
            val available = parentView.width - parentView.paddingLeft - parentView.paddingRight
            if (available <= 0) return
            val target = if (available > maxPx) maxPx else ViewGroup.LayoutParams.MATCH_PARENT
            if (layoutParams.width != target) {
                layoutParams = layoutParams.also { it.width = target }
            }
        }
        parentView.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) post { apply() }
        }
        if (parentView.width > 0) apply()
    }

    fun Configuration.isWideWindow(): Boolean = screenWidthDp >= WIDE_WINDOW_MIN_WIDTH_DP
    fun Configuration.useNavigationRail(): Boolean = screenWidthDp >= NAV_RAIL_MIN_WIDTH_DP
    private fun Configuration.isShortWindow(): Boolean =
        screenHeightDp in 1 until SHORT_WINDOW_MAX_HEIGHT_DP

    fun Context.isWideWindow(): Boolean = resources.configuration.isWideWindow()

    /**
     * Caps the sheet at [MAX_SHEET_WIDTH_DP] (centred by BottomSheetDialog) and, on wide or short
     * windows, opens it fully expanded instead of at a peek height that hides most of it.
     * Safe to call before or after show().
     */
    fun BottomSheetDialog.adaptToWindow() {
        val ctx = context
        val density = ctx.resources.displayMetrics.density
        behavior.maxWidth = (MAX_SHEET_WIDTH_DP * density).toInt()
        val config = ctx.resources.configuration
        if (config.isWideWindow() || config.isShortWindow()) {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /** Caps a (non bottom sheet) dialog window at [MAX_SHEET_WIDTH_DP] on wide windows. */
    fun Dialog.capWidthOnWideWindow() {
        val win = window ?: return
        val ctx = context
        if (!ctx.isWideWindow()) return
        val density = ctx.resources.displayMetrics.density
        val maxPx = (MAX_SHEET_WIDTH_DP * density).toInt()
        val screenPx = (ctx.resources.configuration.screenWidthDp * density).toInt()
        if (screenPx > maxPx) {
            win.setLayout(maxPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
    }
}
