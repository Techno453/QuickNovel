package com.lagradost.quicknovel.gaze

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lagradost.quicknovel.R
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The follower's front camera: frames to [GazeFrame]s on a background thread, delivered to
 * [onFrame] on the main thread. No preview, and kept cheap: [TARGET_FPS] frames a second (sweeps
 * were found as well at 10 or 15 as at 30, docs/gaze-follow.md), and while no face has been seen
 * for [IDLE_AFTER_MS] only every [IDLE_EVERY]th frame is analysed ([RESTING_EVERY]th while the
 * phone lies at rest). Bound to the activity's lifecycle, so it stops while the reader is in the
 * background.
 */
class FollowCamera(
    private val activity: AppCompatActivity,
    private val onFrame: (GazeFrame) -> Unit,
) {
    private val analysisThread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var tracker: FaceTracker? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var onError: (String) -> Unit = {}

    /** Bound, or to be bound once the model and the camera are ready. */
    private var wanted = false

    var index = 0
        private set
    val count: Int get() = fronts().size

    // analysis thread
    @Volatile
    private var lastFaceMs = 0L

    @Volatile
    private var analyseEvery = 1

    @Volatile
    private var idleEvery = IDLE_EVERY
    private var frameCount = 0

    /**
     * The phone lies at rest: while no face is seen, analyse only every [RESTING_EVERY]th frame
     * (about once a second), enough to notice the reader leaning over it.
     */
    fun setResting(resting: Boolean) {
        idleEvery = if (resting) RESTING_EVERY else IDLE_EVERY
    }

    /** Loads the face model (downloading it the first time) and binds front camera [index]. */
    fun start(index: Int, onError: (String) -> Unit) {
        this.index = index
        this.onError = onError
        wanted = true
        activity.lifecycleScope.launch {
            if (tracker == null) {
                val loaded = runCatching { FaceModel.tracker(activity.applicationContext) }
                tracker = loaded.getOrElse {
                    Log.w(TAG, "Face model failed", it)
                    onError(activity.getString(R.string.follow_model_failed, it.toString()))
                    return@launch
                }
            }
            if (provider != null) {
                if (wanted) bind()
                return@launch
            }
            val future = ProcessCameraProvider.getInstance(activity)
            future.addListener({
                provider = try {
                    future.get()
                } catch (e: Exception) {
                    onError(activity.getString(R.string.follow_camera_failed, e.message))
                    return@addListener
                }
                if (wanted) bind()
            }, ContextCompat.getMainExecutor(activity))
        }
    }

    /** Moves to front camera [index] (wrapping around). */
    fun select(index: Int) {
        val count = count
        this.index = if (count == 0) 0 else index.mod(count)
        if (wanted) bind()
    }

    /** Stops the camera; [resume] starts it again. */
    fun stop() {
        wanted = false
        analysis?.let { provider?.unbind(it) }
        analysis = null
    }

    fun resume() {
        wanted = true
        if (tracker != null) bind()
    }

    fun close() {
        stop()
        analysisThread.execute {
            tracker?.close()
            tracker = null
        }
        analysisThread.shutdown()
    }

    private fun fronts(): List<CameraInfo> =
        provider?.availableCameraInfos?.filter { it.lensFacing == CameraSelector.LENS_FACING_FRONT }.orEmpty()

    private fun bind() {
        val provider = provider ?: return
        if (activity.isDestroyed) return
        val fronts = fronts()
        if (fronts.isEmpty()) {
            onError(activity.getString(R.string.follow_no_front_camera))
            return
        }
        index = index.mod(fronts.size)
        val info = fronts[index]
        analysis?.let { provider.unbind(it) }
        val useCase = buildAnalysis(info)
        analysis = useCase
        lastFaceMs = SystemClock.uptimeMillis()
        try {
            provider.bindToLifecycle(activity, info.cameraSelector, useCase)
        } catch (e: Exception) {
            onError(activity.getString(R.string.follow_camera_failed, e.message))
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildAnalysis(info: CameraInfo): ImageAnalysis {
        // The landmark model works on a ~256 px crop of the face; 640x480 is plenty
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        // A slower sensor saves power too; without such a mode, analyse every other frame
        val range = try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper == TARGET_FPS }?.maxByOrNull { it.lower }
        } catch (e: Exception) {
            null
        }
        if (range != null) {
            Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }
        analyseEvery = if (range != null) 1 else 2
        return builder.build().also { it.setAnalyzer(analysisThread, ::analyse) }
    }

    private fun analyse(image: ImageProxy) {
        try {
            val idle = SystemClock.uptimeMillis() - lastFaceMs > IDLE_AFTER_MS
            if (frameCount++ % (if (idle) idleEvery else analyseEvery) != 0) return
            val frame = tracker?.process(image) ?: return
            if (frame.face) lastFaceMs = frame.timeMs
            main.post { onFrame(frame) }
        } catch (e: Exception) {
            Log.w(TAG, "Frame failed", e)
        } finally {
            image.close()
        }
    }

    companion object {
        private const val TAG = "FollowCamera"
        const val TARGET_FPS = 15
        const val IDLE_AFTER_MS = 3000L
        const val IDLE_EVERY = 4
        const val RESTING_EVERY = 15
    }
}
