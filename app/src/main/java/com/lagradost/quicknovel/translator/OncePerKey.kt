package com.lagradost.quicknovel.translator

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-flight gate for a job that should succeed once per key (the automatic novel analysis):
 *
 * - one job per key at a time; callers arriving while it runs wait for it;
 * - after it, waiting callers re-check [runOnce]'s `isDone` and return without running again;
 * - when it failed, callers that were already waiting do not retry it immediately (they return
 *   null); a caller that arrives after the failure tries again ("retry on the next start").
 *
 * [withLock] runs an unconditional job (the manual re-run) under the same per-key lock.
 */
class OncePerKey {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val clock = AtomicLong()
    private val lastFailure = ConcurrentHashMap<String, Long>()

    private fun lockFor(key: String) = locks.getOrPut(key) { Mutex() }

    /**
     * @param isDone true when the job is no longer needed (checked before and after waiting)
     * @param succeeded whether a result counts as success
     * @return the job's result, or null if it did not run
     */
    suspend fun <T> runOnce(
        key: String,
        isDone: suspend () -> Boolean,
        succeeded: (T) -> Boolean,
        block: suspend () -> T,
    ): T? {
        if (isDone()) return null
        val ticket = clock.incrementAndGet()
        return lockFor(key).withLock {
            if (isDone()) return@withLock null
            if ((lastFailure[key] ?: 0L) > ticket) return@withLock null // it failed while we waited
            val result = block()
            if (succeeded(result)) lastFailure.remove(key) else lastFailure[key] = clock.incrementAndGet()
            result
        }
    }

    suspend fun <T> withLock(key: String, block: suspend () -> T): T = lockFor(key).withLock { block() }

    /** True while a job for [key] runs. */
    fun isRunning(key: String): Boolean = locks[key]?.isLocked == true
}
