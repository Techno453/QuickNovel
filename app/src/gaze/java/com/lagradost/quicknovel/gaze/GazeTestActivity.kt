package com.lagradost.quicknovel.gaze

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.lagradost.quicknovel.databinding.GazeTestBinding
import com.lagradost.quicknovel.gaze.LineSweepDetector.Event
import com.lagradost.quicknovel.gaze.LineSweepDetector.Mark
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Feasibility test for eye-tracked line following (docs/gaze-test.md): can the front camera see
 * the return sweep at the end of every line, and which line the eyes are on? Calibrate (four dots:
 * the left and right end of a line, the first and the last line), press Read and read the passage
 * to its last line, pressing volume up on starting each new line, then Done or volume down. The
 * screen scores the line ends [LineSweepDetector] found and the up-down signal's line estimate
 * against those presses, plots the trial and writes a CSV of every frame.
 *
 * Two signals run side by side: the iris position between the eye corners and lids
 * ([FaceGeometry]) and MediaPipe's eye-look blendshapes.
 */
class GazeTestActivity : AppCompatActivity() {
    private enum class Phase {
        IDLE, CALIBRATING_LEFT, CALIBRATING_RIGHT, CALIBRATING_TOP, CALIBRATING_BOTTOM, READING, DOTS
    }

    /** One frame of the up-down dot test: which dot was shown, and whether the eyes had settled. */
    private class DotFrame(val frame: GazeFrame, val dot: Int, val line: Int, val settled: Boolean, val valid: Boolean)

    /**
     * One frame of a trial: the calibrated signals (null when not calibrated; the V ones are 0 on
     * the first line and 1 on the last) and the events of the two sweep detectors.
     */
    private class Row(
        val frame: GazeFrame,
        val valid: Boolean,
        val iris: Float?,
        val look: Float?,
        val irisV: Float?,
        val lookV: Float?,
        val irisEvent: Event?,
        val lookEvent: Event?,
    )

    private lateinit var binding: GazeTestBinding
    private val handler = Handler(Looper.getMainLooper())
    private val analysisThread = Executors.newSingleThreadExecutor()

    /** Set once the face model is loaded; used and closed on [analysisThread]. */
    @Volatile
    private var tracker: FaceTracker? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var frontCameraIndex = 0
    private var frontCameraCount = 0

    private var phase = Phase.IDLE
    private var phaseStartMs = 0L

    private val leftIris = ArrayList<Float>()
    private val rightIris = ArrayList<Float>()
    private val leftLook = ArrayList<Float>()
    private val rightLook = ArrayList<Float>()
    private val topIrisV = ArrayList<Float>()
    private val bottomIrisV = ArrayList<Float>()
    private val topLookV = ArrayList<Float>()
    private val bottomLookV = ArrayList<Float>()
    private val calibrationOpenness = ArrayList<Float>()
    private var irisFit: GazeCalibration.Fit? = null
    private var lookFit: GazeCalibration.Fit? = null
    private var irisVFit: GazeCalibration.Fit? = null
    private var lookVFit: GazeCalibration.Fit? = null

    /** Frames with the eyes less open than this are blinks; 0 until calibrated. */
    private var blinkBelow = 0f

    private val irisDetector = LineSweepDetector()
    private val lookDetector = LineSweepDetector()
    private val trial = ArrayList<Row>()
    private var trialLines = 0
    private var trialCount = 0

    /** Volume-up presses during a trial: the reader's own "I'm on the next line now". */
    private val lineMarks = ArrayList<Long>()

    /** Up-down dot test (long-press Calibrate): the lines the dot visits, in order. */
    private var dotTargets = emptyList<Int>()
    private var dotIndex = 0
    private var dotStartMs = 0L
    private val dotFrames = ArrayList<DotFrame>()

    /** The plot holds the last trial until the next calibration or trial, or a tap on it. */
    private var showingTrial = false

    private var passageIndex = 0
    private var summary = "Calibrate, then Read. While reading press volume up each time you start a new " +
            "line; volume down (or Done) at the end. Long-press Calibrate for the up-down dot test."

