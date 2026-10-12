package com.lagradost.quicknovel.ui.browse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ListingCacheTest {
    private lateinit var dir: File
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("listing-cache").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun disk(max: Int = 200) = ListingDiskCache(dir, ttlMs = ListingDiskCache.TTL_MS, maxEntries = max, now = { clock })

    /** Values are lists of strings joined by ',' on disk; an empty list is "not cacheable". */
    private fun cache(disk: ListingDiskCache?) = ListingCache<String, List<String>>(
        disk = disk,
        diskKey = { "k|$it" },
        encode = { it.joinToString(",") },
        decode = { raw -> if (raw.isEmpty()) emptyList() else raw.split(',') },
        cacheable = { it.isNotEmpty() },
        io = Dispatchers.Unconfined,
    )

    private class Loader(var value: Result<List<String>>) {
        var calls = 0
        suspend fun load(): Result<List<String>> {
            calls++
            return value
        }
    }

    // ---- ListingDiskCache ----

    @Test
    fun diskEntryIsServedUntilTheTtlEnds() {
        val d = disk()
        d.put("a", "payload")
        clock += ListingDiskCache.TTL_MS
        assertEquals("payload", d.get("a"))
        clock += 1
        assertNull("6 h + 1 ms is expired", d.get("a"))
        assertEquals("expired file deleted", 0, d.size())
    }

    @Test
    fun diskKeepsOnlyTheMostRecentlyUsedEntries() {
        val d = disk(max = 3)
        for (k in listOf("a", "b", "c")) {
            d.put(k, k)
            clock += 1_000
        }
        // Reading "a" makes it recent, so "b" is the least recently used when "d" arrives.
        assertEquals("a", d.get("a"))
        clock += 1_000
        d.put("d", "d")
        assertEquals(3, d.size())
        assertNull(d.get("b"))
        assertEquals("a", d.get("a"))
        assertEquals("c", d.get("c"))
        assertEquals("d", d.get("d"))
    }

    @Test
    fun corruptOrForeignFilesReadAsMisses() {
        val d = disk()
        dir.mkdirs()
        File(dir, ListingDiskCache.fileName("x")).writeText("garbage")
        assertNull(d.get("x"))
        File(dir, ListingDiskCache.fileName("y")).writeText("${clock}\tother key\nvalue")
        assertNull("key in the header must match", d.get("y"))
        assertNull(d.get("never written"))
    }

    @Test
    fun diskSurvivesANewInstance() {
        disk().put("a", "1,2")
        assertEquals("1,2", disk().get("a"))
    }

    // ---- ListingCache ----

    @Test
    fun loadIsWrittenThroughAndReadFromDiskAfterARestart() = runBlocking {
        val loader = Loader(Result.success(listOf("x", "y")))
        assertEquals(listOf("x", "y"), cache(disk()).get("p1", refresh = false) { loader.load() }.getOrThrow())
        assertEquals(1, loader.calls)

        // "Process restart": a new memory layer over the same directory.
        val restarted = cache(disk())
        assertNull(restarted.inMemory("p1"))
        assertEquals(listOf("x", "y"), restarted.get("p1", refresh = false) { loader.load() }.getOrThrow())
        assertEquals("served from disk, no load", 1, loader.calls)
        assertEquals(listOf("x", "y"), restarted.inMemory("p1"))
    }

    @Test
    fun expiredDiskEntryLoadsAgain() = runBlocking {
        val loader = Loader(Result.success(listOf("old")))
        cache(disk()).get("p", refresh = false) { loader.load() }
        clock += ListingDiskCache.TTL_MS + 1
        loader.value = Result.success(listOf("new"))
        assertEquals(listOf("new"), cache(disk()).get("p", refresh = false) { loader.load() }.getOrThrow())
        assertEquals(2, loader.calls)
    }

    @Test
    fun refreshBypassesMemoryAndDiskAndRewritesBoth() = runBlocking {
        val c = cache(disk())
        val loader = Loader(Result.success(listOf("v1")))
        c.get("p", refresh = false) { loader.load() }
        loader.value = Result.success(listOf("v2"))
        assertEquals(listOf("v1"), c.get("p", refresh = false) { loader.load() }.getOrThrow())
        assertEquals(1, loader.calls)
        assertEquals(listOf("v2"), c.get("p", refresh = true) { loader.load() }.getOrThrow())
        assertEquals(2, loader.calls)
        assertEquals(listOf("v2"), c.inMemory("p"))
        assertEquals("v2", disk().get("k|p"))
    }

    @Test
    fun failuresAndEmptyPagesAreNeverCached() = runBlocking {
        val c = cache(disk())
        val loader = Loader(Result.failure(RuntimeException("down")))
        assertTrue(c.get("p", refresh = false) { loader.load() }.isFailure)
        loader.value = Result.success(emptyList())
        assertEquals(emptyList<String>(), c.get("p", refresh = false) { loader.load() }.getOrThrow())
        assertEquals(2, loader.calls)
        assertNull(c.inMemory("p"))
        assertEquals(0, disk().size())

        loader.value = Result.success(listOf("ok"))
        c.get("p", refresh = false) { loader.load() }
        assertEquals(3, loader.calls)
        c.get("p", refresh = false) { loader.load() }
        assertEquals("cached now", 3, loader.calls)
    }

    @Test
    fun emptyPageOnDiskIsIgnored() = runBlocking {
        disk().put("k|p", "")
        val loader = Loader(Result.success(listOf("fresh")))
        assertEquals(listOf("fresh"), cache(disk()).get("p", refresh = false) { loader.load() }.getOrThrow())
        assertEquals(1, loader.calls)
    }

    @Test
    fun worksWithoutADiskLayer() = runBlocking {
        val c = cache(null)
        val loader = Loader(Result.success(listOf("a")))
        c.get("p", refresh = false) { loader.load() }
        c.get("p", refresh = false) { loader.load() }
        assertEquals(1, loader.calls)
    }

    @Test
    fun forgetDropsAPageFromBothLayers() = runBlocking {
        // A merged multi-genre page with a failed genre is forgotten so it is asked for again.
        val d = disk()
        val c = cache(d)
        val loader = Loader(Result.success(listOf("x")))
        c.get("p", refresh = false) { loader.load() }
        c.forget("p")
        assertNull(c.inMemory("p"))
        assertNull(d.get("k|p"))
        c.get("p", refresh = false) { loader.load() }
        assertEquals(2, loader.calls)
    }
}
