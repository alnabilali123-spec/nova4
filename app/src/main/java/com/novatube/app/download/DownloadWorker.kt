package com.novatube.app.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.novatube.app.NovaTubeApp
import com.novatube.app.R
import com.novatube.app.data.model.RequestedDownload
import com.novatube.app.service.DownloadService
import com.novatube.app.util.NotificationHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as NovaTubeApp
        val id = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return Result.failure(workDataOf("error" to "missing id"))

        val entity = app.downloadRepository.get(id) ?: return Result.failure(workDataOf("error" to "no row"))
        val request = RequestedDownload(
            url = entity.webpageUrl ?: entity.url,
            formatId = entity.formatId,
            fileName = entity.fileName.substringBeforeLast('.', entity.fileName),
            isAudioOnly = entity.isAudioOnly,
            audioFormat = entity.audioFormat ?: "mp3",
            title = entity.title,
            uploader = entity.uploader,
            thumbnail = entity.thumbnail,
            duration = entity.duration,
            webpageUrl = entity.webpageUrl
        )

        setForeground(createForegroundInfo(entity.title, 0))
        app.downloadRepository.markRunning(id)

        val completedSignal = CompletableDeferred<File?>()
        val manager = DownloadManager(applicationContext)

        val result = withTimeoutOrNull(6 * 60 * 60 * 1000L) {
            manager.run(
                request = request,
                listener = object : DownloadManager.ProgressListener {
                    override fun onProgress(line: DownloadManager.ProgressEvent) {
                        val percent = line.percent.toInt().coerceIn(0, 100)
                        // التصحيح: استخدام GlobalScope.launch بدلاً من runBlocking
                        kotlinx.coroutines.GlobalScope.launch {
                            app.downloadRepository.updateProgress(id, percent, line.downloadedBytes)
                        }
                        setProgress(
                            workDataOf(
                                KEY_PROGRESS to percent,
                                KEY_BYTES to line.downloadedBytes,
                                KEY_TOTAL to line.totalBytes,
                                KEY_SPEED to line.speed
                            )
                        )
                        setForeground(createForegroundInfo(entity.title, percent))
                        DownloadService.broadcastProgress(applicationContext, id, percent, entity.title)
                    }
                    override fun onCompleted(file: File) {
                        completedSignal.complete(file)
                    }
                    override fun onError(message: String, cause: Throwable?) {
                        completedSignal.completeExceptionally(RuntimeException(message, cause))
                    }
                },
                shouldCancel = { isStopped }
            )
            try { completedSignal.await() } catch (e: Exception) { null }
        }

        return if (result != null) {
            val file = result
            val size = file.length()
            app.downloadRepository.markCompleted(id, file.absolutePath, size)
            DownloadService.broadcastComplete(applicationContext, id, entity.title, file.absolutePath)
            setProgress(workDataOf(KEY_PROGRESS to 100, KEY_TOTAL to size, KEY_BYTES to size))
            Result.success(workDataOf("path" to file.absolutePath, "size" to size))
        } else {
            val msg = if (isStopped) "Cancelled" else "Timed out"
            app.downloadRepository.markFailed(id, msg)
            DownloadService.broadcastFailed(applicationContext, id, entity.title, msg)
            Result.failure(workDataOf("error" to msg))
        }
    }

    private fun createForegroundInfo(title: String, percent: Int): ForegroundInfo {
        val notification = buildNotification(title, percent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(title: String, percent: Int): Notification {
        val cancelIntent = DownloadService.cancelIntent(applicationContext)
        val pi = android.app.PendingIntent.getService(
            applicationContext, 0, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_DOWNLOADS)
            .setContentTitle(applicationContext.getString(R.string.notif_downloading, title))
            .setContentText(applicationContext.getString(R.string.notif_progress, percent))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, percent == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, applicationContext.getString(R.string.common_cancel), pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun workDataOf(vararg pairs: Pair<String, Any?>): Data {
        val b = Data.Builder()
        pairs.forEach { (k, v) -> if (v == null) b.putString(k, null) else when (v) {
            is String -> b.putString(k, v)
            is Int -> b.putInt(k, v)
            is Long -> b.putLong(k, v)
            is Float -> b.putFloat(k, v)
            is Double -> b.putDouble(k, v)
            is Boolean -> b.putBoolean(k, v)
            else -> b.putString(k, v.toString())
        } }
        return b.build()
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_SPEED = "speed"
        private const val NOTIF_ID = 1001
    }
}