    private var lastFrame: GazeFrame? = null
    private var fps = 0
    private var framesThisSecond = 0
    private var fpsSecond = 0L
    private var lastStatusMs = 0L

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else showStatus("No camera permission: the test needs the front camera.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Toast.makeText(this, "The gaze test needs Android 7 or newer", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        binding = GazeTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.gazeDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFB300.toInt())
        }
        // TextureView, so the preview can be hidden with alpha without CameraX losing its surface
        binding.gazePreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.gazePassage.apply {
            // Greedy line breaking: cutting the passage after a line keeps the lines before it
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
        binding.gazeCalibrate.setOnClickListener { calibrate() }
        binding.gazeCalibrate.setOnLongClickListener {
            dotTest()
            true
        }
        binding.gazeRead.setOnClickListener {
            if (phase == Phase.READING) finishReading() else startReading()
        }
        binding.gazeRead.isEnabled = false
        binding.gazeNext.setOnClickListener {
            passageIndex = (passageIndex + 1) % GazePassages.ALL.size
            showPassage(GazePassages.ALL[passageIndex])
        }
        binding.gazePaste.setOnClickListener { paste() }
        binding.gazeShare.setOnClickListener { share() }
        binding.gazePlot.setOnClickListener { showingTrial = false }
        binding.gazePreview.setOnClickListener { nextCamera() }

        showPassage(GazePassages.ALL[passageIndex])
        showStatus()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            start()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        analysisThread.execute {
            tracker?.close()
            tracker = null
        }
        analysisThread.shutdown()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (phase == Phase.READING) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event?.repeatCount == 0) lineMarks.add(SystemClock.uptimeMillis())
                    return true
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    finishReading()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun start() {
        startCamera()
        lifecycleScope.launch {
            showStatus()
            val loaded = runCatching { FaceModel.tracker(applicationContext) }
            loaded.onSuccess { tracker = it }
                .onFailure {
                    Log.w(TAG, "Face model failed", it)
                    showStatus("Face model failed: $it")
                }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.gazePreview.surfaceProvider)
            }
            // The landmark model works on a ~256 px crop of the face, so a larger frame only
            // costs time: at reading distance the face already fills a 640 px frame enough.
            analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis?.setAnalyzer(analysisThread) { image ->
                val frame = try {
                    tracker?.process(image)
                } catch (e: Exception) {
                    Log.w(TAG, "Frame failed", e)
                    null
                } finally {
                    image.close()
                }
                if (frame != null) runOnUiThread { onFrame(frame) }
            }
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                showStatus("Front camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Binds front camera [frontCameraIndex]. A foldable has two: the cover camera, which faces
     * away from the reader when the phone is open, and the inner one; the first one listed is not
     * always the one facing the reader, so a tap on the preview moves to the next.
     */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = preview ?: return
        val analysis = analysis ?: return
        val fronts = provider.availableCameraInfos.filter {
            it.lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        frontCameraCount = fronts.size
        if (fronts.isEmpty()) {
            showStatus("No front camera found")
            return
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this, fronts[frontCameraIndex % fronts.size].cameraSelector, preview, analysis
            )
        } catch (e: Exception) {
            showStatus("Front camera ${frontCameraIndex % fronts.size + 1} failed: ${e.message}")
        }
    }

    private fun nextCamera() {
        if (phase != Phase.IDLE || frontCameraCount < 2) return
        frontCameraIndex = (frontCameraIndex + 1) % frontCameraCount
        lastFrame = null
        bindCamera()
        toast("Front camera ${frontCameraIndex + 1} of $frontCameraCount")
    }

