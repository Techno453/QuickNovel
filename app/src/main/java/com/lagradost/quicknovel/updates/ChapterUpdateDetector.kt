package com.lagradost.quicknovel.updates

/** What a check found, relative to the stored state. */
enum class ChangeKind {
    /** First successful check, nothing to compare against: the list becomes the baseline. */
    Baseline,

    /** Same list (or at least the same last chapter at the same count). */
    Unchanged,

    /** Chapters were added after the last known chapter. */
    NewChapters,

    /** The list got shorter and the last known chapter is gone (site cleanup, re-upload). */
    ChaptersRemoved,

    /** The list changed without new chapters at the end (renamed or reinserted chapters). */
    ListChanged,

    /** The site returned no chapters; treated as an error so the stored count is kept. */
    EmptyList,
}

data class CheckOutcome(
    val state: NovelUpdateState,
    /** New chapters found by this check (0 unless [kind] is [ChangeKind.NewChapters]). */
    val added: Int,
    val kind: ChangeKind,
)

/**
 * Pure decision logic for the library update checker: given the stored state of a novel and a
 * freshly loaded chapter list, what changed and what is stored next.
 *
 * New chapters are counted from the position of the last known chapter url when it is still in
 * the list (robust against sites that only list the latest N chapters, or that insert missing
 * older chapters), and from the chapter count otherwise.
 */
object ChapterUpdateDetector {
    const val EMPTY_LIST_ERROR = "The site returned an empty chapter list"

    /**
     * @param baselineHint the chapter count the library already knew before the first check
     * (the bookmark's cached total or the download's total). Only used when nothing is stored
     * yet, so chapters released since the novel was added are reported on the first check.
     */
    fun onSuccess(
        stored: NovelUpdateState,
        fresh: List<ChapterRef>,
        now: Long,
        baselineHint: Int? = null,
    ): CheckOutcome {
        if (fresh.isEmpty()) {
            return CheckOutcome(
                stored.copy(lastError = EMPTY_LIST_ERROR, lastErrorAt = now),
                0,
                ChangeKind.EmptyList
            )
        }

        val known = stored.lastKnownChapterCount
        val lastIndex = stored.lastChapterUrl?.let { lastUrl ->
            fresh.indexOfLast { sameUrl(it.url, lastUrl) }
        } ?: -1

        val added: Int
        val kind: ChangeKind
        if (known == null) {
            added = if (baselineHint != null && baselineHint > 0 && fresh.size > baselineHint) {
                fresh.size - baselineHint
            } else {
                0
            }
            kind = if (added > 0) ChangeKind.NewChapters else ChangeKind.Baseline
        } else {
            added = if (lastIndex >= 0) {
                fresh.size - 1 - lastIndex
            } else {
                maxOf(0, fresh.size - known)
            }
            kind = when {
                added > 0 -> ChangeKind.NewChapters
                fresh.size < known && lastIndex < 0 -> ChangeKind.ChaptersRemoved
                fresh.size == known && (lastIndex >= 0 || stored.lastChapterUrl == null) ->
                    ChangeKind.Unchanged

                else -> ChangeKind.ListChanged
            }
        }

        val last = fresh.last()
        return CheckOutcome(
            stored.copy(
                lastKnownChapterCount = fresh.size,
                lastChapterUrl = last.url,
                lastChapterName = last.name,
                lastCheckedAt = now,
                newChapters = stored.newChapters + added,
                lastNewChaptersAt = if (added > 0) now else stored.lastNewChaptersAt,
                lastError = null,
                lastErrorAt = null,
            ),
            added,
            kind
        )
    }

    /** A failed or skipped check keeps everything it knew and only notes the error. */
    fun onFailure(stored: NovelUpdateState, error: String, now: Long): NovelUpdateState =
        stored.copy(lastError = error.trim().ifEmpty { "Unknown error" }, lastErrorAt = now)

    /** The user opened the novel: the new chapters are no longer new. */
    fun markSeen(stored: NovelUpdateState): NovelUpdateState =
        if (stored.newChapters == 0) stored else stored.copy(newChapters = 0)

    /** Url equality that ignores scheme, a leading "www.", trailing slashes and case. */
    fun sameUrl(a: String, b: String): Boolean = normalizeUrl(a).equals(normalizeUrl(b), true)

    private fun normalizeUrl(url: String): String = url.trim()
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')
}
