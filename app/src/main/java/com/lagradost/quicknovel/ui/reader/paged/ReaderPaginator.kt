package com.lagradost.quicknovel.ui.reader.paged

import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.TextView
import androidx.core.text.getSpans
import com.lagradost.quicknovel.ChapterStartSpanned
import com.lagradost.quicknovel.FailedSpanned
import com.lagradost.quicknovel.LoadingSpanned
import com.lagradost.quicknovel.SpanDisplay
import com.lagradost.quicknovel.TextSpan
import com.lagradost.quicknovel.ui.UiText
import io.noties.markwon.image.AsyncDrawableSpan

/** One page of the paged reader. Positions use the same chapter-relative chars as scroll mode. */
sealed class ReaderPage(val chapter: Int) {
    /** First character on the page (-1 for the chapter title page / a status page). */
    abstract val startChar: Int

    /** Paragraph (TextSpan.innerIndex) the page starts in. */
    abstract val innerIndex: Int

    /** Last character on the page (exclusive), for TTS lookups. */
    abstract val endChar: Int

    class Title(chapter: Int, val name: UiText) : ReaderPage(chapter) {
        override val startChar = -1
        override val innerIndex = 0
        override val endChar = 0
    }

    class Text(chapter: Int, val slices: List<Slice>) : ReaderPage(chapter) {
        override val startChar get() = slices.first().startChar
        override val innerIndex get() = slices.first().span.innerIndex
        override val endChar get() = slices.last().endChar
    }

    class Image(chapter: Int, val span: TextSpan) : ReaderPage(chapter) {
        override val startChar get() = span.start
        override val innerIndex get() = span.innerIndex
        override val endChar get() = span.end
    }

    /** Loading or failed chapter. */
    class Status(chapter: Int, val item: SpanDisplay) : ReaderPage(chapter) {
        override val startChar = -1
        override val innerIndex = 0
        override val endChar = 0
    }
}

/**
 * Part of a paragraph on a page: lines [firstLine, endLine) of [layout] drawn at [y] in the
 * page's text area. [layout] is null for a separator rule of height [ruleHeight].
 */
class Slice(
    val span: TextSpan,
    val layout: StaticLayout?,
    val firstLine: Int,
    val endLine: Int,
    val y: Int,
    val ruleHeight: Int = 0,
) {
    val startChar: Int get() = span.start + (layout?.getLineStart(firstLine) ?: 0)
    val endChar: Int get() = if (layout == null) span.end else span.start + layout.getLineEnd(endLine - 1)
}

/**
 * Text measurements for pagination, taken from a TextView styled exactly like a reader paragraph
 * (font, size, line spacing, paragraph padding), so pages wrap like the scroll view.
 */
data class PageMetrics(
    val width: Int,
    val height: Int,
    val paint: TextPaint,
    val spacingAdd: Float,
    val spacingMult: Float,
    val breakStrategy: Int,
    val hyphenation: Int,
    val includePad: Boolean,
    val paraPadTop: Int,
    val paraPadBottom: Int,
    val ruleHeight: Int,
    val bionic: Boolean,
) {
    companion object {
        fun from(styled: TextView, width: Int, height: Int, ruleHeight: Int, bionic: Boolean): PageMetrics {
            val breakStrategy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) styled.breakStrategy else 0
            val hyphenation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) styled.hyphenationFrequency else 0
            return PageMetrics(
                width = width,
                height = height,
                // TextView only applies its text colour to the paint while drawing
                paint = TextPaint(styled.paint).apply { color = styled.currentTextColor },
                spacingAdd = styled.lineSpacingExtra,
                spacingMult = styled.lineSpacingMultiplier,
                breakStrategy = breakStrategy,
                hyphenation = hyphenation,
                includePad = styled.includeFontPadding,
                paraPadTop = styled.paddingTop,
                paraPadBottom = styled.paddingBottom,
                ruleHeight = ruleHeight,
                bionic = bionic,
            )
        }
    }
}

/** Lays out one chapter's paragraphs with StaticLayout and cuts them into pages ([PageBreaker]). */
object ReaderPaginator {
    /** A full line of these characters is drawn as a separator (same rule as TextAdapter). */
    private val separatorRegex = Regex("[=\\-_\\s━*]*")

    private fun layout(text: CharSequence, m: PageMetrics): StaticLayout {
        val width = m.width.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, m.paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(m.spacingAdd, m.spacingMult)
                .setIncludePad(m.includePad)
                .setBreakStrategy(m.breakStrategy)
                .setHyphenationFrequency(m.hyphenation)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, m.paint, width, Layout.Alignment.ALIGN_NORMAL, m.spacingMult, m.spacingAdd, m.includePad)
        }
    }

    private fun isImage(span: TextSpan) = span.text.getSpans<AsyncDrawableSpan>(0, span.text.length).isNotEmpty()

    /**
     * Pages of chapter [chapter] from its items in reading order (ChapterStartSpanned, TextSpans,
     * or a LoadingSpanned / FailedSpanned). Images get pages of their own.
     */
    fun paginateChapter(chapter: Int, items: List<SpanDisplay>, m: PageMetrics): List<ReaderPage> {
        val pages = ArrayList<ReaderPage>()
        val run = ArrayList<TextSpan>()

        fun flushRun() {
            if (run.isEmpty()) return
            val layouts = run.map { span ->
                if (span.text.matches(separatorRegex)) null
                else layout(if (m.bionic) span.bionicText else span.text, m)
            }
            val blocks = layouts.map { l ->
                if (l == null) PageBreaker.Block.Fixed(m.ruleHeight)
                else PageBreaker.Block.Lines(IntArray(l.lineCount + 1) { l.getLineTop(it) }, m.paraPadTop, m.paraPadBottom)
            }
            for (page in PageBreaker.paginate(blocks, m.height.coerceAtLeast(1))) {
                pages += ReaderPage.Text(chapter, page.map { p ->
                    Slice(run[p.block], layouts[p.block], p.firstLine, p.endLine, p.y, if (layouts[p.block] == null) m.ruleHeight else 0)
                })
            }
            run.clear()
        }

        for (item in items) {
            when (item) {
                is ChapterStartSpanned -> {
                    flushRun()
                    pages += ReaderPage.Title(chapter, item.name)
                }

                is TextSpan -> {
                    if (isImage(item)) {
                        flushRun()
                        pages += ReaderPage.Image(chapter, item)
                    } else {
                        run += item
                    }
                }

                is LoadingSpanned, is FailedSpanned -> {
                    flushRun()
                    pages += ReaderPage.Status(chapter, item)
                }

                else -> {}
            }
        }
        flushRun()
        return pages
    }

    /**
     * Index of the page showing chapter [chapter] at [char] (see ScrollIndex): the title page for
     * a negative char, else the last page of the chapter starting at or before [char].
     */
    fun pageFor(pages: List<ReaderPage>, chapter: Int, char: Int): Int {
        val first = pages.indexOfFirst { it.chapter == chapter }
        if (first == -1) return -1
        if (char < 0) return first
        var best = first
        for (i in first until pages.size) {
            val p = pages[i]
            if (p.chapter != chapter) break
            if (p.startChar in 0..char) best = i
        }
        return best
    }
}