    private fun onFrame(frame: GazeFrame) {
        if (isDestroyed) return
        lastFrame = frame
        val second = frame.timeMs / 1000
        if (second != fpsSecond) {
            fps = framesThisSecond
            framesThisSecond = 0
            fpsSecond = second
        }
        framesThisSecond++

        val valid = frame.face && !frame.iris.isNaN() && frame.openness >= blinkBelow
        val iris = irisFit?.calibration?.normalize(frame.iris)
        val look = lookFit?.calibration?.normalize(frame.look)?.takeIf { !it.isNaN() }
        val irisV = irisVFit?.calibration?.normalize(frame.irisV)?.takeIf { !it.isNaN() }
        val lookV = lookVFit?.calibration?.normalize(frame.lookV)?.takeIf { !it.isNaN() }
        when (phase) {
            Phase.IDLE -> if (!showingTrial) {
                binding.gazePlot.addLive(
                    frame.timeMs,
                    if (valid) iris ?: frame.iris else Float.NaN,
                    calibrated = iris != null,
                )
            }

            Phase.CALIBRATING_LEFT, Phase.CALIBRATING_RIGHT,
            Phase.CALIBRATING_TOP, Phase.CALIBRATING_BOTTOM -> {
                if (valid && frame.timeMs - phaseStartMs >= CALIBRATION_SETTLE_MS) {
                    val (irisSamples, lookSamples, irisValue, lookValue) = when (phase) {
                        Phase.CALIBRATING_LEFT -> CalibrationTarget(leftIris, leftLook, frame.iris, frame.look)
                        Phase.CALIBRATING_RIGHT -> CalibrationTarget(rightIris, rightLook, frame.iris, frame.look)
                        Phase.CALIBRATING_TOP -> CalibrationTarget(topIrisV, topLookV, frame.irisV, frame.lookV)
                        else -> CalibrationTarget(bottomIrisV, bottomLookV, frame.irisV, frame.lookV)
                    }
                    if (!irisValue.isNaN()) irisSamples.add(irisValue)
                    if (!lookValue.isNaN()) lookSamples.add(lookValue)
                    calibrationOpenness.add(frame.openness)
                }
            }

            Phase.READING -> {
                val irisEvent = if (valid && iris != null) irisDetector.add(frame.timeMs, iris) else null
                val lookEvent = if (valid && look != null) lookDetector.add(frame.timeMs, look) else null
                trial.add(Row(frame, valid, iris, look, irisV, lookV, irisEvent, lookEvent))
            }

            Phase.DOTS -> dotFrames.add(
                DotFrame(
                    frame, dotIndex, dotTargets[dotIndex],
                    settled = frame.timeMs - dotStartMs >= DOT_SETTLE_MS,
                    valid = valid,
                )
            )
        }
        if (phase != Phase.READING && frame.timeMs - lastStatusMs >= STATUS_EVERY_MS) {
            lastStatusMs = frame.timeMs
            showStatus()
        }
    }

    // Calibration

    /** Where a calibration frame's samples go: iris and look lists and the values for them. */
    private data class CalibrationTarget(
        val irisSamples: MutableList<Float>,
        val lookSamples: MutableList<Float>,
        val irisValue: Float,
        val lookValue: Float,
    )

    private val calibrationDots = listOf(
        Phase.CALIBRATING_LEFT, Phase.CALIBRATING_RIGHT, Phase.CALIBRATING_TOP, Phase.CALIBRATING_BOTTOM,
    )

    private fun calibrate() {
        if (tracker == null) {
            toast("Wait for the face model to load")
            return
        }
        for (list in listOf(
            leftIris, rightIris, leftLook, rightLook,
            topIrisV, bottomIrisV, topLookV, bottomLookV, calibrationOpenness,
        )) list.clear()
        blinkBelow = 0f
        showingTrial = false
        setChrome(status = true, controls = false)
        binding.gazePassage.alpha = 0.3f
        calibrationStep(0)
    }

    private fun calibrationStep(index: Int) {
        if (index == calibrationDots.size) {
            finishCalibration()
            return
        }
        enterCalibration(calibrationDots[index])
        handler.postDelayed({ calibrationStep(index + 1) }, CALIBRATION_DOT_MS)
    }

    /**
     * Left and right: the ends of the middle line of the passage (the line start and end of the
     * horizontal signal). Top and bottom: the middle of the first and of the last line (line 0
     * and the last line of the up-down signal).
     */
    private fun enterCalibration(dotPhase: Phase) {
        phase = dotPhase
        phaseStartMs = SystemClock.uptimeMillis()
        val lines = binding.gazePassage.layout?.lineCount ?: return
        val view = binding.gazePassage
        when (dotPhase) {
            Phase.CALIBRATING_LEFT -> placeDot((lines - 1) / 2, view.totalPaddingLeft.toFloat())
            Phase.CALIBRATING_RIGHT -> placeDot((lines - 1) / 2, (view.width - view.totalPaddingRight).toFloat())
            Phase.CALIBRATING_TOP -> placeDot(0)
            else -> placeDot(lines - 1)
        }
        showStatus()
    }

