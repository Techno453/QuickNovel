package com.lagradost.quicknovel.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class LibraryUpdatePlanningTest {
    private fun candidate(id: Int, api: String, lastChecked: Long?, hint: Int? = null) =
        CheckCandidate(id, api, "https://$api/$id", "Novel $id", hint, lastChecked)

    @Test
    fun frequencyKeysAndDefault() {
        assertEquals(UpdateFrequency.Daily, UpdateFrequency.fromKey(null))
        assertEquals(UpdateFrequency.Daily, UpdateFrequency.fromKey("bogus"))
        assertEquals(UpdateFrequency.Off, UpdateFrequency.fromKey("0"))
        assertEquals(6L, UpdateFrequency.fromKey("6").hours)
        assertEquals(12L, UpdateFrequency.fromKey("12").hours)
        assertEquals(24L, UpdateFrequency.fromKey("24").hours)
    }

    @Test
    fun categoryDefaultsAndParsing() {
        assertEquals(
            setOf(LibraryCategory.Reading, LibraryCategory.PlanToRead, LibraryCategory.Downloads),
            LibraryCategory.fromKeys(null)
        )
        assertEquals(setOf("reading", "plan_to_read", "downloads"), LibraryCategory.DEFAULT_KEYS)
        assertEquals(emptySet<LibraryCategory>(), LibraryCategory.fromKeys(emptySet()))
        assertEquals(
            setOf(LibraryCategory.Dropped),
            LibraryCategory.fromKeys(setOf("dropped", "unknown"))
        )
    }

    @Test
    fun bookmarkStatesMapToCategories() {
        // ReadType prefValues: PLAN_TO_READ 1, DROPPED 2, COMPLETED 3, ON_HOLD 4, READING 5
        assertEquals(LibraryCategory.PlanToRead, LibraryCategory.fromBookmarkState(1))
        assertEquals(LibraryCategory.Dropped, LibraryCategory.fromBookmarkState(2))
        assertEquals(LibraryCategory.Completed, LibraryCategory.fromBookmarkState(3))
        assertEquals(LibraryCategory.OnHold, LibraryCategory.fromBookmarkState(4))
        assertEquals(LibraryCategory.Reading, LibraryCategory.fromBookmarkState(5))
        assertEquals(null, LibraryCategory.fromBookmarkState(0))
        assertEquals(null, LibraryCategory.fromBookmarkState(null))
    }

    @Test
    fun plannerPutsNeverCheckedFirstThenOldest() {
        val plan = CheckPlanner.order(
            listOf(
                candidate(1, "a", 300),
                candidate(2, "b", null),
                candidate(3, "c", 100),
            )
        )
        assertEquals(listOf(2, 3, 1), plan.map { it.id })
    }

    @Test
    fun plannerRotatesBetweenSites() {
        val plan = CheckPlanner.order(
            listOf(
                candidate(1, "a", 1),
                candidate(2, "a", 2),
                candidate(3, "a", 3),
                candidate(4, "b", 4),
                candidate(5, "b", 5),
                candidate(6, "c", 6),
            )
        )
        assertEquals(listOf(1, 4, 6, 2, 5, 3), plan.map { it.id })
        // never the same site twice in a row while another site still has work
        assertEquals(listOf("a", "b", "c", "a", "b", "a"), plan.map { it.apiName })
    }

    @Test
    fun plannerMergesDuplicatesPreferringTheOneWithAHint() {
        val plan = CheckPlanner.order(
            listOf(
                candidate(1, "a", null, hint = null),
                candidate(1, "a", null, hint = 40),
                candidate(2, "a", null),
            )
        )
        assertEquals(listOf(1, 2), plan.map { it.id })
        assertEquals(40, plan.first().baselineHint)
    }

    @Test
    fun plannerHandlesEmptyInput() {
        assertTrue(CheckPlanner.order(emptyList()).isEmpty())
    }

    @Test
    fun throttleKeepsTheGapPerSite() {
        val throttle = SiteThrottle(1_500)
        assertEquals(0, throttle.waitMs("a", 1_000))
        throttle.record("a", 1_000)
        assertEquals(1_500, throttle.waitMs("a", 1_000))
        assertEquals(500, throttle.waitMs("a", 2_000))
        assertEquals(0, throttle.waitMs("a", 2_500))
        assertEquals(0, throttle.waitMs("a", 9_000))
        assertEquals(0, throttle.waitMs("b", 1_000))
    }

    @Test
    fun siteIsSkippedAfterTwoSiteLevelFailuresInARow() {
        val health = SiteHealth(maxConsecutiveFailures = 2)
        health.recordFailure("a", siteLevel = true, message = "Connection Timeout\nPlease try again later.")
        assertFalse(health.isSkipped("a"))
        health.recordFailure("a", siteLevel = true, message = "Connection Timeout\nPlease try again later.")
        assertTrue(health.isSkipped("a"))
        assertEquals("Skipped: a looks blocked or down (Connection Timeout)", health.skipReason("a"))
        assertFalse(health.isSkipped("b"))
    }

    @Test
    fun novelLevelFailuresAndSuccessesDoNotSkipTheSite() {
        val health = SiteHealth(maxConsecutiveFailures = 2)
        health.recordFailure("a", siteLevel = false, message = "NullPointerException")
        health.recordFailure("a", siteLevel = false, message = "NullPointerException")
        assertFalse(health.isSkipped("a"))

        health.recordFailure("a", siteLevel = true, message = "timeout")
        health.recordSuccess("a")
        health.recordFailure("a", siteLevel = true, message = "timeout")
        assertFalse(health.isSkipped("a"))
    }

    @Test
    fun failureClassification() {
        assertTrue(FailureClassifier.isSiteLevel(UnknownHostException("x"), "Cannot connect"))
        assertTrue(FailureClassifier.isSiteLevel(SocketTimeoutException(), "Connection Timeout"))
        assertTrue(FailureClassifier.isSiteLevel(SSLHandshakeException("bad"), "bad"))
        assertTrue(FailureClassifier.isSiteLevel(RuntimeException(UnknownHostException()), "wrapped"))
        assertTrue(FailureClassifier.isSiteLevel(IOException("unexpected end of stream"), "x"))
        assertTrue(FailureClassifier.isSiteLevel(null, "Response code 503"))
        assertTrue(FailureClassifier.isSiteLevel(null, "HTTP 403 Forbidden"))
        assertTrue(FailureClassifier.isSiteLevel(null, "Site might have added Cloudflare/DDOS protection"))

        assertFalse(FailureClassifier.isSiteLevel(NullPointerException(), "NullPointerException"))
        assertFalse(FailureClassifier.isSiteLevel(IllegalStateException("No data"), "No data"))
        assertFalse(FailureClassifier.isSiteLevel(null, "Chapter 4030 missing"))
        assertFalse(FailureClassifier.isSiteLevel(null, null))
    }

    @Test
    fun notificationPlanSortsAndCaps() {
        val found = (1..8).map {
            FoundNovel(it, "Novel $it", "a", "u$it", added = 1, newChapters = it)
        }
        val plan = UpdateNotificationPlan.from(found)
        assertEquals(8, plan.total)
        assertEquals(8, plan.novels.size)
        assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1), plan.novels.map { it.id })
        assertEquals(UpdateNotificationPlan.MAX_LINES, plan.lines.size)
        assertEquals("Novel 8  +8", plan.lines.first())
        assertEquals(3, plan.moreCount)
    }

    @Test
    fun notificationPlanDropsNothingFoundAndDuplicates() {
        val plan = UpdateNotificationPlan.from(
            listOf(
                FoundNovel(1, "B", "a", "u", added = 2, newChapters = 0),
                FoundNovel(1, "B", "a", "u", added = 2, newChapters = 0),
                FoundNovel(2, "A", "a", "u", added = 0, newChapters = 5),
            )
        )
        assertEquals(1, plan.total)
        assertEquals(listOf("B  +2"), plan.lines)
        assertEquals(0, plan.moreCount)
        assertTrue(UpdateNotificationPlan.from(emptyList()).novels.isEmpty())
    }
}
