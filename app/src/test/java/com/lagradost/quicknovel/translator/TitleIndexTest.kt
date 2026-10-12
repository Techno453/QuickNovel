package com.lagradost.quicknovel.translator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The combined key-free title index: NovelUpdates names first, Novel Buddy names after, distinct. */
class TitleIndexTest {
    @Test
    fun mergesBothIndexesNovelUpdatesFirst() = runBlocking {
        val names = TitleIndex.associatedNames(
            "Above the sky",
            novelUpdates = { listOf("高天之上", "높은 하늘 위") },
            novelBuddy = { listOf("高天之上", "天外天") },
        )
        assertEquals(listOf("高天之上", "높은 하늘 위", "天外天"), names)
    }

    @Test
    fun oneIndexFailingLeavesTheOthersAnswer() = runBlocking {
        assertEquals(
            listOf("高天之上"),
            TitleIndex.associatedNames("Above the sky", novelUpdates = { emptyList() }, novelBuddy = { listOf("高天之上") }),
        )
        assertEquals(
            listOf("高天之上"),
            TitleIndex.associatedNames("Above the sky", novelUpdates = { listOf("高天之上") }, novelBuddy = { emptyList() }),
        )
    }
}
