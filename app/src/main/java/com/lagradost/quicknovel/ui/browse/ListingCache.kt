package com.lagradost.quicknovel.ui.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * On-disk store for Browse listing pages (docs/browse.md, "Loading, caching and politeness").
 * Pure JVM: one file per key under [dir], named by the SHA-1 of the key. The first line holds
 * the save time and the key, the rest is the JSON value written by the caller.
 *
 * - Entries older than [ttlMs] are never returned (and are deleted when read or pruned).
 * - At most [maxEntries] files are kept; on every write the least recently used ones beyond
 *   the bound are deleted. A read touches the file's modification time, so "recently used"
 *   survives a process restart.
 * - Any I/O or format problem reads as a miss; the cache never throws.
 */
class ListingDiskCache(
    private val dir: File,
    private val ttlMs: Long = TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val MAX_ENTRIES = 200
        private const val SUFFIX = ".json"

        fun fileName(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) } + SUFFIX
        }
    }

    private val lock = Any()

    /** The value saved for [key] if it is younger than the TTL, else null. */
    fun get(key: String): String? = synchronized(lock) {
        val file = File(dir, fileName(key))
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val newline = text.indexOf('\n')
        if (newline < 0) return deleteAndMiss(file)
        val header = text.substring(0, newline)
        val tab = header.indexOf('\t')
        val savedAt = (if (tab < 0) null else header.substring(0, tab).toLongOrNull()) ?: return deleteAndMiss(file)
        if (header.substring(tab + 1) != key) return null
        val age = now() - savedAt
        if (age < 0 || age > ttlMs) return deleteAndMiss(file)
        runCatching { file.setLastModified(now()) }
        text.substring(newline + 1)
    }

    /** Saves [value] for [key] and drops the least recently used entries beyond the bound. */
    fun put(key: String, value: String) = synchronized(lock) {
        runCatching {
            dir.mkdirs()
            val file = File(dir, fileName(key))
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText("${now()}\t$key\n$value", Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            file.setLastModified(now())
        }
        prune()
    }

    fun remove(key: String) = synchronized(lock) {
        runCatching { File(dir, fileName(key)).delete() }
        Unit
    }

    /** Number of entries on disk (tests, diagnostics). */
    fun size(): Int = synchronized(lock) { entries().size }

    private fun entries(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList().orEmpty()

    private fun prune() {
        val files = entries()
        if (files.size <= maxEntries) return
        files.sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { runCatching { it.delete() } }
    }

    private fun deleteAndMiss(file: File): String? {
        runCatching { file.delete() }
        return null
    }
}

/**
 * Memory cache in front of an optional [ListingDiskCache], with shared in-flight loads.
 *
 * - A memory hit returns at once. A memory miss reads the disk once (TTL applies) and keeps the
 *   page in memory for the rest of the session.
 * - `refresh = true` (pull to refresh, Retry) skips both layers and loads.
 * - Every successful load that [cacheable] accepts is written to memory and through to disk;
 *   failures and pages it rejects (empty pages) are never cached.
 * - Concurrent requests for the same key share one load.
 */
class ListingCache<K : Any, V : Any>(
    private val disk: ListingDiskCache?,
    private val diskKey: (K) -> String,
    private val encode: (V) -> String,
    private val decode: (String) -> V?,
    private val cacheable: (V) -> Boolean,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val memory = ConcurrentHashMap<K, V>()
    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<Result<V>>>()

    /** The page in memory, without touching the disk. */
    fun inMemory(key: K): V? = memory[key]

    /** Drops [key] from both layers (a page that should be asked for again next time). */
    suspend fun forget(key: K) {
        memory.remove(key)
        val store = disk ?: return
        withContext(io) { store.remove(diskKey(key)) }
    }

    suspend fun get(key: K, refresh: Boolean, load: suspend () -> Result<V>): Result<V> {
        if (!refresh) {
            memory[key]?.let { return Result.success(it) }
            fromDisk(key)?.let { value ->
                memory.putIfAbsent(key, value)
                return Result.success(memory[key] ?: value)
            }
        }
        val mine = CompletableDeferred<Result<V>>()
        val shared = inFlight.putIfAbsent(key, mine)
        if (shared != null) return shared.await()
        val result = try {
            load()
        } catch (e: CancellationException) {
            inFlight.remove(key, mine)
            mine.cancel()
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        result.onSuccess { value ->
            if (cacheable(value)) {
                memory[key] = value
                toDisk(key, value)
            }
        }
        inFlight.remove(key, mine)
        mine.complete(result)
        return result
    }

    private suspend fun fromDisk(key: K): V? {
        val store = disk ?: return null
        return withContext(io) {
            val raw = store.get(diskKey(key)) ?: return@withContext null
            val value = runCatching { decode(raw) }.getOrNull()
            if (value == null || !cacheable(value)) {
                store.remove(diskKey(key))
                null
            } else value
        }
    }

    private suspend fun toDisk(key: K, value: V) {
        val store = disk ?: return
        withContext(io) {
            val raw = runCatching { encode(value) }.getOrNull() ?: return@withContext
            store.put(diskKey(key), raw)
        }
    }
}
