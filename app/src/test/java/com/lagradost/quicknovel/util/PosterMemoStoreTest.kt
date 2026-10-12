package com.lagradost.quicknovel.util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterMemoStoreTest {
    @Test
    fun keyIgnoresSchemeSlashAndFragment() {
        val k = PosterMemoStore.key("https://www.piaotia.com/bookinfo/1/1234.html")
        assertEquals(k, PosterMemoStore.key("http://www.piaotia.com/bookinfo/1/1234.html"))
        assertEquals(k, PosterMemoStore.key(" https://www.piaotia.com/bookinfo/1/1234.html#top"))
        assertEquals(PosterMemoStore.key("https://quanben.io/n/abc"), PosterMemoStore.key("https://quanben.io/n/abc/"))
    }

    @Test
    fun putGetAndChangeDetection() {
        val s = PosterMemoStore()
        assertTrue(s.put("https://a/book/1/", "https://a/c/1.jpg", null))
        assertFalse(s.put("https://a/book/1", "https://a/c/1.jpg", emptyMap()))
        assertEquals("https://a/c/1.jpg", s.get("http://a/book/1")?.posterUrl)
        assertFalse(s.put("https://a/book/2", null, null))
        assertFalse(s.put(null, "https://a/c/2.jpg", null))
        assertNull(s.get("https://a/book/2"))
        assertTrue(s.put("https://a/book/1", "https://a/c/1b.jpg", mapOf("Referer" to "https://a/")))
        assertEquals(mapOf("Referer" to "https://a/"), s.get("https://a/book/1")?.headers)
    }

    @Test
    fun evictsLeastRecentlyUsed() {
        val s = PosterMemoStore(maxEntries = 2)
        s.put("https://a/1", "https://a/1.jpg", null)
        s.put("https://a/2", "https://a/2.jpg", null)
        s.get("https://a/1")
        s.put("https://a/3", "https://a/3.jpg", null)
        assertEquals(2, s.size())
        assertNull(s.get("https://a/2"))
        assertEquals("https://a/1.jpg", s.get("https://a/1")?.posterUrl)
    }

    @Test
    fun roundTripsThroughJsonAndKeepsNewerEntries() {
        val s = PosterMemoStore()
        s.put("https://a/1", "https://a/1.jpg", mapOf("useCloudflareKiller" to "true"))
        s.put("https://a/2", "https://a/2.jpg", null)
        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(s.toRows())

        val fresh = PosterMemoStore()
        fresh.put("https://a/2", "https://a/2-new.jpg", null)
        fresh.load(mapper.readValue<List<Map<String, Any?>>>(json))
        assertEquals(mapOf("useCloudflareKiller" to "true"), fresh.get("https://a/1")?.headers)
        assertEquals("https://a/2-new.jpg", fresh.get("https://a/2")?.posterUrl)
    }
}
