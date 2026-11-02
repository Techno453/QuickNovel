package com.lagradost.quicknovel.gaze

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ui.reader.ReaderFollow
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * The reader's follow mode (docs/gaze-follow.md). The front camera counts the lines read
 * ([FollowEngine]); a bar left of the text marks the counted line.
 *
 * - **Scroll mode** glides the text up continuously, so the line being read rises to [READ_AT]
 *   of the text area and stays there: its top is kept at that height minus how far along the
 *   line the reader should be by now (time on the line against their pace). The text never moves
 *   back by itself and stands still while the eyes are away.
 * - **Paged mode** turns the page after the last line.
 * - **Start** (toolbar, Follow): finds a front camera that sees a face (a foldable has two, and
 *   the first is not always the one facing the reader), then calibrates once per screen and
 *   orientation: a dot at the start and the end of a line. Later starts reuse it.
 * - **While following**: volume up moves the counted line back one (after a reread was counted),
 *   volume down forward one (scroll mode) or to the next page (paged mode).
 * - **Dragging** stops everything while the finger is on the text: the glide would pull the text
 *   back, and eyes chasing moving text look like sweeps (the first test counted lines and
 *   scrolled back down after scrolling up). Afterwards, as after a page turned by hand or a new
 *   chapter, the count starts again where the reader most likely is, and in scroll mode the
 *   text stays put until a line end is seen (they may only be looking back).
 * - **Put down**: following goes on while the camera sees a face (leaning over the phone on a
 *   table); no face pauses the count and the text. Lying still screen-up ([RestDetector]) slows
 *   the camera's search for a face to about once a second.
 */