    /** The dot on the middle of passage line [line], or at [x] along it. */
    private fun placeDot(line: Int, x: Float? = null) {
        val view = binding.gazePassage
        val layout = view.layout ?: return
        val y = view.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        val dotX = x ?: ((view.totalPaddingLeft + view.width - view.totalPaddingRight) / 2f)
        binding.gazeDot.translationX = dotX - binding.gazeDot.width / 2f
        binding.gazeDot.translationY = y - binding.gazeDot.height / 2f
        binding.gazeDot.visibility = View.VISIBLE
    }

    // Up-down dot test

    /**
     * The best case for the up-down signals: no reading, no sideways movement, the eyes resting
     * on a dot in the middle of a known line (every second line, each twice, in random order).
     * Shows how many lines apart two looks have to be before a signal tells them apart.
     */
    private fun dotTest() {
        if (tracker == null) {
            toast("Wait for the face model to load")
            return
        }
        val lines = binding.gazePassage.layout?.lineCount ?: 0
        if (lines < 4) {
            toast("The passage needs at least 4 lines")
            return
        }
        dotTargets = DotResolution.targets(lines, random = Random(SystemClock.uptimeMillis()))
        dotFrames.clear()
        showingTrial = false
        setChrome(status = true, controls = false)
        binding.gazePassage.alpha = 0.3f
        phase = Phase.DOTS
        dotStep(0)
    }

    private fun dotStep(index: Int) {
        if (index == dotTargets.size) {
            finishDots()
            return
        }
        dotIndex = index
        dotStartMs = SystemClock.uptimeMillis()
        placeDot(dotTargets[index])
        showStatus()
        handler.postDelayed({ dotStep(index + 1) }, DOT_MS)
    }

    private fun finishDots() {
        phase = Phase.IDLE
        binding.gazeDot.visibility = View.INVISIBLE
        binding.gazePassage.alpha = 1f
        setChrome(status = true, controls = true)
        val lines = (dotTargets.maxOrNull() ?: 0) + 1

        fun fit(value: (GazeFrame) -> Float): DotResolution.Result? =
            DotResolution.fit(
                dotFrames.filter { it.settled && it.valid && !value(it.frame).isNaN() }
                    .groupBy { it.dot }
                    .map { (_, frames) -> frames.first().line to frames.map { value(it.frame) } }
            )

        val iris = fit { it.irisV }
        val look = fit { it.lookV }
        val lid = fit { it.openness }
        val tilt = fit { it.headTilt }
        val usable = 100 * dotFrames.count { it.valid } / maxOf(1, dotFrames.size)
        val saved = try {
            writeDotCsv(lines, listOf("iris_v" to iris, "look_v" to look, "openness" to lid, "head_tilt" to tilt))
            true
        } catch (e: IOException) {
            Log.w(TAG, "Dot CSV not written", e)
            false
        }

        // Plot: the iris estimate of the line against the dot's line (orange), both over the page
        iris?.let { r ->
            val intercept = dotFrames.filter { it.settled && it.valid }.let { settled ->
                settled.map { it.frame.irisV - r.slope * it.line }.filter { !it.isNaN() }
                    .takeIf { it.isNotEmpty() }?.let { median(it.map(Double::toFloat)) }
            } ?: return@let
            showingTrial = true
            binding.gazePlot.showTrial(
                dotFrames.map { it.frame.timeMs },
                dotFrames.map { d ->
                    if (!d.valid) Float.NaN else ((d.frame.irisV - intercept) / r.slope / (lines - 1)).toFloat()
                },
                emptyList(),
                emptyList(),
                reference = dotFrames.map { it.line.toFloat() / (lines - 1) },
            )
        }

        fun describe(r: DotResolution.Result?) =
            r?.let { "±${fmt(it.perDot.toFloat(), 2)} per dot, ±${fmt(it.perFrame.toFloat(), 1)} per frame" } ?: "-"
        showStatus(
            "Up-down dots: ${dotTargets.size} dots on lines 0-${lines - 1}, usable $usable%" +
                    "${if (saved) "" else ", CSV not saved"}\n" +
                    "iris: ${describe(iris)}\nlook: ${describe(look)}\n" +
                    "lid ${lid?.let { "±" + fmt(it.perDot.toFloat(), 2) } ?: "-"}, " +
                    "tilt ${tilt?.let { "±" + fmt(it.perDot.toFloat(), 2) } ?: "-"} per dot; " +
                    "±0.3 or less = single lines"
        )
    }

