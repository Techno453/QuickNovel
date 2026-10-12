package com.lagradost.quicknovel.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.util.AdaptiveLayout
import kotlin.math.abs

class GrdLayoutManager(val context: Context, val spanCoun: Int) : GridLayoutManager(context, spanCoun) {
    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        return try {
            val fromPos = getPosition(focused)
            println("Search failed $fromPos")
            val nextPos = getNextViewPos(fromPos, focusDirection)
            findViewByPosition(nextPos)
        } catch (e: Exception) {
            null
        }
    }

    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean {
        // android.widget.FrameLayout$LayoutParams cannot be cast to androidx.recyclerview.widget.RecyclerView$LayoutParams
        return try {
            val pos = maxOf(0, getPosition(focused!!) - 2)
            parent.scrollToPosition(pos)
            super.onRequestChildFocus(parent, state, child, focused)
        } catch (e: Exception){
            false
        }
    }

    // Allows moving right and left with focus https://gist.github.com/vganin/8930b41f55820ec49e4d
    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        return try {
            val fromPos = getPosition(focused)
            val nextPos = getNextViewPos(fromPos, direction)
            findViewByPosition(nextPos)
        } catch (e: Exception) {
            null
        }
    }

    private fun getNextViewPos(fromPos: Int, direction: Int): Int {
        val offset = calcOffsetToNextView(direction)

        if (hitBorder(fromPos, offset)) {
            return fromPos
        }

        return fromPos + offset
    }

    private fun calcOffsetToNextView(direction: Int): Int {
        println("calc")

        val spanCount = this.spanCoun
        val orientation = this.orientation

        if (orientation == VERTICAL) {
            when (direction) {
                View.FOCUS_DOWN -> {
                    return spanCount
                }
                View.FOCUS_UP -> {
                    return -spanCount
                }
                View.FOCUS_RIGHT -> {
                    return 1
                }
                View.FOCUS_LEFT -> {
                    return -1
                }

            }
        } else if (orientation == HORIZONTAL) {
            when (direction) {
                View.FOCUS_DOWN -> {
                    return 1
                }
                View.FOCUS_UP -> {
                    return -1
                }
                View.FOCUS_RIGHT -> {
                    return spanCount
                }
                View.FOCUS_LEFT -> {
                    return -spanCount
                }

            }
        }
        return 0
    }

    private fun hitBorder(from: Int, offset: Int): Boolean {
        val spanCount = spanCount

        return if (abs(offset) == 1) {
            val spanIndex = from % spanCount
            val newSpanIndex = spanIndex + offset
            newSpanIndex < 0 || newSpanIndex >= spanCount
        } else {
            val newPos = from + offset
            newPos in spanCount..-1
        }
    }
}

class AutofitRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    RecyclerView(context, attrs) {

    private val manager = GrdLayoutManager(context, 2) // THIS CONTROLS SPANS

    private var columnWidth = -1

    var spanCount = 0
        set(value) {
            field = value
            if (value > 0) {
                manager.spanCount = value
            }
        }

    val itemWidth: Int
        get() = measuredWidth / manager.spanCount

    private var adaptiveMinCellPx = 0
    private var adaptiveSpacingPx = 0
    private var adaptiveMinCount = 1

    /**
     * Derive the span count from the measured width instead of the orientation, so foldables,
     * split screen and tablets get a sensible number of columns. Re-evaluated on every size change.
     */
    fun setAdaptiveColumns(minCellDp: Int, minCount: Int = 1, spacingDp: Int = 4) {
        val density = resources.displayMetrics.density
        adaptiveMinCellPx = (minCellDp * density).toInt()
        adaptiveSpacingPx = (spacingDp * density).toInt()
        adaptiveMinCount = minCount
        if (width > 0) updateAdaptiveSpan(width, post = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) updateAdaptiveSpan(w, post = true)
    }

    private fun updateAdaptiveSpan(w: Int, post: Boolean) {
        if (adaptiveMinCellPx <= 0 || w <= 0) return
        val count = AdaptiveLayout.columnCount(
            availablePx = w - paddingLeft - paddingRight,
            minCellPx = adaptiveMinCellPx,
            spacingPx = adaptiveSpacingPx,
            minCount = adaptiveMinCount
        )
        if (count == manager.spanCount) return
        val apply = {
            spanCount = count
            // item sizes are derived from itemWidth at bind time
            adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
        }
        // onSizeChanged runs inside a layout pass, so defer the relayout
        if (post) post { apply() } else apply()
    }

    init {
        if (attrs != null) {
            val attrsArray = intArrayOf(android.R.attr.columnWidth)
            val array = context.obtainStyledAttributes(attrs, attrsArray)
            columnWidth = array.getDimensionPixelSize(0, -1)
            array.recycle()
        }

        layoutManager = manager
    }


    /*override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (spanCount == 0 && columnWidth > 0) {
            val count = max(1, measuredWidth / columnWidth)
            spanCount = count
        }
    }*/
}