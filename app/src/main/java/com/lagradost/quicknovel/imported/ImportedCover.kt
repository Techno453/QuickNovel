package com.lagradost.quicknovel.imported

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import java.io.File

/**
 * Placeholder cover for imported books without one (every TXT import, cover-less EPUBs): the
 * title on a background colour derived from the book id ([ImportedLibraryCard.coverColor]),
 * written as the import's `poster.jpg` so the library, result page and reader find a cover.
 */
object ImportedCover {
    private const val TAG = "ImportedCover"
    const val WIDTH = 408
    const val HEIGHT = 600
    private const val MAX_TITLE_LINES = 7

    /** Draws the placeholder for [title] / [bookId]. */
    fun draw(title: String, bookId: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ImportedLibraryCard.coverColor(bookId))

        // A thin inner frame, like a printed cover.
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = (ImportedLibraryCard.TEXT_COLOR and 0x00FFFFFF) or 0x66000000
        }
        canvas.drawRoundRect(RectF(18f, 18f, WIDTH - 18f, HEIGHT - 18f), 10f, 10f, frame)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ImportedLibraryCard.TEXT_COLOR
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = if (title.length <= 12) 52f else 42f
        }
        val textWidth = WIDTH - 2 * 44
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .setMaxLines(MAX_TITLE_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(44f, ((HEIGHT - layout.height) / 2f).coerceAtLeast(44f))
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    /** Writes the placeholder to [file] unless a cover is already there. True when written. */
    fun writeIfMissing(file: File, title: String, bookId: String): Boolean {
        if (file.exists() && file.length() > 0) return false
        return try {
            file.parentFile?.mkdirs()
            val bitmap = draw(title, bookId)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write a placeholder cover for $bookId", t)
            false
        }
    }
}
