package com.lagradost.quicknovel.updates

import com.lagradost.quicknovel.ui.ReadType
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** How often the periodic check runs; the key is what the settings list stores. */
enum class UpdateFrequency(val key: String, val hours: Long) {
    Off("0", 0),
    Every6Hours("6", 6),
    Every12Hours("12", 12),
    Daily("24", 24);

    companion object {
        val DEFAULT = Daily
        fun fromKey(key: String?): UpdateFrequency = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The parts of the library the checker may look at; the key is what the settings set stores. */
enum class LibraryCategory(val key: String, val readType: ReadType?) {
    Reading("reading", ReadType.READING),
    PlanToRead("plan_to_read", ReadType.PLAN_TO_READ),
    OnHold("on_hold", ReadType.ON_HOLD),
    Completed("completed", ReadType.COMPLETED),
    Dropped("dropped", ReadType.DROPPED),
    Downloads("downloads", null);

    companion object {
        val DEFAULT: Set<LibraryCategory> = setOf(Reading, PlanToRead, Downloads)
        val DEFAULT_KEYS: Set<String> = DEFAULT.map { it.key }.toSet()

        fun fromKeys(keys: Set<String>?): Set<LibraryCategory> =
            keys?.mapNotNull { key -> entries.firstOrNull { it.key == key } }?.toSet() ?: DEFAULT

        fun fromBookmarkState(prefValue: Int?): LibraryCategory? =
            entries.firstOrNull { it.readType != null && it.readType.prefValue == prefValue }
    }
}

/** One novel the checker will load. */
data class CheckCandidate(
    val id: Int,
    val apiName: String,
    val url: String,
    val name: String,
    /** Chapter count the library already knows, see [ChapterUpdateDetector.onSuccess]. */
    val baselineHint: Int?,
    /** From the stored state; null when never checked. */
    val lastCheckedAt: Long?,
)

object CheckPlanner {
    /**
     * Merges candidates that appear twice (bookmarked and downloaded) and orders them so that
     * the least recently checked novels go first (a run cut short by the time cap continues with
     * the rest next time) and consecutive requests rotate between sites instead of hitting the
     * same site back to back.
     */
    fun order(candidates: List<CheckCandidate>): List<CheckCandidate> {
        val unique = candidates
            .groupBy { it.id }
            .map { (_, same) -> same.firstOrNull { it.baselineHint != null } ?: same.first() }
            .sortedWith(compareBy<CheckCandidate> { it.lastCheckedAt ?: Long.MIN_VALUE }
                .thenBy { it.name })

        // groupBy keeps the first-seen order, so the site with the oldest novel goes first
        val queues = unique.groupBy { it.apiName }.values.map { ArrayDeque(it) }
        val out = ArrayList<CheckCandidate>(unique.size)
        while (out.size < unique.size) {
            for (queue in queues) {
                queue.removeFirstOrNull()?.let(out::add)
            }
        }
        return out
    }
}

/** Keeps at least [minGapMs] between two requests to the same site. */
class SiteThrottle(private val minGapMs: Long) {
    private val lastRequestAt = HashMap<String, Long>()

    /** How long to wait before the next request to [site] may start. */
    fun waitMs(site: String, now: Long): Long {
        val last = lastRequestAt[site] ?: return 0
        return maxOf(0, last + minGapMs - now)
    }

    fun record(site: String, now: Long) {
        lastRequestAt[site] = now
    }
}

/**
 * Tracks sites that look blocked or down during one run. After [maxConsecutiveFailures]
 * site-level failures in a row the remaining novels of that site are skipped.
 */
class SiteHealth(private val maxConsecutiveFailures: Int = 2) {
    private val failures = HashMap<String, Int>()
    private val lastError = HashMap<String, String>()

    fun isSkipped(site: String): Boolean = (failures[site] ?: 0) >= maxConsecutiveFailures

    fun skipReason(site: String): String =
        "Skipped: $site looks blocked or down (${lastError[site] ?: "unknown error"})"

    fun recordSuccess(site: String) {
        failures.remove(site)
        lastError.remove(site)
    }

    /** Only site-level failures count; a parse error on one novel says nothing about the site. */
    fun recordFailure(site: String, siteLevel: Boolean, message: String) {
        if (!siteLevel) return
        failures[site] = (failures[site] ?: 0) + 1
        lastError[site] = message.lineSequence().firstOrNull()?.trim().orEmpty()
    }
}

object FailureClassifier {
    private val httpStatus = Regex("""\b(403|429|5\d\d)\b""")

    /** True when the failure says the site is unreachable or blocking us, not that one novel failed. */
    fun isSiteLevel(cause: Throwable?, message: String?): Boolean {
        var t = cause
        var depth = 0
        while (t != null && depth < 5) {
            when (t) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException,
                is SocketTimeoutException, is SSLException -> return true
            }
            t = t.cause
            depth++
        }
        val text = message.orEmpty()
        if (text.contains("cloudflare", true) || text.contains("ddos", true)) return true
        if (httpStatus.containsMatchIn(text)) return true
        // A bare IOException without a more specific type is usually a dropped connection
        return cause != null && cause.javaClass == IOException::class.java
    }
}

/** A novel in which a check found new chapters. */
data class FoundNovel(
    val id: Int,
    val name: String,
    val apiName: String,
    val url: String,
    /** Found by this check. */
    val added: Int,
    /** All unseen new chapters, including earlier checks; this is what the "+N" shows. */
    val newChapters: Int,
)

/** What the new-chapter notifications show. */
data class UpdateNotificationPlan(
    /** Novels that get their own (grouped) notification, most new chapters first. */
    val novels: List<FoundNovel>,
    /** Per-novel lines of the summary, "name +N". */
    val lines: List<String>,
    /** Novels not listed in [lines]. */
    val moreCount: Int,
    /** All novels with new chapters in this run. */
    val total: Int,
) {
    companion object {
        const val MAX_LINES = 5
        const val MAX_NOVEL_NOTIFICATIONS = 20

        fun from(found: List<FoundNovel>): UpdateNotificationPlan {
            val sorted = found
                .filter { it.added > 0 }
                .distinctBy { it.id }
                .sortedWith(compareByDescending<FoundNovel> { count(it) }.thenBy { it.name })
            return UpdateNotificationPlan(
                novels = sorted.take(MAX_NOVEL_NOTIFICATIONS),
                lines = sorted.take(MAX_LINES).map { line(it) },
                moreCount = maxOf(0, sorted.size - MAX_LINES),
                total = sorted.size,
            )
        }

        /** The "+N" of a novel: every unseen new chapter, never less than this run found. */
        fun count(novel: FoundNovel): Int = maxOf(novel.newChapters, novel.added)

        fun line(novel: FoundNovel): String = "${novel.name}  +${count(novel)}"
    }
}
