package com.lagradost.quicknovel.ui.reader

import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.quicknovel.gaze.GazeFollower

/**
 * Eye-tracked following (docs/gaze-follow.md): the front camera counts the lines read; in scroll
 * mode the text glides up at the reader's pace, in paged mode the page turns after the last line.
 * The reader only talks to this interface; the camera and face model are in
 * [com.lagradost.quicknovel.gaze].
 */
interface ReaderFollow {
    /**
     * A text line wholly on screen, in window pixels: [left] and [right] where its text starts and
     * ends. [key] (chapter and first character) stays the same while the line moves.
     */
    data class Line(val key: Long, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        companion object {
            fun key(chapter: Int, startChar: Int): Long = (chapter.toLong() shl 32) or (startChar.toLong() and 0xFFFFFFFFL)
        }
    }

    /** What the follower needs from the reader. */
    interface Host {
        val activity: AppCompatActivity

        /** Scroll mode: the text glides with the reader ([scrollBy]); paged mode turns pages ([turnPage]). */
        val continuous: Boolean

        /** The text lines wholly on screen, top to bottom (images, titles and gaps left out). */
        fun lines(): List<Line>

        /** Where the text area starts and ends on screen (window pixels). */
        fun textArea(): IntRange

        /** Scroll mode: moves the text up by [dy] pixels. */
        fun scrollBy(dy: Int)

        /** Paged mode: turns to the next page; [last] is the last line read. */
        fun turnPage(last: Line)
    }

    /** The follow button in the reader's toolbar. */
    fun onMenu(anchor: View)

    /** A volume key; true when following used it. */
    fun onVolumeKey(down: Boolean): Boolean

    /** The user started ([active]) or stopped dragging the text or a page. */
    fun onUserScroll(active: Boolean)

    /** The reader moved the text without the follower (a drag, a page turn, a new chapter). */
    fun onUserMoved()

    fun onDestroy()

    companion object {
        /** The follower; null below Android 7 (MediaPipe needs API 24). */
        fun create(host: Host): ReaderFollow? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) GazeFollower(host) else null
    }
}
