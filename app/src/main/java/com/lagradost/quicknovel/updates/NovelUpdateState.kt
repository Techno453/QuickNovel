package com.lagradost.quicknovel.updates

/**
 * New-chapter tracking for one library novel, persisted as JSON in the app's DataStore
 * (`rebuild_preference`) under `library_update_state/{id}`, next to the bookmark
 * (`result_bookmarked/{id}`) and download (`downloads_data/{id}`) metadata it belongs to.
 * The id is the same `generateId(apiName, author, name)` id the library uses.
 *
 * Every field has a default so older or partial JSON still deserializes.
 */
data class NovelUpdateState(
    val id: Int = 0,
    val apiName: String = "",
    val url: String = "",
    val name: String = "",
    /** Chapter count seen by the last successful check, null before the first one. */
    val lastKnownChapterCount: Int? = null,
    /** Url of the last chapter seen by the last successful check. */
    val lastChapterUrl: String? = null,
    /** Name of the last chapter seen by the last successful check. */
    val lastChapterName: String? = null,
    /** Unix ms of the last successful check. */
    val lastCheckedAt: Long? = null,
    /** New chapters found since the user last opened the novel; shown as the "+N new" badge. */
    val newChapters: Int = 0,
    /** Unix ms of the last check that found new chapters; drives the "Recently updated" sort. */
    val lastNewChaptersAt: Long? = null,
    /** Why the last check failed or was skipped, null after a successful check. */
    val lastError: String? = null,
    /** Unix ms of [lastError]. */
    val lastErrorAt: Long? = null,
)

/** One chapter of a freshly loaded chapter list, reduced to what the comparison needs. */
data class ChapterRef(val name: String, val url: String)
