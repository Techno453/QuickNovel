package com.lagradost.quicknovel.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Column counts and reader measure for the window shapes we care about. Widths are the content
 * width in dp the grid actually gets (window minus padding / navigation rail), computed at 1 px/dp.
 */
class AdaptiveLayoutTest {
    private fun posters(widthDp: Int) = AdaptiveLayout.columnCount(
        availablePx = widthDp - 8, // 4 dp content padding each side
        minCellPx = AdaptiveLayout.POSTER_MIN_CELL_DP,
        spacingPx = 4,
        minCount = 3
    )

    private fun rows(widthDp: Int) = AdaptiveLayout.columnCount(
        availablePx = widthDp - 20, // 10 dp gutter each side
        minCellPx = AdaptiveLayout.ROW_MIN_CELL_DP,
        spacingPx = 10,
    )

    @Test
    fun phonesKeepTheirOldColumnCounts() {
        // portrait: 3 posters / 1 row on 360-430 dp phones
        for (w in listOf(360, 393, 411, 430)) {
            assertEquals("posters at $w dp", 3, posters(w))
            assertEquals("rows at $w dp", 1, rows(w))
        }
        // landscape (bottom bar or rail): 6 posters / 2 rows
        assertEquals(6, posters(914))
        assertEquals(6, posters(914 - 80))
        assertEquals(2, rows(914 - 80))
    }

    @Test
    fun foldShapes() {
        // Fold 8 cover screen, ~475 dp portrait
        assertEquals(3, posters(475))
        assertEquals(1, rows(475))
        // Fold 8 inner screen, ~979 dp landscape, minus the 80 dp navigation rail
        assertEquals(6, posters(979 - 80))
        assertEquals(2, rows(979 - 80))
    }

    @Test
    fun readerMeasureIsCentredAndNeverBelowUserPadding() {
        // phone: the cap does not apply, user padding wins
        assertEquals(20, AdaptiveLayout.centeredPadding(411, 20, 640))
        // inner screen: (979 - 640) / 2
        assertEquals(169, AdaptiveLayout.centeredPadding(979, 20, 640))
        // a bigger user padding still wins
        assertEquals(200, AdaptiveLayout.centeredPadding(979, 200, 640))
        // 0 = no limit
        assertEquals(20, AdaptiveLayout.centeredPadding(979, 20, 0))
    }
}
