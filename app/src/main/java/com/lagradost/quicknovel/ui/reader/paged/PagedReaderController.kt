package com.lagradost.quicknovel.ui.reader.paged

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.GestureDetector
import android.view.ActionMode
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lagradost.quicknovel.ChapterUpdate
import com.lagradost.quicknovel.FailedSpanned
import com.lagradost.quicknovel.LoadingSpanned
import com.lagradost.quicknovel.MLException
import com.lagradost.quicknovel.QuickBook
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.SpanDisplay
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.TextSpan
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.databinding.SingleTextBinding
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.ui.CONFIG_COLOR
import com.lagradost.quicknovel.ui.CONFIG_FONT
import com.lagradost.quicknovel.ui.CONFIG_FONT_BOLD
import com.lagradost.quicknovel.ui.CONFIG_PADDING
import com.lagradost.quicknovel.ui.CONFIG_SIZE
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.TextConfig
import com.lagradost.quicknovel.ui.TextInteraction
import com.lagradost.quicknovel.ui.reader.GlossaryLookup
import com.lagradost.quicknovel.ui.reader.OriginalNotice
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.UIHelper
import io.noties.markwon.image.AsyncDrawableSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Page-flip reading mode ("Reading mode: Pages"). Takes the same chapter window as the scroll
 * view ([ChapterUpdate]), cuts it into pages that fit the text area exactly ([ReaderPaginator],
 * StaticLayout with the reader's font, size, spacing and column width) and shows them in a
 * horizontal ViewPager2.
 *
 * - Taps: left third = previous page, right third = next page, middle third = toggle the bars.
 *   Swiping also turns pages. A tap on a link opens it (the span's own click handler, as in
 *   scroll mode) and wins over the page turn.
 * - Long-press selects the word; its handles extend the selection within the paragraph, and a
 *   floating toolbar offers Copy and Glossary.
 * - Position: the reader's usual chapter + character offset ([ScrollIndex]); a chapter's title
 *   page is char -1. Switching between scroll and pages keeps the place.
 * - Re-pagination when the text area or the text settings change (fold/unfold, rotation,
 *   margins, font...), keeping the current position.
 * - TTS: the page follows the line being read, which is highlighted.
 */
class PagedReaderController(
    private val activity: AppCompatActivity,
    private val binding: ReadMainBinding,
    private val viewModel: ReadActivityViewModel,
    /** The reader's current text settings (shared with the scroll view). */
    private val config: () -> TextConfig,
    /** Text area insets inside the pager: left, top, right, bottom (px). */
    private val insets: () -> Rect,
    /** Glossary hooks shared with the scroll view (long-press word / selection lookup). */
    private val interaction: TextInteraction,
) {
    private val pager: ViewPager2 get() = binding.readPages
    private val adapter = PageAdapter()

    var isActive = false
        private set

    private var pages: List<ReaderPage> = emptyList()
    private var lastUpdate: ChapterUpdate? = null
    private var ttsLine: TTSHelper.TTSLine? = null

    /** What the current [pages] were laid out for; a different key re-paginates. */
    private data class LayoutKey(val width: Int, val height: Int, val insets: Rect, val config: TextConfig, val density: Float)

    private var laidOutFor: LayoutKey? = null
    private var laidOutData: ChapterUpdate? = null

    private class CachedChapter(val key: LayoutKey, val items: List<SpanDisplay>, val pages: List<ReaderPage>)

    private val cache = HashMap<Int, CachedChapter>()
    private var job: Job? = null
    private var generation = 0

    /** Set while the user drags the pager, so the settled page is reported as the position. */
    private var userDragging = false

    /**
     * Page the pager must show after a (re-)pagination, until the user turns a page. ViewPager2
     * can reset its current item while it lays out a changed list; this puts it back.
     */
    private var pinnedPage = -1

    @SuppressLint("ClickableViewAccessibility")
    fun bind() {
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    userDragging = true
                    pinnedPage = -1
                }
                if (state == ViewPager2.SCROLL_STATE_IDLE) userDragging = false
            }

            override fun onPageSelected(position: Int) {
                if (position != selectionPage) clearSelection()
                updatePageNumber(position)
                if (userDragging) {
                    report(position)
                } else if (pinnedPage >= 0 && position != pinnedPage) {
                    pager.post {
                        if (pinnedPage in pages.indices && pager.currentItem != pinnedPage) {
                            pager.setCurrentItem(pinnedPage, false)
                        }
                    }
                }
            }
        })
        pager.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                pager.post { refresh() }
            }
        }
    }

    /** Shows the pages instead of the scrolling list (or back). */
    fun setActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        binding.readerLinContainer.isGone = active
        pager.isVisible = active
        binding.readPageNumber.isVisible = active
        if (!active) {
            clearSelection()
            job?.cancel()
            pages = emptyList()
            adapter.submitList(emptyList())
            lastUpdate = null
            laidOutFor = null
            laidOutData = null
            synchronized(cache) { cache.clear() }
        }
    }

    /** New chapter window from the view model. */
    fun submit(update: ChapterUpdate) {
        lastUpdate = update
        paginate(force = true)
    }

    /** Text area or text settings may have changed: re-paginate if they did, keep the place. */
    fun refresh() {
        paginate(force = false)
    }

    // ============================== navigation ==============================

    fun nextPage() = turnTo(pager.currentItem + 1)

    fun previousPage() = turnTo(pager.currentItem - 1)

    private fun turnTo(position: Int) {
        if (position < 0 || position >= pages.size) return
        pinnedPage = position
        pager.setCurrentItem(position, true)
        report(position)
    }

    /** The user is now on page [position]: save it as the reading position. */
    private fun report(position: Int) {
        val page = pages.getOrNull(position) ?: return
        viewModel.onPagedPosition(ScrollIndex(page.chapter, page.innerIndex, page.startChar))
    }

    private fun updatePageNumber(position: Int = pager.currentItem) {
        val page = pages.getOrNull(position)
        if (page == null || page is ReaderPage.Status) {
            binding.readPageNumber.text = ""
            return
        }
        val first = pages.indexOfFirst { it.chapter == page.chapter }
        val count = pages.count { it.chapter == page.chapter }
        binding.readPageNumber.text =
            activity.getString(R.string.reader_page_progress, position - first + 1, count)
    }

    // ============================== TTS ==============================

    /** Highlights [line] and turns to its page. */
    fun updateTTSLine(line: TTSHelper.TTSLine?) {
        ttsLine = line
        redrawText()
        if (line == null || pages.isEmpty()) return
        val target = ReaderPaginator.pageFor(pages, line.index, line.startChar)
        if (target == -1) {
            // read aloud went past the loaded chapters: move the window there
            val inner = viewModel.innerCharToIndex(line.index, line.startChar) ?: return
            viewModel.scrollToDesired(ScrollIndex(line.index, inner, line.startChar))
            return
        }
        if (target != pager.currentItem) {
            pinnedPage = target
            pager.setCurrentItem(target, abs(target - pager.currentItem) == 1)
            report(target)
        }
    }

    private fun redrawText() {
        val list = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until list.childCount) {
            (list.getChildAt(i) as? ViewGroup)?.getChildAt(0)?.let { if (it is PageTextView) it.invalidate() }
        }
    }

    // ============================== pagination ==============================

    private fun density() = activity.resources.displayMetrics.density

    /** A reader paragraph view, styled like the scroll view's rows, to measure with. */
    private fun styledParagraph(config: TextConfig): TextView {
        val text = SingleTextBinding.inflate(LayoutInflater.from(activity)).root
        config.setArgs(text, CONFIG_SIZE or CONFIG_COLOR or CONFIG_FONT or CONFIG_PADDING)
        return text
    }

    private fun paginate(force: Boolean) {
        if (!isActive) return
        val update = lastUpdate ?: return
        val width = pager.width
        val height = pager.height
        if (width <= 0 || height <= 0) {
            // Not laid out yet (the reader hides its content while loading): show the content
            // area; the pager's layout listener paginates once it has a size.
            viewModel._loadingStatus.postValue(Resource.Success(true))
            return
        }

        val inset = insets()
        val cfg = config()
        val key = LayoutKey(width, height, inset, cfg.copy(toolbarHeight = 0), density())
        if (!force && key == laidOutFor && update === laidOutData) return

        val textWidth = (width - inset.left - inset.right).coerceAtLeast(1)
        val textHeight = (height - inset.top - inset.bottom).coerceAtLeast(1)
        val metrics = PageMetrics.from(
            styledParagraph(cfg),
            textWidth,
            textHeight,
            ruleHeight = (12 * density()).toInt(),
            bionic = cfg.bionicReading
        )
        val items = update.data
        val gen = ++generation
        job?.cancel()
        job = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { build(items, key, metrics) }
            if (gen != generation || !isActive) return@launch
            laidOutFor = key
            laidOutData = update
            show(result, inset, cfg)
        }
    }

    /** Pages of every chapter in [items], reusing chapters whose items and layout did not change. */
    private fun build(items: List<SpanDisplay>, key: LayoutKey, metrics: PageMetrics): List<ReaderPage> {
        val byChapter = LinkedHashMap<Int, MutableList<SpanDisplay>>()
        for (item in items) byChapter.getOrPut(item.index) { ArrayList() } += item
        val result = ArrayList<ReaderPage>()
        val fresh = HashMap<Int, CachedChapter>()
        for ((chapter, chapterItems) in byChapter) {
            val cached = synchronized(cache) { cache[chapter] }
            val chapterPages = if (cached != null && cached.key == key && sameItems(cached.items, chapterItems)) {
                cached.pages
            } else {
                ReaderPaginator.paginateChapter(chapter, chapterItems, metrics)
            }
            fresh[chapter] = CachedChapter(key, chapterItems, chapterPages)
            result += chapterPages
        }
        synchronized(cache) {
            cache.clear()
            cache.putAll(fresh)
        }
        return result
    }

    private fun sameItems(a: List<SpanDisplay>, b: List<SpanDisplay>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x is TextSpan || y is TextSpan) {
                if (x !== y) return false
            } else if (x != y) return false
        }
        return true
    }

    private var shownInsets = Rect()
    private var shownConfig: TextConfig? = null

    private fun show(result: List<ReaderPage>, inset: Rect, cfg: TextConfig) {
        val restyle = inset != shownInsets || cfg != shownConfig
        clearSelection()
        shownInsets = inset
        shownConfig = cfg
        pages = result
        adapter.submitList(result) {
            if (restyle) adapter.notifyItemRangeChanged(0, result.size)
            val desired = viewModel.desiredIndex
            val target = if (desired != null) {
                ReaderPaginator.pageFor(result, desired.index, desired.char)
            } else -1
            if (target >= 0) {
                pinnedPage = target
                if (target != pager.currentItem) pager.setCurrentItem(target, false)
            }
            updatePageNumber()
            viewModel._loadingStatus.postValue(Resource.Success(true))
        }
    }

    // ============================== selection ==============================

    /** The page view holding the selection, its page, and the floating Copy / Glossary toolbar. */
    private var selectionView: PageTextView? = null
    private var selectionPage = -1
    private var actionMode: ActionMode? = null

    /** The long-pressed character and the word it selected (Glossary looks that up like scroll mode). */
    private var pressedOffset = -1
    private var pressedRange: PageSelection.Range? = null

    private fun startSelection(view: PageTextView, span: TextSpan, offset: Int, position: Int) {
        clearSelection()
        val word = GlossaryLookup.wordAt(span.text, offset) ?: return
        view.select(span, PageSelection.Range(word.first, word.last + 1))
        val selected = view.selection ?: return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selectionView = view
        selectionPage = position
        pressedOffset = offset
        pressedRange = selected.range
        actionMode = view.startActionMode(SelectionCallback(view), ActionMode.TYPE_FLOATING)
    }

    /** Ends the selection mode (tap, page turn, re-pagination, Copy / Glossary, back). */
    fun clearSelection() {
        val view = selectionView ?: return
        selectionView = null
        selectionPage = -1
        pressedRange = null
        pressedOffset = -1
        view.clearSelection()
        actionMode?.let {
            actionMode = null
            it.finish()
        }
    }

    private inner class SelectionCallback(private val view: PageTextView) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
            if (interaction.selectionLookupEnabled) {
                menu.add(Menu.NONE, R.id.reader_glossary_action, 1, R.string.reader_glossary_action)
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val selected = view.selection ?: return false
            when (item.itemId) {
                android.R.id.copy -> {
                    val text = view.selectedText() ?: return false
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(null, text.toString()))
                    // Android 13+ confirms copies itself
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(activity, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                    }
                }

                R.id.reader_glossary_action -> {
                    val span = selected.span
                    if (selected.range == pressedRange && pressedOffset >= 0) {
                        // the long-pressed word as it was: extend it to the longest glossary term
                        interaction.onLongPress(span, pressedOffset)
                    } else {
                        interaction.onSelectionLookup(span, selected.range.start, selected.range.end)
                    }
                }

                else -> return false
            }
            clearSelection()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) {
                actionMode = null
                clearSelection()
            }
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            (view as? PageTextView)?.selectionRect(outRect)
        }
    }

    // ============================== pages ==============================

    private val requireCloudFlare: Boolean
        get() {
            val book = viewModel.book
            return book is QuickBook && Apis.getApiFromNameNull(book.data.meta.apiName)?.usesCloudFlareKiller == true
        }

    private inner class PageHolder(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        var page: ReaderPage? = null

        private val text: PageTextView? get() = root.getChildAt(0) as? PageTextView

        private val gestures = GestureDetector(root.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // a tap while selecting only ends the selection
                if (selectionView != null) {
                    clearSelection()
                    return true
                }
                val textView = text
                val link = textView?.linkAt(e.x, e.y)
                if (link != null) {
                    link.onClick(textView)
                    return true
                }
                val w = root.width
                when {
                    e.x < w / 3f -> previousPage()
                    e.x > w * 2f / 3f -> nextPage()
                    else -> onMiddleTap()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val textView = text ?: return
                val (slice, offset) = textView.charUnder(e.x, e.y) ?: textView.hit(e.x, e.y) ?: return
                startSelection(textView, slice.span, offset, bindingAdapterPosition)
            }
        })

        init {
            root.setOnTouchListener { _, event -> onTouch(event) }
        }

        /** Handle drags first (they must not turn the page), then taps and long-presses. */
        private fun onTouch(event: MotionEvent): Boolean {
            val textView = text
            if (textView != null && selectionView === textView && textView.selection != null) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val handle = textView.handleAt(event.x, event.y)
                        if (handle != null) {
                            textView.beginDrag(handle, event.x, event.y)
                            root.parent?.requestDisallowInterceptTouchEvent(true)
                            actionMode?.hide(60_000L) // hidden while dragging, shown again on release
                            return true
                        }
                    }

                    MotionEvent.ACTION_MOVE -> if (textView.isDragging) {
                        textView.dragTo(event.x, event.y)
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (textView.isDragging) {
                        textView.endDrag()
                        root.parent?.requestDisallowInterceptTouchEvent(false)
                        actionMode?.invalidateContentRect()
                        actionMode?.hide(0)
                        return true
                    }
                }
            }
            return gestures.onTouchEvent(event)
        }

        private fun onMiddleTap() {
            when (val p = page) {
                is ReaderPage.Status -> {
                    val failed = p.item as? FailedSpanned
                    if (failed?.cause != null) {
                        if (failed.cause is MLException) viewModel.reTranslateChapter(failed.index)
                        else viewModel.reloadChapter(failed.index)
                        return
                    }
                }

                is ReaderPage.Image -> {
                    val img = p.span.text.getSpans(0, p.span.text.length, AsyncDrawableSpan::class.java)
                        .firstOrNull()
                    if (img != null) {
                        UIHelper.showImage(root.context, img.drawable, requireCloudFlare)
                        return
                    }
                }

                else -> {}
            }
            viewModel.switchVisibility()
        }
    }

    private object PageDiff : DiffUtil.ItemCallback<ReaderPage>() {
        override fun areItemsTheSame(oldItem: ReaderPage, newItem: ReaderPage) = oldItem === newItem
        override fun areContentsTheSame(oldItem: ReaderPage, newItem: ReaderPage) = oldItem === newItem
    }

    private inner class PageAdapter : ListAdapter<ReaderPage, PageHolder>(PageDiff) {
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is ReaderPage.Text -> 0
            is ReaderPage.Title -> 1
            is ReaderPage.Image -> 2
            is ReaderPage.Status -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val ctx = parent.context
            val root = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val child: View = when (viewType) {
                0 -> PageTextView(ctx) { ttsLine }
                1 -> TextView(ctx).apply { gravity = Gravity.CENTER }
                2 -> ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = false
                }

                else -> LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(ProgressBar(ctx), LinearLayout.LayoutParams((40 * density()).toInt(), (40 * density()).toInt()))
                    addView(TextView(ctx).apply { gravity = Gravity.CENTER })
                }
            }
            root.addView(child, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            return PageHolder(root)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = getItem(position)
            holder.page = page
            val cfg = config()
            val inset = shownInsets
            when (val child = holder.root.getChildAt(0)) {
                is PageTextView -> {
                    child.areaLeft = inset.left
                    child.areaTop = inset.top
                    child.ruleColor = cfg.textColor
                    child.page = page as? ReaderPage.Text
                }

                is ImageView -> {
                    child.setPadding(inset.left, inset.top, inset.right, inset.bottom)
                    val span = (page as? ReaderPage.Image)?.span ?: return
                    val img = span.text.getSpans(0, span.text.length, AsyncDrawableSpan::class.java).firstOrNull() ?: return
                    UIHelper.bindImage(child, img.drawable, requireCloudFlare)
                }

                is LinearLayout -> {
                    child.setPadding(inset.left, inset.top, inset.right, inset.bottom)
                    val item = (page as? ReaderPage.Status)?.item
                    val progress = child.getChildAt(0)
                    val text = child.getChildAt(1) as TextView
                    cfg.setArgs(text, CONFIG_COLOR or CONFIG_FONT or CONFIG_FONT_BOLD)
                    when (item) {
                        is LoadingSpanned -> {
                            progress.isVisible = true
                            text.text = item.text.asString(text.context)
                        }

                        is FailedSpanned -> {
                            progress.isVisible = false
                            text.text = item.reason.asString(text.context)
                        }

                        else -> {
                            progress.isVisible = false
                            text.text = ""
                        }
                    }
                }

                is TextView -> {
                    child.setPadding(inset.left, inset.top, inset.right, inset.bottom)
                    cfg.setArgs(child, CONFIG_COLOR or CONFIG_FONT or CONFIG_FONT_BOLD)
                    child.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.5f)
                    val title = page as? ReaderPage.Title
                    child.text = if (title == null) "" else OriginalNotice.titleWithNotice(
                        title.name.asString(child.context), title.notice?.asString(child.context), sizeFactor = 0.5f
                    )
                }
            }
        }
    }

    companion object {
        /** Space for the page number under the text. */
        const val PAGE_NUMBER_HEIGHT_DP = 28
        const val TOP_MARGIN_DP = 16

        /** Text area insets for horizontal padding [h] and the user's vertical padding [v] (px). */
        fun insetsFor(h: Int, v: Int, density: Float): Rect = Rect(
            h,
            v + (TOP_MARGIN_DP * density).toInt(),
            h,
            max(v, 0) + (PAGE_NUMBER_HEIGHT_DP * density).toInt()
        )
    }
}
