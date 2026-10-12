package com.lagradost.quicknovel.translator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OncePerKeyTest {

    @Test
    fun concurrentCallersRunTheJobOnce() = runBlocking {
        val gate = OncePerKey()
        var done = false
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val callers = (1..5).map {
            async {
                gate.runOnce("novel", isDone = { done }, succeeded = { it }) {
                    runs.incrementAndGet()
                    release.await()
                    done = true
                    true
                }
            }
        }
        repeat(10) { yield() }
        assertTrue(gate.isRunning("novel"))
        release.complete(Unit)
        val results = callers.awaitAll()
        assertEquals(1, runs.get())
        assertEquals(1, results.count { it == true })
        assertEquals(4, results.count { it == null })
        assertFalse(gate.isRunning("novel"))
    }

    @Test
    fun waitersDoNotRetryAFailureButLaterCallersDo() = runBlocking {
        val gate = OncePerKey()
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.runOnce("novel", isDone = { false }, succeeded = { it }) {
                runs.incrementAndGet()
                release.await()
                false // e.g. network error
            }
        }
        repeat(5) { yield() }
        val waiter = async {
            gate.runOnce("novel", isDone = { false }, succeeded = { it }) {
                runs.incrementAndGet()
                false
            }
        }
        repeat(5) { yield() }
        release.complete(Unit)
        assertEquals(false, first.await())
        assertNull("a caller that waited for the failed run must not retry it at once", waiter.await())
        assertEquals(1, runs.get())

        // The next translation start retries.
        val retry = gate.runOnce("novel", isDone = { false }, succeeded = { it }) {
            runs.incrementAndGet()
            true
        }
        assertEquals(true, retry)
        assertEquals(2, runs.get())
    }

    @Test
    fun doneSkipsAndKeysAreIndependent() = runBlocking {
        val gate = OncePerKey()
        assertNull(gate.runOnce("a", isDone = { true }, succeeded = { true }) { error("must not run") })
        assertEquals(1, gate.runOnce("b", isDone = { false }, succeeded = { true }) { 1 })
        assertEquals("manual", gate.withLock("a") { "manual" })
    }
}
