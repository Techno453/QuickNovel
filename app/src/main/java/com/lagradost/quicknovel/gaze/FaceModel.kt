package com.lagradost.quicknovel.gaze

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** MediaPipe's face landmark model, downloaded once into the app's files (3.7 MB). */
object FaceModel {
    private const val URL_FLOAT16 =
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

    /** A tracker on the model, downloading it first if needed. */
    suspend fun tracker(context: Context): FaceTracker = withContext(Dispatchers.IO) {
        val model = File(context.filesDir, "gaze/face_landmarker.task")
        if (!model.exists()) download(URL_FLOAT16, model)
        FaceTracker(context.applicationContext, model)
    }

    private fun download(url: String, to: File) {
        to.parentFile?.mkdirs()
        val part = File(to.path + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for the face model")
            }
            connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
        if (!part.renameTo(to)) throw IOException("Could not save the face model")
    }
}
