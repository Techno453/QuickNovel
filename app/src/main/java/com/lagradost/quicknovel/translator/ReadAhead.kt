package com.lagradost.quicknovel.translator

/**
 * Which chapters the reader's "auto-translate ahead" mode should have queued: the next [ahead]
 * chapters after the one being read, in reading order, minus those already translated or being
 * translated right now. Never anything at or behind the current chapter.
 */
object ReadAheadPlanner {
    /**
     * @param current index of the chapter being read
     * @param ahead N from the setting (0 = off)
     * @param chapterCount size of the chapter list
     * @param translated chapters with a stored translation
     * @param inFlight chapters being translated right now (by read-ahead or on demand)
     */
    fun plan(
        current: Int,
        ahead: Int,
        chapterCount: Int,
        translated: Set<Int>,
        inFlight: Set<Int> = emptySet(),
    ): List<Int> {
        if (ahead <= 0 || chapterCount <= 0 || current >= chapterCount - 1) return emptyList()
        val first = maxOf(current + 1, 0)
        val last = minOf(current.toLong() + ahead, chapterCount - 1L).toInt()
        if (first > last) return emptyList()
        return (first..last).filter { it !in translated && it !in inFlight }
    }
}

/**
 * The read-ahead queue: one novel at a time (the one open in the reader), chapters in reading
 * order. Each [update] (the reader moved to a chapter) replaces the pending list with a fresh
 * [ReadAheadPlanner.plan], so chapters that fell behind or out of the window are dropped and
 * the queue is topped up. Thread-safe.
 *
 * Worker claim: [update] returns true exactly when the caller must start a worker (there is work
 * and no worker owns the queue). The worker takes items with [poll] and reports them with
 * [done]; [poll] returning null releases the claim, so the next [update] starts a new worker.
 */
class ReadAheadQueue {
    data class Item(val novelId: String, val chapter: Int)

    private var novelId: String? = null
    private val pending = ArrayDeque<Int>()
    private val inFlight = LinkedHashSet<Item>()
    private var workerClaimed = false

    /** Novel the queue is for (null before the first update or after [clear]). */
    val currentNovelId: String? @Synchronized get() = novelId

    /** Pending chapters, in order. */
    val pendingChapters: List<Int> @Synchronized get() = pending.toList()

    val hasPending: Boolean @Synchronized get() = pending.isNotEmpty()

    val isWorkerClaimed: Boolean @Synchronized get() = workerClaimed

    /** @return true if the caller must start a worker for the queue */
    @Synchronized
    fun update(novelId: String, current: Int, ahead: Int, chapterCount: Int, translated: Set<Int>): Boolean {
        val busy = inFlight.filter { it.novelId == novelId }.map { it.chapter }.toSet()
        this.novelId = novelId
        pending.clear()
        pending.addAll(ReadAheadPlanner.plan(current, ahead, chapterCount, translated, busy))
        if (pending.isNotEmpty() && !workerClaimed) {
            workerClaimed = true
            return true
        }
        return false
    }

    /** Next chapter to translate (marked in flight), or null, which also releases the worker claim. */
    @Synchronized
    fun poll(): Item? {
        val id = novelId
        val chapter = pending.removeFirstOrNull()
        if (id == null || chapter == null) {
            workerClaimed = false
            return null
        }
        return Item(id, chapter).also { inFlight += it }
    }

    @Synchronized
    fun done(item: Item) {
        inFlight -= item
    }

    /** Drops all pending chapters (the in-flight one finishes or is cancelled by the worker). */
    @Synchronized
    fun clear() {
        pending.clear()
        novelId = null
    }

    /** The worker stopped without draining the queue (service destroyed, cancelled). */
    @Synchronized
    fun releaseWorker() {
        workerClaimed = false
        inFlight.clear()
    }
}
