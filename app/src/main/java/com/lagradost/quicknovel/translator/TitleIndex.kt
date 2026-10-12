package com.lagradost.quicknovel.translator

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Outcome of one uncached title-index lookup; [failure] says why the index could not answer. */
data class IndexLookup(val names: List<String>, val failure: String? = null)

/**
 * The key-free title index behind translated search (docs/search.md): the original CJK titles of
 * the novel an English query names, from NovelUpdates ("Associated Names", [NovelUpdatesIndex])
 * and the Novel Buddy search API (alternative names, [NovelBuddyIndex]), asked in parallel.
 * NovelUpdates names come first. One index failing (NovelUpdates blocks some networks) leaves the
 * other's answer. Never throws, except for cancellation.
 */
object TitleIndex {
    suspend fun associatedNames(
        query: String,
        novelUpdates: suspend (String) -> List<String> = { NovelUpdatesIndex.associatedNames(it) },
        novelBuddy: suspend (String) -> List<String> = { NovelBuddyIndex.associatedNames(it) },
    ): List<String> = coroutineScope {
        val nu = async { novelUpdates(query) }
        val nb = async { novelBuddy(query) }
        SearchQueryTranslator.distinctQueries(nu.await() + nb.await())
    }
}

/**
 * Per-query memo shared by the title indexes: answers are cached (LRU, [maxEntries]); a failure is
 * remembered for [failureTtlMs] so that the zh, ja and ko lookups of one search do not repeat it,
 * but not for the whole session (a block may clear); concurrent lookups of one query share a
 * single request.
 */
internal class LookupCache(
    private val tag: String,
    private val maxEntries: Int,
    private val failureTtlMs: Long = 2 * 60_000L,
) {
    private val cache: MutableMap<String, List<String>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean =
                size > maxEntries
        }
    )
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failures = ConcurrentHashMap<String, Long>()

    suspend fun get(query: String, compute: suspend () -> IndexLookup): List<String> {
        val key = query.trim().lowercase()
        cache[key]?.let { return it }
        val mutex = locks.getOrPut(key) { Mutex() }
        return try {
            mutex.withLock {
                cache[key]?.let { return@withLock it }
                val failedAt = failures[key]
                if (failedAt != null && System.currentTimeMillis() - failedAt < failureTtlMs) {
                    return@withLock emptyList()
                }
                val result = compute()
                if (result.failure == null) {
                    cache[key] = result.names
                    failures.remove(key)
                } else {
                    failures[key] = System.currentTimeMillis()
                    // runCatching: android.util.Log is not available in JVM tests.
                    runCatching { android.util.Log.i(tag, "Title index unavailable: ${result.failure}") }
                }
                result.names
            }
        } finally {
            if (!mutex.isLocked) locks.remove(key, mutex)
        }
    }

    fun clear() {
        cache.clear()
        failures.clear()
    }
}