class GazeFollower(private val host: ReaderFollow.Host) : ReaderFollow {
    private val activity = host.activity
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val engine = FollowEngine().apply { dropScale = FollowEngine.SENSITIVITIES[sensitivity()] }
    private val overlay = FollowOverlay(activity)
    private val camera = FollowCamera(activity, ::onFrame)
    private val rest = RestDetector(activity, ::onRest)
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else toast(activity.getString(R.string.follow_needs_camera))
    }

    private enum class State { OFF, FINDING, CALIBRATING, FOLLOWING }

    private var state = State.OFF

    /** Calibrations and camera choices are kept per [screenKey]. */
    private var screen = ""
    private var calibration: Calibration? = null
    private var calibrateNext = false
    private var log: FollowLog? = null

    private var findingSinceMs = 0L

    private var dotIndex = 0
    private var dotStartMs = 0L
    private val leftSamples = ArrayList<Float>()
    private val rightSamples = ArrayList<Float>()
    private val openSamples = ArrayList<Float>()

    private var countedKey: Long? = null
    private var turning = false
    private var resting = false
    private var holdUntilMs = 0L

    /** A finger is on the text: nothing is counted or moved (eyes chasing moving text look like sweeps). */
    private var userScrolling = false

    /** After a scroll by hand the text stays put until a line end is seen (the reader may be looking back). */
    private var glideHeld = false

    private val anchorAfterMove = object : Runnable {
        override fun run() {
            if (state != State.FOLLOWING || turning) return
            if (userScrolling) {
                handler.postDelayed(this, MOVE_SETTLE_MS)
                return
            }
            log?.event(SystemClock.uptimeMillis(), "MOVED")
            anchor(atReadingHeight = host.continuous)
            glideHeld = host.continuous
        }
    }

    /** The text column, from the widest lines seen at this window width. */
    private var columnLeft = Int.MAX_VALUE
    private var columnRight = Int.MIN_VALUE
    private var columnWidthFor = 0

    // Scroll mode's glide, run on every display frame while following
    private val choreographer = Choreographer.getInstance()
    private var gliding = false
    private var lastGlideNanos = 0L
    private var glideCarry = 0f
    private val glide = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (state != State.FOLLOWING) {
                gliding = false
                return
            }
            val stepMs = if (lastGlideNanos == 0L) 0f else (frameTimeNanos - lastGlideNanos) / 1_000_000f
            lastGlideNanos = frameTimeNanos
            if (host.continuous) glideStep(minOf(stepMs, MAX_GLIDE_STEP_MS))
            choreographer.postFrameCallback(this)
        }
    }

    private class Calibration(val left: Float, val right: Float, val snr: Float, val blinkBelow: Float) {
        fun normalize(raw: Float) = (raw - left) / (right - left)
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (state != State.OFF) {
                    resting = false
                    camera.setResting(false)
                    rest.start()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    rest.stop()
                    log?.flush()
                }

                else -> {}
            }
        })
    }

    override fun onMenu(anchor: View) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, MENU_TOGGLE, 0, if (state == State.OFF) R.string.follow_start else R.string.follow_stop)
            menu.add(0, MENU_CALIBRATE, 1, R.string.follow_recalibrate)
            if (state != State.OFF && camera.count > 1) menu.add(0, MENU_CAMERA, 2, R.string.follow_switch_camera)
            menu.add(0, MENU_SENSITIVITY, 3, R.string.follow_sensitivity)
            menu.add(0, MENU_SHARE, 4, R.string.follow_share_logs)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_TOGGLE -> if (state == State.OFF) begin(calibrate = false) else stop()
                    MENU_CALIBRATE -> begin(calibrate = true)
                    MENU_CAMERA -> switchCamera()
                    MENU_SENSITIVITY -> chooseSensitivity()
                    MENU_SHARE -> shareLogs()
                }
                true
            }
            show()
        }
    }

    override fun onVolumeKey(down: Boolean): Boolean {
        if (state != State.FOLLOWING) return false
        if (turning) return true
        val now = SystemClock.uptimeMillis()
        val lines = host.lines()
        val index = lines.indexOfFirst { it.key == countedKey }
        when {
            down && !host.continuous -> lines.lastOrNull()?.let {
                log?.event(now, "MANUAL_TURN")
                turn(it)
            }

            down -> lines.getOrNull(index + 1)?.let {
                countedKey = it.key
                engine.moved()
                glideHeld = false
                log?.event(now, "FORWARD")
            }

            index > 0 -> {
                countedKey = lines[index - 1].key
                engine.moved()
                log?.event(now, "BACK")
            }
        }
        overlay.mark(lines.firstOrNull { it.key == countedKey }, look(now))
        return true
    }

    override fun onUserScroll(active: Boolean) {
        if (state != State.FOLLOWING || userScrolling == active) return
        userScrolling = active
        val now = SystemClock.uptimeMillis()
        log?.event(now, if (active) "DRAG" else "DRAG_END")
        if (active) {
            handler.removeCallbacks(anchorAfterMove)
            overlay.mark(null, look(now))
        } else {
            // a page dragged and let go where it was: start counting afresh on it
            engine.startPage(fresh = false, settleMs = MOVED_SETTLE_MS)
        }
    }

    override fun onUserMoved() {
        if (state != State.FOLLOWING || turning) return
        handler.removeCallbacks(anchorAfterMove)
        handler.postDelayed(anchorAfterMove, MOVE_SETTLE_MS)
    }

    override fun onDestroy() {
        stop()
        camera.close()
    }

    /** 0 less, 1 normal, 2 more: an index into [FollowEngine.SENSITIVITIES]. */
    private fun sensitivity() = prefs.getInt("sensitivity", 1).coerceIn(0, FollowEngine.SENSITIVITIES.lastIndex)

    private fun chooseSensitivity() {
        val names = arrayOf(
            activity.getString(R.string.follow_sensitivity_less),
            activity.getString(R.string.follow_sensitivity_normal),
            activity.getString(R.string.follow_sensitivity_more),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.follow_sensitivity)
            .setSingleChoiceItems(names, sensitivity()) { dialog, which ->
                prefs.edit { putInt("sensitivity", which) }
                engine.dropScale = FollowEngine.SENSITIVITIES[which]
                log?.event(SystemClock.uptimeMillis(), "SENSITIVITY_${engine.dropScale}")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Starting and stopping

    private fun begin(calibrate: Boolean) {
        calibrateNext = calibrate
        when {
            state == State.OFF -> if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                start()
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }

            calibrate && state != State.FINDING -> beginCalibration()
        }
    }

    private fun start() {
        screen = screenKey()
        calibration = loadCalibration(screen)
        state = State.FINDING
        findingSinceMs = SystemClock.uptimeMillis()
        overlay.show()
        overlay.say(activity.getString(R.string.follow_looking))
        camera.start(prefs.getInt("$screen.camera", 0)) { error ->
            toast(error)
            stop()
        }
        rest.start()
    }

    private fun stop() {
        state = State.OFF
        handler.removeCallbacksAndMessages(null)
        choreographer.removeFrameCallback(glide)
        gliding = false
        turning = false
        resting = false
        userScrolling = false
        glideHeld = false
        camera.setResting(false)
        camera.stop()
        rest.stop()
        overlay.hide()
        log?.close()
        log = null
    }

    /** Folded or unfolded, portrait or landscape: each has its own camera and calibration. */
    private fun screenKey(): String {
        val config = activity.resources.configuration
        val size = if (config.smallestScreenWidthDp >= 600) "open" else "cover"
        val turned = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
        return "$size-$turned"
    }

    private fun switchCamera() {
        camera.select(camera.index + 1)
        prefs.edit { putInt("$screen.camera", camera.index) }
        toast(activity.getString(R.string.follow_camera_n, camera.index + 1, camera.count))
    }

    private fun shareLogs() {
        log?.flush()
        val files = logDir().listFiles { f -> f.name.startsWith("follow-") && f.name.endsWith(".csv") }
            ?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            toast(activity.getString(R.string.follow_no_logs))
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(activity, "${activity.packageName}.provider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.follow_share_logs)))
    }

    private fun logDir() = File(activity.getExternalFilesDir(null) ?: activity.filesDir, "gaze")

    /**
     * The phone was set down or picked up. Following goes on either way (the reader may lean over
     * the phone on a table); at rest the camera only looks for a face about once a second while it
     * sees none.
     */
    private fun onRest(nowResting: Boolean) {
        if (state == State.OFF || nowResting == resting) return
        resting = nowResting
        log?.event(SystemClock.uptimeMillis(), if (nowResting) "REST" else "PICKED_UP")
        camera.setResting(nowResting)
    }

    // Frames

    private fun onFrame(frame: GazeFrame) {
        if (state == State.OFF || activity.isDestroyed) return
        if (screenKey() != screen) {
            // folded, unfolded or turned: another camera and calibration
            screen = screenKey()
            calibration = loadCalibration(screen)
            state = State.FINDING
            findingSinceMs = frame.timeMs
            overlay.hideDot()
            overlay.say(activity.getString(R.string.follow_looking))
            camera.select(prefs.getInt("$screen.camera", 0))
            return
        }
        when (state) {
            State.FINDING -> finding(frame)
            State.CALIBRATING -> calibrating(frame)
            State.FOLLOWING -> following(frame)
            State.OFF -> {}
        }
    }

    private fun finding(frame: GazeFrame) {
        if (frame.face) {
            prefs.edit { putInt("$screen.camera", camera.index) }
            if (calibrateNext || calibration == null) beginCalibration() else beginFollowing()
            return
        }
        if (frame.timeMs - findingSinceMs > FIND_MS && camera.count > 1) {
            findingSinceMs = frame.timeMs
            camera.select(camera.index + 1)
        }
    }

    // Calibration: a dot at the start, then the end, of a line in the middle of the screen

    private fun beginCalibration() {
        state = State.CALIBRATING
        calibrateNext = false
        leftSamples.clear()
        rightSamples.clear()
        openSamples.clear()
        dotIndex = 0
        dotStartMs = SystemClock.uptimeMillis()
        overlay.mark(null, FollowOverlay.Look.FOLLOWING)
        overlay.say(activity.getString(R.string.follow_look_at_dot))
        placeDot()
    }

    private fun placeDot() {
        val lines = host.lines()
        updateColumn(lines)
        val middle = lines.getOrNull(lines.size / 2)
        val root = activity.window.decorView
        val y = middle?.let { (it.top + it.bottom) / 2 } ?: (root.height / 2)
        val x = when {
            middle == null -> if (dotIndex == 0) root.width / 8 else root.width * 7 / 8
            dotIndex == 0 -> columnLeft
            else -> columnRight
        }
        overlay.dotAt(x, y)
    }

    private fun calibrating(frame: GazeFrame) {
        val elapsed = frame.timeMs - dotStartMs
        val samples = if (dotIndex == 0) leftSamples else rightSamples
        if (elapsed >= CALIBRATION_DOT_MS && samples.size >= GazeCalibration.MIN_SAMPLES ||
            elapsed >= CALIBRATION_DOT_MS * 3
        ) {
            if (dotIndex == 0) {
                dotIndex = 1
                dotStartMs = frame.timeMs
                placeDot()
            } else {
                finishCalibration()
            }
            return
        }
        if (frame.face && !frame.iris.isNaN() && elapsed >= CALIBRATION_SETTLE_MS) {
            samples.add(frame.iris)
            openSamples.add(frame.openness)
        }
    }

    private fun finishCalibration() {
        overlay.hideDot()
        val fit = GazeCalibration.fit(leftSamples, rightSamples)
        if (fit == null || fit.snr < MIN_SNR) {
            val why = if (fit == null) {
                activity.getString(R.string.follow_no_steady_face)
            } else {
                activity.getString(R.string.follow_too_noisy, String.format(Locale.US, "%.1f", fit.snr))
            }
            if (calibration == null) {
                toast(activity.getString(R.string.follow_calibration_failed, why))
                stop()
                return
            }
            toast(activity.getString(R.string.follow_calibration_kept, why))
        } else {
            val saved = Calibration(
                fit.calibration.left, fit.calibration.right, fit.snr,
                blinkBelow = if (openSamples.isEmpty()) 0f else median(openSamples) * BLINK_FRACTION,
            )
            calibration = saved
            prefs.edit {
                putFloat("$screen.left", saved.left)
                putFloat("$screen.right", saved.right)
                putFloat("$screen.snr", saved.snr)
                putFloat("$screen.blink", saved.blinkBelow)
            }
            toast(activity.getString(R.string.follow_calibrated, String.format(Locale.US, "%.0f", saved.snr)))
        }
        beginFollowing()
    }

    private fun loadCalibration(key: String): Calibration? {
        if (!prefs.contains("$key.left")) return null
        return Calibration(
            prefs.getFloat("$key.left", 0f), prefs.getFloat("$key.right", 1f),
            prefs.getFloat("$key.snr", 0f), prefs.getFloat("$key.blink", 0f),
        )
    }

    // Following

    private fun beginFollowing() {
        state = State.FOLLOWING
        overlay.say(null)
        val cal = calibration
        log?.close()
        log = FollowLog(
            logDir(),
            listOf(
                "QuickNovel follow, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                "screen=$screen camera=${camera.index} mode=${if (host.continuous) "scroll" else "pages"} " +
                        "iris_left=${cal?.left} iris_right=${cal?.right} iris_snr=${cal?.snr} blink_below=${cal?.blinkBelow} " +
                        "drop_scale=${engine.dropScale}",
            )
        )
        // the eyes come back from the menu or the calibration dot first
        anchor(atReadingHeight = false, settleMs = START_SETTLE_MS)
        if (!gliding) {
            gliding = true
            lastGlideNanos = 0L
            glideCarry = 0f
            choreographer.postFrameCallback(glide)
        }
    }

    /**
     * Counting starts again at the first line on screen, or ([atReadingHeight], scroll mode after
     * a drag) at the line nearest the reading height, where the reader has most likely put theirs.
     */
    private fun anchor(atReadingHeight: Boolean, settleMs: Long = MOVED_SETTLE_MS) {
        val lines = host.lines()
        val line = if (atReadingHeight) {
            val y = readingY()
            lines.minByOrNull { abs(it.top - y) }
        } else {
            lines.firstOrNull()
        }
        countedKey = line?.key
        // the page-turn guard needs a page read from its top (paged mode only)
        engine.startPage(fresh = !atReadingHeight && !host.continuous, settleMs = settleMs)
        log?.event(SystemClock.uptimeMillis(), "ANCHOR")
        overlay.mark(line, look(SystemClock.uptimeMillis()))
    }

    private fun following(frame: GazeFrame) {
        val cal = calibration ?: return
        if (userScrolling) return
        val lines = host.lines()
        if (turning || lines.isEmpty()) {
            overlay.mark(null, look(frame.timeMs))
            return
        }
        updateColumn(lines)
        var index = lines.indexOfFirst { it.key == countedKey }
        if (index < 0) {
            // the counted line left the screen without the follower (a jump it was not told of)
            index = 0
            countedKey = lines[0].key
            engine.startPage(fresh = false, settleMs = MOVED_SETTLE_MS)
            log?.event(frame.timeMs, "ANCHOR")
        }
        val line = lines[index]
        val width = widthOf(line)
        val last = index == lines.lastIndex
        val valid = frame.face && !frame.iris.isNaN() && frame.openness >= cal.blinkBelow
        val x = cal.normalize(frame.iris)
        val event = engine.onFrame(frame.timeMs, valid, x, frame.irisV, FollowEngine.Line(width, last))
        val moved = event == FollowEngine.Event.NEXT_LINE || event == FollowEngine.Event.TURN || event == FollowEngine.Event.HOLD
        log?.frame(
            frame.timeMs, valid, x, frame.irisV, frame.openness, index, lines.size, width, last,
            event?.name, if (moved) engine.lastCause?.name else null,
        )
        when (event) {
            FollowEngine.Event.NEXT_LINE -> {
                countedKey = lines[index + 1].key
                glideHeld = false
            }

            // in scroll mode the last line on screen is the end of what is loaded: wait there
            FollowEngine.Event.TURN -> if (!host.continuous) turn(line)
            FollowEngine.Event.HOLD -> holdUntilMs = frame.timeMs + HOLD_SHOW_MS
            FollowEngine.Event.PAUSE, FollowEngine.Event.RESUME, null -> {}
        }
        // scroll mode marks the line on every display frame (glideStep)
        if (!turning && !host.continuous) overlay.mark(lines.firstOrNull { it.key == countedKey }, look(frame.timeMs))
    }

    /** Where scroll mode keeps the line being read (window y). */
    private fun readingY(): Int {
        val area = host.textArea()
        return area.first + ((area.last - area.first) * READ_AT).toInt()
    }

    /**
     * One display frame of scroll mode: the counted line's top should be at the reading height,
     * minus the share of the line read by now, so the text rises one line per line read and the
     * next line arrives at the reading height as the eyes sweep to it. Closes [GLIDE_MS]' worth of
     * the gap each frame; never scrolls back.
     */
    private fun glideStep(stepMs: Float) {
        if (userScrolling) return
        val lines = host.lines()
        val line = lines.firstOrNull { it.key == countedKey }
        if (line == null || glideHeld) {
            overlay.mark(line, look(SystemClock.uptimeMillis()))
            return
        }
        val height = line.bottom - line.top
        val lineTimeMs = maxOf(MIN_LINE_MS, widthOf(line) * engine.paceMs)
        val progress = minOf(1f, engine.lineMs / lineTimeMs)
        val gap = line.top - (readingY() - progress * height)
        var moved = 0
        if (gap > 0) {
            val dy = gap * minOf(1f, stepMs / GLIDE_MS) + glideCarry
            moved = dy.toInt()
            glideCarry = dy - moved
            if (moved > 0) host.scrollBy(moved)
        } else {
            glideCarry = 0f
        }
        overlay.mark(line.copy(top = line.top - moved, bottom = line.bottom - moved), look(SystemClock.uptimeMillis()))
    }

    /** Paged mode: on to the next page; its first line becomes the counted line. */
    private fun turn(last: ReaderFollow.Line) {
        turning = true
        overlay.mark(null, look(SystemClock.uptimeMillis()))
        host.turnPage(last)
        handler.postDelayed({
            turning = false
            if (state != State.FOLLOWING) return@postDelayed
            val lines = host.lines()
            countedKey = lines.firstOrNull { it.key != last.key }?.key
            engine.startPage(fresh = true)
        }, TURN_SETTLE_MS)
    }

    private fun updateColumn(lines: List<ReaderFollow.Line>) {
        val width = activity.window.decorView.width
        if (width != columnWidthFor) {
            columnWidthFor = width
            columnLeft = Int.MAX_VALUE
            columnRight = Int.MIN_VALUE
        }
        for (line in lines) {
            columnLeft = minOf(columnLeft, line.left)
            columnRight = maxOf(columnRight, line.right)
        }
    }

    /** How far the line reaches across the column (0 to 1): the size of the sweep at its end. */
    private fun widthOf(line: ReaderFollow.Line): Float {
        val column = columnRight - columnLeft
        if (column <= 0) return 1f
        return ((line.right - columnLeft).toFloat() / column).coerceIn(0f, 1f)
    }

    private fun look(nowMs: Long) = when {
        engine.paused && resting -> FollowOverlay.Look.RESTING
        engine.paused -> FollowOverlay.Look.PAUSED
        nowMs < holdUntilMs -> FollowOverlay.Look.HOLD
        else -> FollowOverlay.Look.FOLLOWING
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val PREFS = "gaze_follow"
        const val MENU_TOGGLE = 1
        const val MENU_CALIBRATE = 2
        const val MENU_CAMERA = 3
        const val MENU_SHARE = 4
        const val MENU_SENSITIVITY = 5

        /** Without a face this long, the next front camera is tried. */
        const val FIND_MS = 3000L

        const val CALIBRATION_DOT_MS = 2000L
        const val CALIBRATION_SETTLE_MS = 700L
        const val MIN_SNR = 3f

        /** A frame is a blink when the eyes are less than this share as open as in calibration. */
        const val BLINK_FRACTION = 0.5f

        /** Scroll mode keeps the line being read this far down the text area. */
        const val READ_AT = 0.35f

        /** The glide closes the gap to where the line should be with this time constant. */
        const val GLIDE_MS = 300f
        const val MAX_GLIDE_STEP_MS = 50f

        /** The shortest time a line is expected to take (a line of one word). */
        const val MIN_LINE_MS = 300f

        /** A page turn's animation is over after this. */
        const val TURN_SETTLE_MS = 500L

        /** After a drag or a page turned by hand, the text has settled after this. */
        const val MOVE_SETTLE_MS = 300L

        /** Reading time not counted after starting (eyes back from the menu) or a jump. */
        const val START_SETTLE_MS = 1500L
        const val MOVED_SETTLE_MS = 800L

        /** A held page shows the bar amber this long. */
        const val HOLD_SHOW_MS = 1200L
    }
}
