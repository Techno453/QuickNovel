package com.lagradost.quicknovel.util

import com.lagradost.quicknovel.util.InAppUpdater.Companion.GithubAsset
import com.lagradost.quicknovel.util.InAppUpdater.Companion.GithubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version checks of the in-app updater against the asset names the release workflows publish. */
class InAppUpdaterTest {
    private fun release(vararg assetNames: String) = GithubRelease(
        tag_name = "v1",
        body = "notes",
        assets = assetNames.map {
            GithubAsset(it, 50_000_000, "https://github.com/Techno453/QuickNovel/releases/download/v1/$it", "application/vnd.android.package-archive")
        },
        target_commitish = "master",
    )

    @Test
    fun stableUsesUpstreamComparison() {
        val r = release("QuickNovel-3.8.1.apk")
        assertTrue(InAppUpdater.stableUpdate(r, "3.8.0").shouldUpdate)
        assertFalse(InAppUpdater.stableUpdate(r, "3.8.1").shouldUpdate)
        assertFalse(InAppUpdater.stableUpdate(r, "3.9.0").shouldUpdate)
        val update = InAppUpdater.stableUpdate(r, "3.8.0")
        assertEquals("3.8.1", update.updateVersion)
        assertEquals("notes", update.changelog)
        assertTrue(update.updateURL!!.endsWith("/QuickNovel-3.8.1.apk"))
    }

    @Test
    fun stableIgnoresAssetsWithoutVersion() {
        val update = InAppUpdater.stableUpdate(release("app-release.apk"), "3.8.0")
        assertFalse(update.shouldUpdate)
        assertNull(update.updateURL)
    }

    @Test
    fun preReleaseComparesCiBuildNumbers() {
        val r = release("QuickNovel-3.8.0-pre.42.apk")
        assertTrue(InAppUpdater.preReleaseUpdate(r, 41).shouldUpdate)
        assertTrue(InAppUpdater.preReleaseUpdate(r, 0).shouldUpdate)
        assertFalse(InAppUpdater.preReleaseUpdate(r, 42).shouldUpdate)
        assertFalse(InAppUpdater.preReleaseUpdate(r, 50).shouldUpdate)
        assertEquals("3.8.0-pre.42", InAppUpdater.preReleaseUpdate(r, 41).updateVersion)
    }

    @Test
    fun preReleaseWithoutBuildNumberIsNoUpdate() {
        assertFalse(InAppUpdater.preReleaseUpdate(release("QuickNovel-3.8.0.apk"), 0).shouldUpdate)
        assertFalse(InAppUpdater.preReleaseUpdate(release(), 0).shouldUpdate)
    }
}
