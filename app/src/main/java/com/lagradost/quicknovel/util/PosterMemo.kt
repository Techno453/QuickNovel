package com.lagradost.quicknovel.util

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Book page url -> cover url (and the headers the cover needs). Several listings (JJWXC and
 * Piaotia rankings, Quanben's plain lists, 69shuba's latest updates) give no cover, while the
 * book's own page does, which is why a cover used to appear only after the title was opened.
 * Every loaded book page and every listing item with a cover is remembered here, and a card
 * without a cover of its own falls back to it (docs/images.md).
 *
 * Pure and thread safe; [PosterMemo] keeps the app's instance on disk.
 */
class PosterMemoStore(private val maxEntries: Int = 4000) {
    data class Entry(val posterUrl: String, val headers: Map<String, String>? = null)

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    /** Remembers a cover; returns whether anything changed. */
    @Synchronized
    fun put(bookUrl: String?, posterUrl: String?, headers: Map<String, String>?): Boolean {
        if (bookUrl.isNullOrBlank() || posterUrl.isNullOrBlank()) return false
        val entry = Entry(posterUrl.trim(), headers?.takeIf { it.isNotEmpty() })
        val key = key(bookUrl)
        val old = map[key]
        if (old == entry) return false
        map[key] = entry
        return true
    }

    @Synchronized
    fun get(bookUrl: String?): Entry? = if (bookUrl.isNullOrBlank()) null else map[key(bookUrl)]

    @Synchronized
    fun size(): Int = map.size

    /** Oldest first, so [load] of the result keeps the same eviction order. */
    @Synchronized
    fun toRows(): List<Map<String, Any?>> = map.entries.map { (k, v) ->
        mapOf("book" to k, "poster" to v.posterUrl, "headers" to v.headers)
    }

    @Synchronized
    fun load(rows: List<Map<String, Any?>>) {
        for (row in rows) {
            val book = row["book"] as? String ?: continue
            val poster = row["poster"] as? String ?: continue
            @Suppress("UNCHECKED_CAST")
            val headers = (row["headers"] as? Map<String, Any?>)
                ?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap()
            // Entries added since the app started win over the file.
            if (!map.containsKey(key(book))) put(book, poster, headers)
        }
    }

    companion object {
        /** The same book is listed as http/https, with or without a trailing slash or #fragment. */
        fun key(url: String): String = url.trim()
            .substringBefore('#')
            .replaceFirst("http://", "https://")
            .trimEnd('/')
    }
}

/** The app's [PosterMemoStore], saved to `filesDir/poster_memo.json` a moment after it changes. */
object PosterMemo {
    private const val TAG = "PosterMemo"
    private const val FILE = "poster_memo.json"

    val store = PosterMemoStore()

    private val _version = MutableStateFlow(0)

    /** Bumped on every change, so cards waiting for a cover can pick it up. */
    val version: StateFlow<Int> = _version

    private val mapper by lazy { jacksonObjectMapper() }
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "poster-memo").apply { isDaemon = true }
    }
    private val savePending = AtomicBoolean(false)
    @Volatile
    private var file: File? = null

    /** Reads the saved memo in the background; call once from Application.onCreate. */
    fun init(context: Context) {
        val f = File(context.filesDir, FILE)
        file = f
        io.execute {
            try {
                if (f.exists()) {
                    store.load(mapper.readValue<List<Map<String, Any?>>>(f))
                    _version.value++
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Could not read $FILE", t)
            }
        }
    }

    fun get(bookUrl: String?): PosterMemoStore.Entry? = store.get(bookUrl)

    fun put(bookUrl: String?, posterUrl: String?, headers: Map<String, String>?) {
        if (store.put(bookUrl, posterUrl, headers)) changed()
    }

    /** Remembers each item that has a cover (a listing page or search results). */
    fun putAll(items: List<Triple<String?, String?, Map<String, String>?>>) {
        var any = false
        for ((book, poster, headers) in items) any = store.put(book, poster, headers) || any
        if (any) changed()
    }

    private fun changed() {
        _version.value++
        if (file == null || !savePending.compareAndSet(false, true)) return
        io.schedule({
            savePending.set(false)
            try {
                val f = file ?: return@schedule
                val tmp = File(f.parentFile, "$FILE.tmp")
                mapper.writeValue(tmp, store.toRows())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Could not write $FILE", t)
            }
        }, 2, TimeUnit.SECONDS)
    }
}
