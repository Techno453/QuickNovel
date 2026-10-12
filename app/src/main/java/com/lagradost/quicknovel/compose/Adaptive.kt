package com.lagradost.quicknovel.compose

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.quicknovel.util.AdaptiveLayout

/** Rows, text fields and settings lists never get wider than this, see [AdaptiveLayout]. */
val MaxContentWidth: Dp = AdaptiveLayout.MAX_CONTENT_WIDTH_DP.dp

/**
 * Horizontal content padding that centres content of at most [maxContent] in the available width,
 * but is never smaller than [minGutter]. Use as contentPadding so lists stay scrollable edge to edge.
 */
fun BoxWithConstraintsScope.centeredGutter(minGutter: Dp, maxContent: Dp = MaxContentWidth): Dp =
    ((maxWidth - maxContent) / 2).coerceAtLeast(minGutter)

/** Poster covers in grids (search results, library grid, provider cards). */
val PosterMinCellWidth: Dp = AdaptiveLayout.POSTER_MIN_CELL_DP.dp

/** Search/library/history list rows (poster + text). */
val RowMinCellWidth: Dp = AdaptiveLayout.ROW_MIN_CELL_DP.dp

/**
 * Like [GridCells.Adaptive], but never fewer than [minCount] columns.
 *
 * Column count is derived from the width the grid actually gets (so it reacts to split screen,
 * the navigation rail and fold/unfold), not from the device orientation. The [minCount] keeps
 * small phones (360 dp) at the same column count they always had.
 */
class AdaptiveAtLeast(
    private val minSize: Dp,
    private val minCount: Int = 1,
) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(
        availableSize: Int,
        spacing: Int
    ): List<Int> {
        val count = AdaptiveLayout.columnCount(
            availablePx = availableSize,
            minCellPx = minSize.roundToPx(),
            spacingPx = spacing,
            minCount = minCount
        )
        val gridSize = availableSize - spacing * (count - 1)
        val slotSize = gridSize / count
        val remaining = gridSize % count
        return List(count) { slotSize + if (it < remaining) 1 else 0 }
    }

    override fun hashCode(): Int = minSize.hashCode() * 31 + minCount

    override fun equals(other: Any?): Boolean =
        other is AdaptiveAtLeast && minSize == other.minSize && minCount == other.minCount
}
