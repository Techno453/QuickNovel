package com.lagradost.quicknovel.ui.common

import com.lagradost.quicknovel.util.PosterMemoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterModelTest {
    private val cover = "https://img22.ixdzs.com/f1/d2/f1d206a8.jpg"

    @Test
    fun equalByValueSoRememberAndCacheKeysAreStable() {
        val a = PosterModel.resolve(cover, linkedMapOf("Referer" to "https://ixdzs8.com", "X" to "1"))
        val b = PosterModel.resolve(cover, linkedMapOf("X" to "1", "Referer" to "https://ixdzs8.com"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(cover, a.cacheKey)
    }

    @Test
    fun cacheKeyIgnoresHeaders() {
        val plain = PosterModel.resolve(cover, null)
        val withHeaders = PosterModel.resolve(cover, mapOf("useCloudflareKiller" to "true"))
        assertNotEquals(plain, withHeaders)
        assertEquals(plain.cacheKey, withHeaders.cacheKey)
    }

    @Test
    fun savedFileWinsOverUrlAndMemo() {
        val m = PosterModel.resolve(cover, null, savedFile = "/data/files/Biquge/a/b/poster.jpg",
            memo = PosterMemoStore.Entry("https://other/x.jpg"))
        assertEquals("/data/files/Biquge/a/b/poster.jpg", m.data)
        assertNull(m.url)
    }

    @Test
    fun memoFillsAMissingCover() {
        val memo = PosterMemoStore.Entry("https://i9-static.jjwxc.net/novelimage.php?novelid=1", mapOf("Referer" to "https://www.jjwxc.net/"))
        val m = PosterModel.resolve(null, null, memo = memo)
        assertEquals(memo.posterUrl, m.url)
        assertEquals(mapOf("Referer" to "https://www.jjwxc.net/"), m.headers)
        // The item's own cover wins.
        assertEquals(cover, PosterModel.resolve(cover, null, memo = memo).url)
    }

    @Test
    fun noCoverIsEmpty() {
        assertTrue(PosterModel.resolve(null, null).isEmpty)
        assertTrue(PosterModel.resolve("  ", null).isEmpty)
        assertEquals(PosterModel.Empty, PosterModel.resolve("", mapOf("a" to "b")))
        assertFalse(PosterModel.resolve(cover, null).isEmpty)
    }

    @Test
    fun errorPagesAreNotImages() {
        assertTrue(PosterModel.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertTrue(PosterModel.looksLikeImage(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertTrue(PosterModel.looksLikeImage("RIFF....WEBP".toByteArray()))
        assertFalse(PosterModel.looksLikeImage("\n  <!DOCTYPE html><title>403</title>".toByteArray()))
        assertFalse(PosterModel.looksLikeImage("{\"error\":1}".toByteArray()))
        assertFalse(PosterModel.looksLikeImage(ByteArray(0)))
    }

    @Test
    fun headerMergeIsCaseInsensitiveAndOverrideWins() {
        val merged = PosterHeaders.merge(
            mapOf("User-Agent" to "app", "Referer" to "https://a/"),
            mapOf("referer" to "https://b/", "Cookie" to "x=1", "Empty" to " "),
        )
        assertEquals(mapOf("User-Agent" to "app", "referer" to "https://b/", "Cookie" to "x=1"), merged)
    }

    @Test
    fun defaultUserAgentOnlyWhenTheSourceHasNone() {
        assertEquals(mapOf("User-Agent" to "ua"), PosterHeaders.withDefaults(null, "ua"))
        assertEquals(
            mapOf("user-agent" to "mobile", "Referer" to "r"),
            PosterHeaders.withDefaults(mapOf("user-agent" to "mobile", "Referer" to "r"), "ua"),
        )
    }
}