    private fun writeDotCsv(lines: Int, results: List<Pair<String, DotResolution.Result?>>) {
        val dir = trialDir()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir, "updown-$stamp.csv").bufferedWriter().use { out ->
            out.appendLine("# QuickNovel gaze up-down dot test, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            out.appendLine("# lines=$lines dot_ms=$DOT_MS settle_ms=$DOT_SETTLE_MS targets=${dotTargets.joinToString(",")}")
            out.appendLine(
                "# " + results.joinToString(" ") { (name, r) ->
                    "${name}_per_dot=${num(r?.perDot?.toFloat())} ${name}_per_frame=${num(r?.perFrame?.toFloat())} " +
                            "${name}_slope=${r?.slope ?: ""}"
                }
            )
            out.appendLine("t_ms,dot,line,settled,face,valid,iris_raw,iris_v_raw,look_raw,look_v_raw,openness,blink,head_turn,head_tilt")
            for (d in dotFrames) {
                val f = d.frame
                out.appendLine(
                    listOf(
                        f.timeMs, d.dot, d.line, if (d.settled) 1 else 0, if (f.face) 1 else 0, if (d.valid) 1 else 0,
                        num(f.iris), num(f.irisV), num(f.look), num(f.lookV),
                        num(f.openness), num(f.blink), num(f.headTurn), num(f.headTilt),
                    ).joinToString(",")
                )
            }
        }
    }

    private fun finishCalibration() {
        phase = Phase.IDLE
        binding.gazeDot.visibility = View.INVISIBLE
        binding.gazePassage.alpha = 1f
        setChrome(status = true, controls = true)
        irisFit = GazeCalibration.fit(leftIris, rightIris)
        lookFit = GazeCalibration.fit(leftLook, rightLook)
        irisVFit = GazeCalibration.fit(topIrisV, bottomIrisV)
        lookVFit = GazeCalibration.fit(topLookV, bottomLookV)
        blinkBelow = if (calibrationOpenness.isEmpty()) 0f else median(calibrationOpenness) * BLINK_FRACTION
        binding.gazeRead.isEnabled = irisFit != null || lookFit != null
        showStatus(
            if (irisFit == null && lookFit == null) {
                "Calibration failed: no steady face. Hold the phone as you do when reading and try again."
            } else {
                "Calibrated, SNR (under 3 is too noisy):\n" +
                        "iris ${fmt(irisFit?.snr, 1)} left-right, ${fmt(irisVFit?.snr, 1)} up-down; " +
                        "look ${fmt(lookFit?.snr, 1)}, ${fmt(lookVFit?.snr, 1)}\nPress Read."
            }
        )
    }

    // Trials

    private fun startReading() {
        trial.clear()
        lineMarks.clear()
        irisDetector.reset()
        lookDetector.reset()
        trialLines = binding.gazePassage.layout?.lineCount ?: 0
        showingTrial = false
        setChrome(status = false, controls = false, read = true)
        binding.gazeRead.text = "Done"
        phase = Phase.READING
        phaseStartMs = SystemClock.uptimeMillis()
    }

