package com.lagradost.quicknovel.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Which chapter names to translate next, for a scrolling chapter list (result page, reader's
 * chapter picker). Pure; the clock is passed in. Not thread-safe: use it from one thread.
 *
 * Only the visible rows plus a small lookahead are ever requested: [lookAhead] rows below the
 * last visible one and [lookBehind] above the first. A window is only planned once the list has
 * been still for [debounceMs] ([isDue]), so a fling through 1,400 chapters asks for nothing until
 * it stops. Each name is requested once ([requested]); a batch is at most [maxPerRequest] names,
 * visible rows first (top to bottom), then the rows below, then the rows above.
 */
class ChapterNameWindow(
    val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    val lookBehind: Int = DEFAULT_LOOK_BEHIND,
    val lookAhead: Int = DEFAULT_LOOK_AHEAD,
    val maxPerRequest: Int = DEFAULT_MAX_PER_REQUEST,
    private val needsTranslation: (String) -> Boolean = MetadataLabels::needsTranslation,
) {
    private var first = -1
    private var last = -1
    private var changedAt: Long? = null
    private val requested = HashSet<String>()

    /** The last reported visible rows, or null before the first report. */
    val visible: IntRange? get() = if (first < 0) null else first..last

    /** Names handed out by [next] and not [release]d. */
    val requestedCount: Int get() = requested.size

    /**
     * The list shows rows [firstVisible]..[lastVisible] at [now]. Returns false (and keeps the
     * debounce clock) when nothing changed.
     */
    fun onVisible(firstVisible: Int, lastVisible: Int, now: Long): Boolean {
        val f = firstVisible.coerceAtLeast(0)
        val l = lastVisible.coerceAtLeast(f)
        if (f == first && l == last && changedAt != null) return false
        first = f
        last = l
        changedAt = now
        return true
    }

    /** The list has been still for [debounceMs] since the last change. */
    fun isDue(now: Long): Boolean = changedAt?.let { now - it >= debounceMs } ?: false

    /** Milliseconds until [isDue] (0 when already due or nothing reported). */
    fun dueIn(now: Long): Long = changedAt?.let { (it + debounceMs - now).coerceAtLeast(0) } ?: 0

    /** Row indices of the current window in request order, clamped to [size] rows. */
    fun order(size: Int): List<Int> {
        if (first < 0 || size <= 0 || first >= size) return emptyList()
        val visibleEnd = last.coerceAtMost(size - 1)
        val out = ArrayList<Int>()
        for (i in first..visibleEnd) out += i
        for (i in visibleEnd + 1..(visibleEnd + lookAhead).coerceAtMost(size - 1)) out += i
        for (i in first - 1 downTo (first - lookBehind).coerceAtLeast(0)) out += i
        return out
    }

    /**
     * The next batch of names to translate for list [names] (the rows as listed), or empty when
     * not [isDue] or nothing is left in the window. Names for which [known] is true (already
     * translated) are skipped; the returned names count as requested.
     */
    fun next(names: List<String>, now: Long, known: (String) -> Boolean = { false }): List<String> {
        if (!isDue(now)) return emptyList()
        val batch = LinkedHashSet<String>()
        for (i in order(names.size)) {
            if (batch.size >= maxPerRequest) break
            val name = names[i].trim()
            if (name in requested || name in batch || !needsTranslation(name) || known(name)) continue
            batch += name
        }
        requested += batch
        return batch.toList()
    }

    /** Lets [names] be requested again (their request was cancelled). */
    fun release(names: Collection<String>) {
        requested -= names.toSet()
    }

    /** Forgets everything (language changed, translation switched off). */
    fun reset() {
        first = -1
        last = -1
        changedAt = null
        requested.clear()
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L
        const val DEFAULT_LOOK_BEHIND = 5
        const val DEFAULT_LOOK_AHEAD = 15
        const val DEFAULT_MAX_PER_REQUEST = 40
    }
}

/**
 * Drives a [ChapterNameWindow] for one chapter list: [visible] is called on every scroll change;
 * after the debounce, the window's names go to [translate] (batched: one call of at most
 * [ChapterNameWindow.maxPerRequest] names, which [MetadataTranslator] splits into AI calls of 20)
 * and the results to [onTranslated]. Translations already in memory ([cached]) are delivered at
 * once, without waiting for the debounce. A scroll cancels only the pending debounce, never a
 * request in flight. Use it from one thread (the main thread in the app).
 */
class ChapterNameTranslator(
    private val scope: CoroutineScope,
    private val translate: suspend (List<String>) -> Map<String, String>,
    private val onTranslated: (Map<String, String>) -> Unit,
    private val cached: (String) -> String? = { null },
    val window: ChapterNameWindow = ChapterNameWindow(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var names: List<String> = emptyList()
    private var debounce: Job? = null
    private val delivered = HashSet<String>()

    /** The list [names] shows rows [first]..[last]. */
    fun visible(names: List<String>, first: Int, last: Int) {
        val listChanged = names !== this.names
        this.names = names
        if (!window.onVisible(first, last, now()) && !listChanged) return
        deliverCached()
        debounce?.cancel()
        debounce = scope.launch {
            delay(window.dueIn(now()))
            pump()
        }
    }

    /** Stops the pending debounce and forgets what was requested. */
    fun reset() {
        debounce?.cancel()
        debounce = null
        window.reset()
        delivered.clear()
    }

    private fun deliverCached() {
        val hits = HashMap<String, String>()
        for (i in window.order(names.size)) {
            val name = names[i].trim()
            if (name in delivered) continue
            cached(name)?.let { hits[name] = it }
        }
        if (hits.isNotEmpty()) {
            delivered += hits.keys
            onTranslated(hits)
        }
    }

    private fun pump() {
        while (true) {
            val batch = window.next(names, now()) { it in delivered }
            if (batch.isEmpty()) return
            scope.launch {
                val result = try {
                    translate(batch)
                } catch (e: CancellationException) {
                    window.release(batch)
                    throw e
                } catch (e: Throwable) {
                    emptyMap()
                }
                if (result.isNotEmpty()) {
                    delivered += result.keys
                    onTranslated(result)
                }
            }
        }
    }
}
