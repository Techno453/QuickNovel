package com.lagradost.quicknovel.ui.reader

import android.annotation.SuppressLint
import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.ChapterUpdate
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.SpanDisplay
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.databinding.SingleOverscrollChapterBinding
import com.lagradost.quicknovel.generateId
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.ui.ReadingType
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityItem
import com.lagradost.quicknovel.ui.TextAdapter
import com.lagradost.quicknovel.ui.TextVisualLine
import com.lagradost.quicknovel.ui.ViewHolderState
import java.lang.Integer.max
import kotlin.math.absoluteValue

/**
 * The scrolling text display: a RecyclerView of paragraphs (TextAdapter), the reading position
 * derived from the visible lines, seeking to a saved position, the TTS line lock (the list
 * follows the line being read), the overscroll-to-change-chapter gesture and volume-key paging.
 */
class ReaderScrollController(
    private val activity: AppCompatActivity,
    private val binding: ReadMainBinding,
    private val viewModel: ReadActivityViewModel,
    val textAdapter: TextAdapter,
    private val topBarHeight: () -> Int,
) {
    private val textLayoutManager = LinearLayoutManager(binding.realText.context)

    /** The list last submitted to the adapter (the chapters around the current one). */
    private var cachedChapter: List<SpanDisplay> = emptyList()

    // TTS lock: while TTS reads with "lock TTS" on, scrolling is limited to keep the line visible.
    private var lockTop: Int? = null
    private var lockBottom: Int? = null
    private var currentScroll: Int = 0

    /** Bumped by every seek, so a pending line seek from an older one gives up. */
    private var seekGeneration = 0

    private fun transformIndexToScrollVisibilityItem(adapterPosition: Int): ScrollVisibilityItem {
        return ScrollVisibilityItem(
            adapterPosition = adapterPosition,
            viewHolder = binding.realText.findViewHolderForAdapterPosition(adapterPosition),
        )
    }

    private fun getTopY(): Int {
        val outLocation = IntArray(2)
        binding.readTopItem.getLocationInWindow(outLocation)
        val (_, topY) = outLocation
        return topY + binding.realText.paddingTop
    }

    private fun getBottomY(): Int {
        val outLocation = IntArray(2)
        binding.readBottomItem.getLocationInWindow(outLocation)
        val (_, bottomY) = outLocation
        return bottomY - max(
            binding.realText.paddingBottom,
            if (viewModel.showTime || viewModel.showBattery) binding.readOverlay.height else 0
        )
    }

    /**

    ________________
    [ hello ]
    -- screen cut --
    [ world ]
    [ ! ]
    [ From kotlin ]
    ________________

    here the first index of "world" would be stored as it is the first whole line visible,
    while "hello" would be stored as the first invisible line, this is used to scroll the exact char
    you are on. so while rotating you would rotate to the first line that contains "world"

    This is also used for TTS because TTS *must* start at the first visible whole sentence,
    so in this case it would start at "From kotlin" because hello is not visible.
     */
    private fun getAllLines(): ArrayList<TextVisualLine> {
        val lines: ArrayList<TextVisualLine> = arrayListOf()

        for (i in textLayoutManager.findFirstVisibleItemPosition()..textLayoutManager.findLastVisibleItemPosition()) {
            lines.addAll(textAdapter.getLines(transformIndexToScrollVisibilityItem(i)))
        }
        return lines
    }

    private fun postLines(lines: ArrayList<TextVisualLine>) {
        if (lines.isEmpty()) {
            return
        }

        val topY = getTopY()
        val bottomY = getBottomY()
        val topBar = topBarHeight()

        viewModel.onScroll(
            ScrollVisibilityIndex(
                firstInMemory = lines.first(),
                lastInMemory = lines.last(),
                firstFullyVisible = lines.getOrNull(
                    ReadingAnchor.pick(
                        IntArray(lines.size) { lines[it].top },
                        IntArray(lines.size) { lines[it].bottom },
                        topY,
                        bottomY,
                    )
                ),
                firstFullyVisibleUnderLine = lines.firstOrNull {
                    it.top >= topBar
                },
                lastHalfVisible = lines.firstOrNull {
                    it.bottom >= bottomY
                },
            )
        )
    }

    fun onScroll() {
        postLines(getAllLines())
    }

    /** Scrolls to [ReadActivityViewModel.desiredIndex] (chapter, paragraph, then line). */
    fun scrollToDesired() {
        val desired: ScrollIndex = viewModel.desiredIndex ?: return
        val adapterPosition =
            cachedChapter.indexOfFirst { display -> display.index == desired.index && display.innerIndex == desired.innerIndex }
        if (adapterPosition == -1) return

        textLayoutManager.scrollToPositionWithOffset(adapterPosition, 1)

        // don't inner-seek at the chapter start (the title row shares innerIndex 0 with the first
        // paragraph); a position inside the first paragraph still seeks to its line
        if (desired.innerIndex == 0 && desired.char <= 0) {
            binding.realText.post { onScroll() }
            return
        }
        seekToLine(desired, ++seekGeneration, SEEK_ATTEMPTS)
    }

    /**
     * Scrolls the line holding [desired].char to the top. On a cold start the list is not laid out
     * yet when the first post runs (no lines), so it retries on the following frames. The position
     * is recorded only after the seek: recording the paragraph top first made the next window
     * update seek back to the paragraph start.
     */
    private fun seekToLine(desired: ScrollIndex, generation: Int, attemptsLeft: Int) {
        binding.realText.postOnAnimation {
            if (generation != seekGeneration) return@postOnAnimation // a newer seek won
            val line = getAllLines().firstOrNull { line ->
                // endChar is exclusive: a line ending at the char is the line before it
                line.index == desired.index && line.endChar > desired.char
            }
            if (line == null && attemptsLeft > 0) {
                seekToLine(desired, generation, attemptsLeft - 1)
                return@postOnAnimation
            }
            line?.let { binding.realText.scrollBy(0, it.top - getTopY()) }
            postLines(getAllLines())
        }
    }

    /** Re-seeks to the current position (and TTS line) after the next layout pass. */
    fun postDesired() {
        val currentDesired = viewModel.desiredIndex
        binding.realText.post {
            viewModel.desiredIndex = currentDesired
            scrollToDesired()
            updateTTSLine(viewModel.ttsLine.value)
        }
    }

    /** Highlights [line] and, with the TTS lock on, keeps it on screen. */
    fun updateTTSLine(line: TTSHelper.TTSLine?, depth: Int = 0) {
        // update the visual component
        textAdapter.updateTTSLine(line)
        val first = textLayoutManager.findFirstVisibleItemPosition()
        val last = textLayoutManager.findLastVisibleItemPosition()
        textAdapter.notifyItemRangeChanged(first, last + 1 - first)

        // update the lock area
        if (line == null || !viewModel.ttsLock) {
            lockTop = null
            lockBottom = null
            return
        }

        val lines = getAllLines()
        postLines(lines)

        val top = lines.firstOrNull { it.index == line.index && it.endChar > line.startChar }
        val bottom =
            lines.firstOrNull { it.index == line.index && it.startChar <= line.endChar && line.endChar <= it.endChar }

        if (top == null || bottom == null) {
            lockTop = null
            lockBottom = null

            // this should never happened as tts line must be valid
            val innerIndex = viewModel.innerCharToIndex(line.index, line.startChar) ?: return

            // scroll to the top of that line, first search the adapter
            val adapterPosition =
                cachedChapter.indexOfFirst { display -> display.index == line.index && display.innerIndex == innerIndex }

            // if we tts out of bounds somehow? we scroll to that and refresh everything
            if (adapterPosition == -1) {
                viewModel.scrollToDesired(
                    ScrollIndex(
                        index = line.index,
                        innerIndex = innerIndex,
                        line.startChar
                    )
                )
                return
            }

            if (depth < 3) {
                textLayoutManager.scrollToPositionWithOffset(adapterPosition, 1)
                textLayoutManager.postOnAnimation {
                    updateTTSLine(line, depth = depth + 1)
                }
            }

            return
        }

        val topScroll = top.top - getTopY()
        lockTop = currentScroll + topScroll
        val bottomScroll =
            bottom.bottom - getBottomY()
        lockBottom = currentScroll + bottomScroll

        // we have reached the end, scroll to the top
        if (bottomScroll > 0) {
            binding.realText.scrollBy(0, topScroll)
        }
        // we have scrolled up while being on top
        else if (topScroll < 0) {
            binding.realText.scrollBy(0, topScroll)
        }
    }

    /** Volume down: scroll so the first partly hidden line becomes the top line. */
    fun pageDown() {
        val bottomY = getBottomY()
        val lines = getAllLines()
        val line = lines.firstOrNull {
            it.bottom >= bottomY
        } ?: lines.lastOrNull() ?: return
        binding.realText.scrollBy(0, line.top - getTopY())
    }

    /** Volume up: scroll back one screen, aligned to a whole line. */
    fun pageUp() {
        binding.realText.scrollBy(0, getTopY() - getBottomY())
        binding.realText.post {
            val lines = getAllLines()
            val topY = getTopY()
            val line = lines.firstOrNull {
                it.top >= topY
            } ?: return@post
            binding.realText.scrollBy(0, line.top - getTopY())
        }
    }

    /** Rebinds every row (font, size, colours changed). It does not happen often. */
    @SuppressLint("NotifyDataSetChanged")
    fun rebindAll() {
        textAdapter.notifyDataSetChanged()
    }

    /** New chapter window from the view model. */
    fun submit(chapter: ChapterUpdate) {
        cachedChapter = chapter.data

        if (chapter.seekToDesired) {
            textAdapter.submitIncomparableList(chapter.data) {
                viewModel._loadingStatus.postValue(Resource.Success(true))
                // scrollToDesired records the position once the seek has run
                scrollToDesired()
            }
        } else {
            textAdapter.submitList(chapter.data) {
                viewModel._loadingStatus.postValue(Resource.Success(true))
                onScroll()
            }
        }
    }

    private companion object {
        /** Frames to wait for the list to lay out the seek target (about half a second). */
        const val SEEK_ATTEMPTS = 30
    }

    // ===================== overscroll to change chapter (OVERSCROLL_SCROLL) =====================

    private var currentOverScrollValue = 0.0f

    private fun setProgressOfOverscroll(index: Int, progress: Float) {
        val id = generateId(5, index, 0, 0)
        ((binding.realText.findViewHolderForItemId(id) as? ViewHolderState<*>)?.view as? SingleOverscrollChapterBinding)?.let {
            it.progress.max = 10000
            it.progress.progress = (progress.absoluteValue * 10000.0f).toInt()
            it.progress.alpha = if (progress.absoluteValue > 0.05f) 1.0f else 0.0f
        }
    }

    private var currentOverScroll: Float
        get() = currentOverScrollValue
        set(value) {
            currentOverScrollValue = if (viewModel.readerType != ReadingType.OVERSCROLL_SCROLL) {
                0.0f
            } else {
                val setTo = value.coerceIn(-1.0f, 1.0f)
                if (setTo == 0.0f) {
                    setProgressOfOverscroll(viewModel.currentIndex + 1, setTo)
                    setProgressOfOverscroll(viewModel.currentIndex - 1, setTo)
                    if (currentOverScrollValue > 0.9) {
                        viewModel.seekToChapter(viewModel.currentIndex - 1)
                    } else if (currentOverScrollValue < -0.9) {
                        viewModel.seekToChapter(viewModel.currentIndex + 1)
                    }
                } else {
                    setProgressOfOverscroll(
                        viewModel.currentIndex + if (setTo < 0.0f) 1 else -1,
                        setTo
                    )
                }
                setTo
            }
        }

    /** Adapter, layout manager, touch (overscroll) and scroll (position, TTS lock) listeners. */
    @SuppressLint("ClickableViewAccessibility")
    fun bind() {
        binding.realText.apply {
            layoutManager = textLayoutManager
            adapter = textAdapter
            itemAnimator = null
            // testing overscroll
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        if (event.historySize <= 1) return@setOnTouchListener false
                        val start = event.getHistoricalY(0, event.historySize - 1)
                        val end = event.getY(0)
                        val dy = (end - start).div(Resources.getSystem().displayMetrics.density)
                            .coerceIn(-1.5f, 1.5f)
                        // if cant scroll in the direction then translate Y with the dy
                        val translated = !canScrollVertically(-1) || !canScrollVertically(1)
                        if (translated) {
                            currentOverScroll += dy * 0.1f
                        }

                        // if we can scroll down then we cant translate down
                        if (canScrollVertically(1) && currentOverScroll < 0.0f) {
                            currentOverScroll = 0.0f
                            return@setOnTouchListener false
                        }

                        // if we can scroll up then we cant translate up
                        if (canScrollVertically(-1) && currentOverScroll > 0.0f) {
                            currentOverScroll = 0.0f
                            return@setOnTouchListener false
                        }

                        return@setOnTouchListener false
                    }

                    MotionEvent.ACTION_UP -> {
                        currentOverScroll = 0.0f
                    }

                    else -> {}
                }
                return@setOnTouchListener false
            }

            addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var updateFromCode = false
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0 && !updateFromCode) {
                        var rdy = dy

                        lockTop?.let { lock ->
                            if (currentScroll + rdy > lock) {
                                rdy = lock - currentScroll
                                fling(0, 0)
                            }
                        }

                        lockBottom?.let { lock ->
                            if (currentScroll + rdy < lock) {
                                rdy = lock - currentScroll
                                fling(0, 0)
                            }
                        }

                        if (currentOverScroll < 0.0f && rdy < 0) {
                            rdy = 0
                        } else if (currentOverScroll > 0.0f && rdy > 0) {
                            rdy = 0
                        }

                        currentScroll += dy
                        val delta = rdy - dy
                        if (delta != 0 && canScrollVertically(delta)) {
                            scrollBy(0, delta)
                        }
                    } else {
                        updateFromCode = false
                        onScroll()
                    }

                    super.onScrolled(recyclerView, dx, dy)
                }

                //this is to reduce onScroll calls
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_SETTLING || newState == RecyclerView.SCROLL_STATE_IDLE) {
                        onScroll()
                    }
                }
            })
        }
    }

    val view: View get() = binding.realText
}