    private fun finishReading() {
        val endMs = SystemClock.uptimeMillis()
        phase = Phase.IDLE
        trialCount++
        binding.gazeRead.text = "Read"
        setChrome(status = true, controls = true)

        val start = phaseStartMs
        val irisMarks = trial.mapNotNull { r -> r.irisEvent?.let { Mark(r.frame.timeMs, it) } }
        val lookMarks = trial.mapNotNull { r -> r.lookEvent?.let { Mark(r.frame.timeMs, it) } }
        val usable = 100 * trial.count { it.valid } / maxOf(1, trial.size)
        val saved = try {
            writeCsv(start, endMs)
            true
        } catch (e: IOException) {
            Log.w(TAG, "CSV not written", e)
            false
        }

        val useIris = irisFit != null
        showingTrial = true
        binding.gazePlot.showTrial(
            trial.map { it.frame.timeMs },
            trial.map { r -> (if (useIris) r.iris else r.look)?.takeIf { r.valid } ?: Float.NaN },
            if (useIris) irisMarks else lookMarks,
            lineMarks.toList(),
        )
        val times = trial.map { it.frame.timeMs }
        fun lineError(fit: GazeCalibration.Fit?, values: List<Float?>) =
            if (fit == null || lineMarks.isEmpty()) "-" else {
                TrialStats.lineError(times, values, lineMarks, trialLines, start, endMs)
                    ?.let { "±" + fmt(it, 1) } ?: "-"
            }
        showStatus(
            "Trial $trialCount: $trialLines lines, ${lineMarks.size} marks, ${(endMs - start) / 1000} s, " +
                    "usable $usable%${if (saved) "" else ", CSV not saved"}\n" +
                    "iris: ${describe(irisMarks, irisFit, start, endMs)}\n" +
                    "look: ${describe(lookMarks, lookFit, start, endMs)}\n" +
                    "up-down line error: iris ${lineError(irisVFit, trial.map { r -> r.irisV.takeIf { r.valid } })}, " +
                    "look ${lineError(lookVFit, trial.map { r -> r.lookV.takeIf { r.valid } })} lines"
        )
    }

    /** Sweeps, scored against the volume-up marks when there are any. */
    private fun describe(marks: List<Mark>, fit: GazeCalibration.Fit?, startMs: Long, endMs: Long): String {
        if (fit == null) return "not calibrated"
        val stats = TrialStats.of(marks, startMs, endMs)
        val line = "line ${stats.medianLineMs?.let { fmt(it / 1000f, 1) + " s" } ?: "-"}"
        if (lineMarks.isEmpty()) return "${stats.sweeps} sweeps, $line (no marks to score)"
        val score = TrialStats.score(marks, lineMarks, startMs, endMs)
        return "${stats.sweeps} sweeps: ${score.hits}/${score.marks} marks hit, ${score.extra} extra, $line"
    }

