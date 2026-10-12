package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAheadTest {

    @Test
    fun planCoversTheNextNChaptersThatNeedTranslation() {
        assertEquals(listOf(6, 7), ReadAheadPlanner.plan(current = 5, ahead = 2, chapterCount = 100, translated = emptySet()))
        assertEquals(listOf(7, 8), ReadAheadPlanner.plan(5, 3, 100, translated = setOf(6)))
        assertEquals(listOf(8), ReadAheadPlanner.plan(5, 3, 100, translated = setOf(6), inFlight = setOf(7)))
        assertEquals(emptyList<Int>(), ReadAheadPlanner.plan(5, 2, 100, translated = setOf(6, 7)))
    }

    @Test
    fun planIsOffForZeroAndStopsAtTheLastChapter() {
        assertEquals(emptyList<Int>(), ReadAheadPlanner.plan(5, 0, 100, emptySet()))
        assertEquals(listOf(9), ReadAheadPlanner.plan(8, 5, 10, emptySet()))
        assertEquals(emptyList<Int>(), ReadAheadPlanner.plan(9, 5, 10, emptySet()))
        assertEquals(emptyList<Int>(), ReadAheadPlanner.plan(0, 5, 0, emptySet()))
        assertEquals(listOf(0, 1), ReadAheadPlanner.plan(-1, 2, 10, emptySet()))
        assertEquals(listOf(6), ReadAheadPlanner.plan(5, Int.MAX_VALUE, 7, emptySet()))
    }

    @Test
    fun neverQueuesBehindTheCurrentChapter() {
        val plan = ReadAheadPlanner.plan(current = 20, ahead = 10, chapterCount = 100, translated = emptySet())
        assertTrue(plan.all { it > 20 })
        assertEquals(21, plan.first())
        assertEquals(30, plan.last())
    }

    @Test
    fun queueClaimsOneWorkerAndDrainsInOrder() {
        val q = ReadAheadQueue()
        assertTrue("first update must start a worker", q.update("n", 5, 2, 100, emptySet()))
        assertFalse("a worker already owns the queue", q.update("n", 5, 2, 100, emptySet()))
        assertEquals(ReadAheadQueue.Item("n", 6), q.poll())
        assertEquals(ReadAheadQueue.Item("n", 7), q.poll())
        assertNull(q.poll())
        assertFalse(q.isWorkerClaimed)
        assertTrue("after the worker released the claim, new work starts a new one", q.update("n", 7, 2, 100, setOf(6, 7)))
    }

    @Test
    fun movingForwardTopsUpAndDropsChaptersThatFellBehind() {
        val q = ReadAheadQueue()
        q.update("n", 5, 3, 100, emptySet())
        val inFlight = q.poll()!! // chapter 6 being translated
        assertEquals(listOf(7, 8), q.pendingChapters)

        // The reader moves on to chapter 6: 7, 8 stay, 9 is added, 6 is not queued twice.
        q.update("n", 6, 3, 100, emptySet())
        assertEquals(listOf(7, 8, 9), q.pendingChapters)

        // Jump far ahead: everything near the old position is dropped.
        q.update("n", 50, 2, 100, emptySet())
        assertEquals(listOf(51, 52), q.pendingChapters)

        // Jump back: nothing behind the current chapter, already translated chapters skipped.
        q.done(inFlight)
        q.update("n", 3, 3, 100, setOf(5))
        assertEquals(listOf(4, 6), q.pendingChapters)
    }

    @Test
    fun deDuplicatesAgainstInFlightChapters() {
        val q = ReadAheadQueue()
        q.update("n", 0, 2, 10, emptySet())
        val item = q.poll()!!
        assertEquals(1, item.chapter)
        q.update("n", 0, 2, 10, emptySet())
        assertEquals("chapter 1 is in flight", listOf(2), q.pendingChapters)
        q.done(item)
        q.update("n", 0, 2, 10, emptySet())
        assertEquals(listOf(1, 2), q.pendingChapters)
    }

    @Test
    fun switchingNovelsReplacesTheQueue() {
        val q = ReadAheadQueue()
        q.update("a", 0, 2, 10, emptySet())
        q.update("b", 10, 2, 50, emptySet())
        assertEquals("b", q.currentNovelId)
        assertEquals(ReadAheadQueue.Item("b", 11), q.poll())
    }

    @Test
    fun clearStopsTheWorkerAtItsNextPoll() {
        val q = ReadAheadQueue()
        assertTrue(q.update("n", 0, 3, 10, emptySet()))
        q.clear()
        assertFalse(q.hasPending)
        assertTrue(q.isWorkerClaimed)
        assertNull(q.poll())
        assertFalse(q.isWorkerClaimed)
    }

    @Test
    fun releaseWorkerLetsTheNextUpdateStartOne() {
        val q = ReadAheadQueue()
        assertTrue(q.update("n", 0, 3, 10, emptySet()))
        q.releaseWorker() // service destroyed mid-queue
        assertTrue(q.update("n", 0, 3, 10, emptySet()))
    }
}
