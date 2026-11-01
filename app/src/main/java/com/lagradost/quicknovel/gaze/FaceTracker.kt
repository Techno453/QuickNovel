package com.lagradost.quicknovel.gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * One analysed camera frame. Without a face only [timeMs] is meaningful. [iris], [irisV],
 * [openness], [headTurn] and [headTilt] come from [FaceGeometry]; [look], [lookV] and [blink] from
 * MediaPipe's eye blendshapes (a second gaze estimate: left minus right look, down minus up look,
 * and the mean eye-blink score). The V signals are the up-down ones.
 */
class GazeFrame(
    val timeMs: Long,
    val face: Boolean,
    val iris: Float = Float.NaN,
    val irisV: Float = Float.NaN,
    val openness: Float = Float.NaN,
    val headTurn: Float = Float.NaN,
    val headTilt: Float = Float.NaN,
    val look: Float = Float.NaN,
    val lookV: Float = Float.NaN,
    val blink: Float = Float.NaN,
)

/**
 * Front camera frames to [GazeFrame]s with MediaPipe Face Landmarker (478 landmarks including
 * the irises, plus blendshapes). Used from the camera analysis thread only.
 */
class FaceTracker(context: Context, model: File) : AutoCloseable {
    private val landmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(map(model)).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .build()
    )

    /** VIDEO mode refuses a timestamp that is not larger than the previous one. */
    private var lastTimeMs = 0L

    fun process(image: ImageProxy): GazeFrame {
        val timeMs = maxOf(SystemClock.uptimeMillis(), lastTimeMs + 1)
        lastTimeMs = timeMs
        val bitmap = upright(image.toBitmap(), image.imageInfo.rotationDegrees)
        val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), timeMs)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        bitmap.recycle()

        val landmarks = result.faceLandmarks().firstOrNull()
        if (landmarks == null || landmarks.size < FaceGeometry.LANDMARK_COUNT) {
            return GazeFrame(timeMs, face = false)
        }
        val eyes = FaceGeometry.measure { i ->
            FaceGeometry.Point(landmarks[i].x() * width, landmarks[i].y() * height)
        } ?: return GazeFrame(timeMs, face = false)

        val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
            ?.associate { it.categoryName() to it.score() }.orEmpty()
        fun score(name: String) = shapes[name] ?: Float.NaN
        // Looking to the subject's left turns the left eye out and the right eye in
        val look = (score("eyeLookOutLeft") + score("eyeLookInRight") -
                score("eyeLookInLeft") - score("eyeLookOutRight")) / 2
        val lookV = (score("eyeLookDownLeft") + score("eyeLookDownRight") -
                score("eyeLookUpLeft") - score("eyeLookUpRight")) / 2
        val blink = (score("eyeBlinkLeft") + score("eyeBlinkRight")) / 2

        return GazeFrame(
            timeMs, true,
            iris = eyes.ratio, irisV = eyes.vertical, openness = eyes.openness,
            headTurn = eyes.headTurn, headTilt = eyes.headTilt,
            look = look, lookV = lookV, blink = blink,
        )
    }

    override fun close() = landmarker.close()

    private fun upright(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        bitmap.recycle()
        return rotated
    }

    private fun map(file: File) = FileInputStream(file).channel.use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
    }
}
