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

    // --- APK selection for per-ABI releases ---

    private val splitRelease = listOf(
        "QuickNovel-3.8.1-arm64-v8a.apk",
        "QuickNovel-3.8.1-armeabi-v7a.apk",
        "QuickNovel-3.8.1-x86_64.apk",
        "QuickNovel-3.8.1-universal.apk",
        "QuickNovel-3.8.1-mapping.txt",
    )

    private fun pick(assets: List<String>, vararg abis: String): String? =
        InAppUpdater.pickApkAsset(release(*assets.toTypedArray()).assets, abis.toList())?.name

    private val arm64Phone = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val armv7Phone = arrayOf("armeabi-v7a", "armeabi")
    private val x86_64Emulator = arrayOf("x86_64", "x86", "arm64-v8a")
    private val x86Emulator = arrayOf("x86")

    @Test
    fun picksTheSplitOfThePreferredAbi() {
        assertEquals("QuickNovel-3.8.1-arm64-v8a.apk", pick(splitRelease, *arm64Phone))
        assertEquals("QuickNovel-3.8.1-armeabi-v7a.apk", pick(splitRelease, *armv7Phone))
        assertEquals("QuickNovel-3.8.1-x86_64.apk", pick(splitRelease, *x86_64Emulator))
        // Asset order on the release page does not matter.
        assertEquals("QuickNovel-3.8.1-arm64-v8a.apk", pick(splitRelease.reversed(), *arm64Phone))
    }

    @Test
    fun fallsBackToALaterDeviceAbiThenUniversal() {
        val noArm64 = splitRelease.filterNot { it.contains("arm64") }
        assertEquals("QuickNovel-3.8.1-armeabi-v7a.apk", pick(noArm64, *arm64Phone))
        // No split for 32-bit x86: the universal APK carries every ABI.
        assertEquals("QuickNovel-3.8.1-universal.apk", pick(splitRelease, *x86Emulator))
        assertEquals("QuickNovel-3.8.1-universal.apk", pick(splitRelease))
        val universalOnly = listOf("QuickNovel-3.8.1-universal.apk", "QuickNovel-3.8.1-mapping.txt")
        assertEquals("QuickNovel-3.8.1-universal.apk", pick(universalOnly, *arm64Phone))
    }

    @Test
    fun oldSingleApkReleasesStillWork() {
        assertEquals("QuickNovel-3.8.0.apk", pick(listOf("QuickNovel-3.8.0.apk"), *arm64Phone))
        assertEquals("QuickNovel-3.8.0.apk", pick(listOf("notes.txt", "QuickNovel-3.8.0.apk"), *x86Emulator))
        assertEquals("QuickNovel-3.8.0-pre.42.apk", pick(listOf("QuickNovel-3.8.0-pre.42.apk"), *arm64Phone))
    }

    @Test
    fun noApkForThisDeviceOrNoApkAtAll() {
        assertNull(pick(listOf("QuickNovel-3.8.1-x86_64.apk"), *arm64Phone))
        assertNull(pick(listOf("QuickNovel-3.8.1-mapping.txt"), *arm64Phone))
        assertNull(pick(emptyList(), *arm64Phone))
        // "armeabi" must not match the armeabi-v7a split.
        assertNull(pick(listOf("QuickNovel-3.8.1-armeabi-v7a.apk"), "armeabi"))
    }

    @Test
    fun stableUpdateOffersTheSplitOfThisDevice() {
        val r = release(*splitRelease.toTypedArray())
        val update = InAppUpdater.stableUpdate(r, "3.8.0", arm64Phone.toList())
        assertTrue(update.shouldUpdate)
        assertEquals("3.8.1", update.updateVersion)
        assertTrue(update.updateURL!!.endsWith("/QuickNovel-3.8.1-arm64-v8a.apk"))
        assertTrue(
            InAppUpdater.stableUpdate(r, "3.8.0", x86_64Emulator.toList()).updateURL!!.endsWith("/QuickNovel-3.8.1-x86_64.apk")
        )
        assertFalse(InAppUpdater.stableUpdate(r, "3.8.1", arm64Phone.toList()).shouldUpdate)
        // The mapping file listed first (as gh may order it) is not mistaken for the APK.
        val mappingFirst = release("QuickNovel-3.8.1-mapping.txt", "QuickNovel-3.8.1-universal.apk")
        assertTrue(InAppUpdater.stableUpdate(mappingFirst, "3.8.0", arm64Phone.toList()).shouldUpdate)
    }
}
