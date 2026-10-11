package com.lagradost.quicknovel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE_PDF
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.ui.download.DownloadViewModel
import com.lagradost.quicknovel.util.Apis
import java.lang.ref.WeakReference

// This is needed to fix downloads, as newer android versions pause network connections in the background
class DownloadFileWorkManager(val context: Context, private val workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        const val DATA = "data"
        const val ID = "id"

        const val ID_REFRESH_DOWNLOADS = "REFRESH_DOWNLOADS"
        const val ID_REFRESH_READINGPROGRESS = "REFRESH_READINGPROGRESS"
        const val ID_DOWNLOAD = "ID_DOWNLOAD"

        private var _viewModel: WeakReference<DownloadViewModel> = WeakReference(null)
        var viewModel: DownloadViewModel?
            get() = _viewModel.get()
            set(value) {
                _viewModel = WeakReference(value)
            }

        private var workNumber: Int = 0
        private val workData: HashMap<Int, Any> = hashMapOf()

        // java.lang.IllegalStateException: Data cannot occupy more than 10240 bytes when serialized
        // This stores the actual data for the WorkManager to use
        private fun insertWork(data: Any): Int {
            synchronized(workData) {
                workNumber += 1
                workData[workNumber] = data
                return workNumber
            }
        }

        private fun popWork(key: Int): Any? {
            synchronized(workData) {
                return workData.remove(key)
            }
        }

        fun refreshAll(from: DownloadViewModel, context: Context) {
            viewModel = from

            (WorkManager.getInstance(context)).enqueueUniqueWork(
                ID_REFRESH_DOWNLOADS,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(DownloadFileWorkManager::class.java)
                    .setInputData(
                        Data.Builder()
                            .putString(ID, ID_REFRESH_DOWNLOADS)
                            .build()
                    )
                    .build()
            )
        }

        fun refreshAllReadingProgress(from: DownloadViewModel, context: Context, currentTab: Int) {
            viewModel = from
            val uniqueWorkName = "${ID_REFRESH_READINGPROGRESS}_$currentTab"
            (WorkManager.getInstance(context)).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(DownloadFileWorkManager::class.java)
                    .setInputData(
                        Data.Builder()
                            .putString(ID, ID_REFRESH_READINGPROGRESS)
                            .putInt(CURRENT_TAB, currentTab)
                            .build()
                    )
                    .build()
            )
        }

        private fun startDownload(data: Any, context: Context) {
            (WorkManager.getInstance(context)).enqueueUniqueWork(
                ID_DOWNLOAD + System.currentTimeMillis(),
                ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(DownloadFileWorkManager::class.java)
                    .setInputData(
                        Data.Builder()
                            .putString(ID, ID_DOWNLOAD)
                            .putInt(DATA, insertWork(data))
                            .build()
                    )
                    .build()
            )
        }

        fun download(
            card: DownloadFragment.DownloadDataLoaded,
            context: Context
        ) {
            startDownload(card, context)
        }

        fun download(
            load: LoadResponse,
            context: Context
        ) {
            if(load.apiName == IMPORT_SOURCE || load.apiName == IMPORT_SOURCE_PDF) {
                return
            }
            startDownload(load, context)
        }
    }

    /**
     * Builds a [ForegroundInfo] using the book's own download notification ID so that
     * the foreground-service notification and the progress notification are the same object.
     * When BookDownloader2.createNotification() later calls notify(notifId, …) with the same
     * ID the notification is updated in-place — only one notification is ever shown.
     */
    private fun createForegroundInfo(notifId: Int, title: String): ForegroundInfo {
        val channelId = NotificationHelper.CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                NotificationHelper.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText("Downloading…")
            .setSmallIcon(R.drawable.rdload)
            .setOngoing(true)
            .build()
        // On API 29+ the ForegroundInfo must declare a service type that matches
        // the FOREGROUND_SERVICE_DATA_SYNC permission in AndroidManifest.xml.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notifId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    @WorkerThread
    override suspend fun doWork(): Result {
        val id = this.workerParams.inputData.getString(ID)
        when (id) {
            ID_DOWNLOAD -> {
                when (val data = popWork(this.workerParams.inputData.getInt(DATA, -1))) {
                    is StreamResponse -> {
                        // Use the book's own notification ID so the foreground-service
                        // notification and the progress notification are the same one.
                        val notifId = BookDownloader2Helper.generateId(data, data.apiName)
                        setForeground(createForegroundInfo(notifId, data.name))
                        BookDownloader2.downloadWorkThread(data, Apis.getApiFromName(data.apiName))
                    }

                    is EpubResponse -> {
                        val notifId = BookDownloader2Helper.generateId(data, data.apiName)
                        setForeground(createForegroundInfo(notifId, data.name))
                        BookDownloader2.downloadWorkThread(data, Apis.getApiFromName(data.apiName))
                    }

                    is DownloadFragment.DownloadDataLoaded -> {
                        // card.id matches the notification ID used by BookDownloader2
                        setForeground(createForegroundInfo(data.id, data.name))
                        if(data.apiName == IMPORT_SOURCE_PDF)
                            BookDownloader2.downloadPDFWorkThread(data.source.toUri(), context)
                        else
                            BookDownloader2.downloadWorkThread(data)
                    }

                    else -> return Result.failure()
                }
            }

            ID_REFRESH_DOWNLOADS -> {
                viewModel?.refreshInternal()
            }

            ID_REFRESH_READINGPROGRESS ->{
                val currentTab = this.workerParams.inputData.getInt(CURRENT_TAB, 1)
                viewModel?.setIsLoading(true, currentTab)
                BookDownloader2.getOldDataReadingProgress(currentTab)
                viewModel?.setIsLoading(false, currentTab)
            }

            else -> return Result.failure()
        }
        return Result.success()
    }
}