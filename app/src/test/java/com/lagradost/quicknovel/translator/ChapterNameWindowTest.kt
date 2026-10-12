package com.lagradost.quicknovel.translator

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterNameWindowTest {
    /** 1,400 chapter names, as a long zh novel has. */
    private val names = (1..1400).map { "第${it}章 名字$it" }

    private fun window(debounce: Long = 300, behind: Int = 2, ahead: Int = 3, max: Int = 40) =
        ChapterNameWindow(debounceMs = debounce, lookBehind = behind, lookAhead = ahead, maxPerRequest = max)

    @Test
    fun nothingIsRequestedBeforeTheListIsStill() {
        val w = window()
        assertTrue(w.next(names, now = 0).isEmpty()) // nothing reported yet
        w.onVisible(10, 14, now = 1_000)
        assertTrue(w.next(names, now = 1_299).isEmpty())
        assertEquals(1, w.dueIn(1_299))
        assertEquals(10, w.next(names, now = 1_300).size)
    }

    @Test
    fun scrollingRestartsTheDebounce() {
        val w = window()
        w.onVisible(0, 4, now = 0)
        w.onVisible(1, 5, now = 200)
        w.onVisible(2, 6, now = 400)
        assertFalse(w.isDue(600))
        assertTrue(w.isDue(700))
        // The same rows again do not restart the clock.
        assertFalse(w.onVisible(2, 6, now = 650))
        assertTrue(w.isDue(700))
    }

    @Test
    fun visibleRowsFirstThenLookaheadThenLookbehind() {
        val w = window(behind = 2, ahead = 3)
        w.onVisible(10, 12, now = 0)
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 9, 8), w.order(names.size))
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 9, 8).map { names[it] }, w.next(names, now = 300))
    }

    @Test
    fun windowIsClampedToTheList() {
        val w = window(behind = 5, ahead = 5)
        w.onVisible(0, 2, now = 0)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), w.order(names.size))
        w.onVisible(1397, 1399, now = 0)
        assertEquals(listOf(1397, 1398, 1399, 1396, 1395, 1394, 1393, 1392), w.order(names.size))
        w.onVisible(50, 60, now = 0)
        assertTrue(w.order(10).isEmpty()) // the list got shorter (filter)
        assertEquals(listOf(5, 6, 7, 8, 9, 4, 3, 2, 1, 0), window(behind = 5, ahead = 5).apply { onVisible(5, 20, 0) }.order(10))
    }

    @Test
    fun neverMoreThanTheVisibleWindowEvenForAHugeList() {
        val w = window(behind = 5, ahead = 15, max = 40)
        w.onVisible(700, 712, now = 0)
        val batch = w.next(names, now = 300)
        assertEquals(13 + 15 + 5, batch.size)
        assertTrue(w.next(names, now = 300).isEmpty()) // all requested already
        assertEquals(33, w.requestedCount)
    }

    @Test
    fun batchesAreCappedAndTheRestFollows() {
        val w = window(behind = 0, ahead = 0, max = 20)
        w.onVisible(0, 49, now = 0)
        assertEquals(names.subList(0, 20), w.next(names, now = 300))
        assertEquals(names.subList(20, 40), w.next(names, now = 300))
        assertEquals(names.subList(40, 50), w.next(names, now = 300))
        assertTrue(w.next(names, now = 300).isEmpty())
    }

    @Test
    fun skipsRequestedKnownDuplicateAndUntranslatableNames() {
        val list = listOf("第1章 开始", "Chapter 2", "第1章 开始", "第3章 夜", "  ", "第4章 雨")
        val w = window(behind = 0, ahead = 0)
        w.onVisible(0, 5, now = 0)
        assertEquals(listOf("第1章 开始", "第4章 雨"), w.next(list, now = 300) { it == "第3章 夜" })
        // Scrolling back over requested rows asks for nothing new.
        w.onVisible(0, 1, now = 400)
        assertTrue(w.next(list, now = 700).isEmpty())
    }

    @Test
    fun releasedNamesAreRequestedAgainAndResetForgetsAll() {
        val w = window(behind = 0, ahead = 0)
        w.onVisible(0, 1, now = 0)
        val first = w.next(names, now = 300)
        w.release(first)
        assertEquals(first, w.next(names, now = 300))
        w.reset()
        assertTrue(w.next(names, now = 10_000).isEmpty()) // nothing visible after a reset
        w.onVisible(0, 1, now = 10_000)
        assertEquals(first, w.next(names, now = 10_300))
    }

    @Test
    fun driverDebouncesAFlingIntoOneBatchForTheFinalWindow() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val delivered = mutableMapOf<String, String>()
        val driver = ChapterNameTranslator(
            scope = this,
            translate = { batch -> calls += batch; batch.associateWith { "EN $it" } },
            onTranslated = { delivered += it },
            window = window(debounce = 60, behind = 1, ahead = 2),
        )
        // A fling: the window moves every 10 ms, never still for 60 ms.
        for (row in 0 until 500 step 50) {
            driver.visible(names, row, row + 9)
            delay(10)
        }
        delay(200)
        assertEquals(1, calls.size)
        assertEquals((450..461).map { names[it] } + names[449], calls.single())
        assertEquals("EN ${names[455]}", delivered[names[455]])
        // Same rows again: nothing new is asked for.
        driver.visible(names, 450, 459)
        delay(150)
        assertEquals(1, calls.size)
    }

    @Test
    fun driverDeliversMemoryHitsAtOnceAndDoesNotAskForThem() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val delivered = mutableMapOf<String, String>()
        val driver = ChapterNameTranslator(
            scope = this,
            translate = { batch -> calls += batch; batch.associateWith { "EN $it" } },
            onTranslated = { delivered += it },
            cached = { name -> if (name == names[1]) "cached" else null },
            window = window(debounce = 50, behind = 0, ahead = 0),
        )
        driver.visible(names, 0, 2)
        assertEquals(mapOf(names[1] to "cached"), delivered) // before the debounce
        delay(150)
        assertEquals(listOf(listOf(names[0], names[2])), calls)
    }

    @Test
    fun driverKeepsRequestsInFlightWhenScrollingOn() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val delivered = mutableMapOf<String, String>()
        val driver = ChapterNameTranslator(
            scope = this,
            translate = { batch -> calls += batch; delay(100); batch.associateWith { "EN $it" } },
            onTranslated = { delivered += it },
            window = window(debounce = 20, behind = 0, ahead = 0),
        )
        driver.visible(names, 0, 1)
        delay(50) // the first request is in flight
        driver.visible(names, 2, 3)
        delay(250)
        assertEquals(listOf(names.subList(0, 2), names.subList(2, 4)), calls)
        assertEquals(4, delivered.size)
    }

    @Test
    fun cancelledRequestsAreReleased() = runBlocking {
        val w = window(debounce = 10, behind = 0, ahead = 0)
        val job = launch {
            val driver = ChapterNameTranslator(
                scope = this,
                translate = { delay(10_000); emptyMap() },
                onTranslated = {},
                window = w,
            )
            driver.visible(names, 0, 1)
        }
        delay(100)
        assertEquals(2, w.requestedCount)
        job.cancelAndJoin()
        assertEquals(0, w.requestedCount)
    }
}