    private fun writeCsv(startMs: Long, endMs: Long): File {
        val dir = trialDir()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "trial-$stamp.csv")
        file.bufferedWriter().use { out ->
            out.appendLine("# QuickNovel gaze test, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            out.appendLine(
                "# lines=$trialLines expected_sweeps=${trialLines - 1} start_ms=$startMs end_ms=$endMs " +
                        "settle_ms=${TrialStats.SETTLE_MS} tail_ms=${TrialStats.TAIL_MS}"
            )
            out.appendLine(
                "# iris_left=${num(irisFit?.calibration?.left)} iris_right=${num(irisFit?.calibration?.right)} " +
                        "iris_snr=${num(irisFit?.snr)} look_left=${num(lookFit?.calibration?.left)} " +
                        "look_right=${num(lookFit?.calibration?.right)} look_snr=${num(lookFit?.snr)} " +
                        "blink_below=${num(blinkBelow)}"
            )
            out.appendLine(
                "# iris_v_top=${num(irisVFit?.calibration?.left)} iris_v_bottom=${num(irisVFit?.calibration?.right)} " +
                        "iris_v_snr=${num(irisVFit?.snr)} look_v_top=${num(lookVFit?.calibration?.left)} " +
                        "look_v_bottom=${num(lookVFit?.calibration?.right)} look_v_snr=${num(lookVFit?.snr)}"
            )
            out.appendLine("# marks_ms=${lineMarks.joinToString(",")}")
            out.appendLine(
                "t_ms,face,valid,iris_raw,iris_norm,look_raw,look_norm,iris_v_raw,iris_v_norm,look_v_raw,look_v_norm," +
                        "openness,blink,head_turn,head_tilt,iris_event,look_event"
            )
            for (r in trial) {
                val f = r.frame
                out.appendLine(
                    listOf(
                        f.timeMs, if (f.face) 1 else 0, if (r.valid) 1 else 0,
                        num(f.iris), num(r.iris), num(f.look), num(r.look),
                        num(f.irisV), num(r.irisV), num(f.lookV), num(r.lookV),
                        num(f.openness), num(f.blink), num(f.headTurn), num(f.headTilt),
                        r.irisEvent?.name.orEmpty(), r.lookEvent?.name.orEmpty(),
                    ).joinToString(",")
                )
            }
        }
        return file
    }

    private fun trialDir() = File(getExternalFilesDir(null) ?: filesDir, "gaze")

    private fun share() {
        val files = trialDir().listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            toast("No trials yet")
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.provider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ${files.size} gaze trials"))
    }

    // Passage

    /** Shows [text] cut after the last line that fits, so every line of it can be read. */
    private fun showPassage(text: String) {
        val view = binding.gazePassage
        view.text = text
        view.post {
            val layout = view.layout ?: return@post
            val room = view.height - view.totalPaddingTop - view.totalPaddingBottom
            var last = layout.getLineForVertical(room)
            if (layout.getLineBottom(last) > room) last--
            if (last in 0 until layout.lineCount - 1) {
                view.text = text.substring(0, layout.getLineEnd(last)).trimEnd()
            }
        }
    }

    /** The clipboard as one paragraph (see [GazePassages] for why). */
    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            .orEmpty().replace(WHITESPACE, " ").trim()
        if (text.length < MIN_PASTE) {
            toast("Copy at least a screenful of text first")
            return
        }
        showPassage(text)
    }

    // Chrome and status

    /** Hidden parts keep their size (see gaze_test.xml) so the passage never reflows. */
    private fun setChrome(status: Boolean, controls: Boolean, read: Boolean = controls) {
        binding.gazeStatus.visibility = if (status) View.VISIBLE else View.INVISIBLE
        binding.gazePlot.visibility = if (status) View.VISIBLE else View.INVISIBLE
        binding.gazePreview.alpha = if (status) 1f else 0f
        for (button in listOf(binding.gazeCalibrate, binding.gazeNext, binding.gazePaste, binding.gazeShare)) {
            button.visibility = if (controls) View.VISIBLE else View.INVISIBLE
        }
        binding.gazeRead.visibility = if (read) View.VISIBLE else View.INVISIBLE
    }

    private fun showStatus(newSummary: String? = null) {
        if (newSummary != null) summary = newSummary
        val f = lastFrame
        val camera = if (frontCameraCount > 1) "cam ${frontCameraIndex + 1}/$frontCameraCount " else ""
        val live = when {
            tracker == null -> "loading the face model (3.7 MB, downloaded once)…"
            f == null -> "${camera}waiting for the camera"
            !f.face && frontCameraCount > 1 -> "$camera$fps fps · no face (tap the preview for another camera)"
            !f.face -> "$fps fps · no face"
            else -> "$camera$fps fps iris ${fmt(f.iris)} look ${fmt(f.look)} open ${fmt(f.openness, 2)}"
        }
        val text = when (phase) {
            Phase.CALIBRATING_LEFT -> "Look at the orange dot on the LEFT and keep looking (1/4)"
            Phase.CALIBRATING_RIGHT -> "Now the dot on the RIGHT (2/4)"
            Phase.CALIBRATING_TOP -> "Now the dot on the FIRST line (3/4)"
            Phase.CALIBRATING_BOTTOM -> "Now the dot on the LAST line (4/4)"
            Phase.DOTS -> "Up-down test: keep looking at the orange dot, don't read (${dotIndex + 1}/${dotTargets.size})"
            else -> summary
        }
        binding.gazeStatus.text = "$live\n$text"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun fmt(value: Float?, decimals: Int = 3) =
        if (value == null || value.isNaN()) "-" else String.format(Locale.US, "%.${decimals}f", value)

    /** CSV number: empty when missing. */
    private fun num(value: Float?) =
        if (value == null || value.isNaN()) "" else String.format(Locale.US, "%.5f", value)

    companion object {
        private const val TAG = "GazeTest"

        /** How long each calibration dot is shown, and how much of it the eyes get to land. */
        private const val CALIBRATION_DOT_MS = 2000L
        private const val CALIBRATION_SETTLE_MS = 700L

        /** Each dot of the up-down test, and the part of it left out while the eyes land. */
        private const val DOT_MS = 1500L
        private const val DOT_SETTLE_MS = 600L

        /** A frame is a blink when the eyes are less than this share as open as in calibration. */
        private const val BLINK_FRACTION = 0.5f
        private const val STATUS_EVERY_MS = 250L
        private const val MIN_PASTE = 300
        private val WHITESPACE = Regex("\\s+")
    }
}
