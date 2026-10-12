package com.lagradost.quicknovel.updates

import com.lagradost.quicknovel.DataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The state is stored with the DataStore's Jackson mapper; it must survive a round trip. */
class NovelUpdateStateJsonTest {
    @Test
    fun roundTrip() {
        val state = NovelUpdateState(
            id = -42,
            apiName = "Royal Road",
            url = "https://www.royalroad.com/fiction/1",
            name = "A Novel",
            lastKnownChapterCount = 120,
            lastChapterUrl = "https://www.royalroad.com/fiction/1/chapter/120",
            lastChapterName = "Chapter 120",
            lastCheckedAt = 1_700_000_000_000,
            newChapters = 3,
            lastNewChaptersAt = 1_700_000_000_001,
            lastError = null,
            lastErrorAt = null,
        )
        val json = DataStore.mapper.writeValueAsString(state)
        assertEquals(state, DataStore.mapper.readValue(json, NovelUpdateState::class.java))
    }

    @Test
    fun partialAndUnknownFieldsStillRead() {
        val json = """{"id":7,"newChapters":2,"someFutureField":true}"""
        val state = DataStore.mapper.readValue(json, NovelUpdateState::class.java)
        assertEquals(7, state.id)
        assertEquals(2, state.newChapters)
        assertNull(state.lastKnownChapterCount)
        assertEquals("", state.apiName)
    }
}
