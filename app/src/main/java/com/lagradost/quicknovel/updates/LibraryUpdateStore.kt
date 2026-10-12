package com.lagradost.quicknovel.updates

import android.content.Context
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.removeKey
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.mvvm.logError
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Persistence for [NovelUpdateState], kept in the same DataStore (`rebuild_preference`) as the
 * rest of the library metadata, so it is part of the app's manual backup and of Android backup.
 */
object LibraryUpdateStore {
    const val FOLDER = "library_update_state"

    /**
     * Fired with the novel id whenever its update state changes (new chapters found or seen).
     * Fired from the worker thread too, hence the copy-on-write observer set.
     */
    val changed = ConcurrentEvent<Int>()

    class ConcurrentEvent<T> {
        private val observers = CopyOnWriteArraySet<(T) -> Unit>()
        operator fun plusAssign(observer: (T) -> Unit) {
            observers.add(observer)
        }

        operator fun minusAssign(observer: (T) -> Unit) {
            observers.remove(observer)
        }

        operator fun invoke(value: T) {
            for (observer in observers) observer(value)
        }
    }

    private val lock = Any()
    private var installed = false

    fun get(context: Context, id: Int): NovelUpdateState? =
        context.getKey<NovelUpdateState>(FOLDER, id.toString())

    fun put(context: Context, state: NovelUpdateState) {
        synchronized(lock) {
            context.setKey(FOLDER, state.id.toString(), state)
        }
    }

    fun remove(context: Context, id: Int) {
        synchronized(lock) {
            context.removeKey(FOLDER, id.toString())
        }
    }

    /** Clears the "+N new" count of a novel, e.g. when its result page is opened. */
    fun markSeen(context: Context, id: Int) {
        val changedState = synchronized(lock) {
            val stored = get(context, id) ?: return
            val seen = ChapterUpdateDetector.markSeen(stored)
            if (seen === stored) return
            context.setKey(FOLDER, id.toString(), seen)
            seen
        }
        try {
            LibraryUpdateNotifier.cancelNovel(context, changedState.id)
            changed(id)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /**
     * Hooks the store into the app: opening a novel's result page (or reading it) clears its new
     * chapter count. Called once from the Application.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        BookDownloader2.openChanged += { id ->
            try {
                markSeen(appContext, id)
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }
}
