package com.example.bilidownload.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.bilidownload.api.BiliApi
import com.example.bilidownload.model.VideoInfo
import com.example.bilidownload.model.VideoPage
import com.example.bilidownload.security.CookieVault
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class BiliDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString(DownloadWork.KEY_TITLE) ?: "B站视频"
        return try {
            setForeground(foreground(title, "正在准备下载"))
            val taskId = inputData.getString(DownloadWork.KEY_TASK_ID).orEmpty()
            val taskFile = java.io.File(applicationContext.filesDir, "download_tasks/$taskId.json")
            val payload = if (taskFile.isFile) taskFile.readText()
                else inputData.getString(DownloadWork.KEY_VIDEO) ?: error("任务数据缺失")
            val json = JSONObject(payload)
            val pagesJson = json.getJSONArray("pages")
            val pages = (0 until pagesJson.length()).map { i ->
                val p = pagesJson.getJSONObject(i)
                VideoPage(p.getLong("cid"), p.getInt("page"), p.optString("part"), p.optInt("duration"))
            }
            require(pages.isNotEmpty()) { "没有有效分P" }
            val video = VideoInfo(json.optString("bvid"), json.optLong("aid"), json.optLong("cid"), title,
                json.optString("owner"), json.optInt("duration"), json.optString("cover"), pages)
            val mode = inputData.getString(DownloadWork.KEY_MODE) ?: "VIDEO"
            val language = inputData.getString(DownloadWork.KEY_LANGUAGE).orEmpty()
            val quality = inputData.getInt(DownloadWork.KEY_QUALITY, 80)
            val cookie = CookieVault(applicationContext).read()
            val api = BiliApi()
            val downloader = StreamDownloader(applicationContext)
            val minimumFree = if (mode == "VIDEO") 150L * 1024L * 1024L else 12L * 1024L * 1024L
            if (downloader.availableSpaceBytes() < minimumFree) {
                val needed = if (mode == "VIDEO") "150 MB" else "12 MB"
                error("存储空间不足，请至少释放 $needed 后重试")
            }
            val output = mutableListOf<String>()
            var lastProgressAt = System.currentTimeMillis()
            var lastProgressBytes = 0L
            val workPages = if (mode == "COVER") pages.take(1) else pages
            workPages.forEachIndexed { index, page ->
                if (isStopped) return failure("任务已取消")
                val path = when (mode) {
                    "VIDEO" -> downloader.downloadVideo(api, video, page.cid, quality, cookie, taskId,
                        onProgress = { done, total ->
                            if (downloader.availableSpaceBytes() < 24L * 1024L * 1024L) error("存储空间不足，已保留临时文件；释放空间后可恢复")
                            val local = if (total > 0) (done * 100 / total).toInt() else 0
                            val percent = ((index * 100 + local) / pages.size).coerceIn(0, 99)
                            val now = System.currentTimeMillis()
                            val elapsed = (now - lastProgressAt).coerceAtLeast(1L)
                            val speed = ((done - lastProgressBytes).coerceAtLeast(0L) * 1000L / elapsed)
                            if (elapsed >= 1000L) {
                                lastProgressAt = now
                                lastProgressBytes = done
                            }
                            val eta = if (speed > 0 && total > done) (total - done) / speed else -1L
                            val message = "正在下载 P${page.page}"
                            setProgressAsync(Data.Builder().putInt(DownloadWork.KEY_PROGRESS, percent)
                                .putLong(DownloadWork.KEY_SPEED, speed).putLong(DownloadWork.KEY_ETA, eta)
                                .putString(DownloadWork.KEY_TITLE, title).putString(DownloadWork.KEY_MESSAGE, message).build())
                            setForegroundAsync(foreground(title, "$message · $percent%"))
                        }, isCancelled = { isStopped })
                    "COVER" -> {
                        val ext = when {
                            video.coverUrl.contains(".gif", true) -> "gif"
                            video.coverUrl.contains(".webp", true) -> "webp"
                            video.coverUrl.contains(".png", true) -> "png"
                            else -> "jpg"
                        }
                        downloader.downloadBytes(video.coverUrl, "${video.bvid}_cover.$ext", cookie, taskId = taskId, isCancelled = { isStopped })
                    }
                    "DANMAKU" -> downloader.downloadBytes("https://comment.bilibili.com/${page.cid}.xml",
                        "${video.bvid}_P${page.page}_danmaku.xml", cookie, taskId = taskId, isCancelled = { isStopped })
                    "SUBTITLE" -> {
                        val tracks = api.getSubtitleTracks(video, page.cid, cookie)
                        val track = tracks.firstOrNull { it.language == language } ?: tracks.firstOrNull()
                            ?: error("该分P没有公开字幕")
                        downloader.saveText("${video.bvid}_P${page.page}.srt", api.downloadSubtitle(track, cookie), taskId)
                    }
                    else -> error("未知下载类型：$mode")
                }
                output += path
                val percent = ((index + 1) * 100 / workPages.size).coerceIn(0, 100)
                val message = "已完成 ${index + 1}/${workPages.size}"
                setProgress(Data.Builder().putInt(DownloadWork.KEY_PROGRESS, percent)
                    .putString(DownloadWork.KEY_TITLE, title).putString(DownloadWork.KEY_MESSAGE, message).build())
                setForeground(foreground(title, message))
            }
            downloader.cleanupTaskArtifacts(taskId)
            taskFile.delete()
            java.io.File(applicationContext.filesDir, "download_tasks/$taskId.meta.json").delete()
            DownloadHistoryStore(applicationContext).record(
                inputData.getString(DownloadWork.KEY_TASK_ID).orEmpty(), title,
                inputData.getString(DownloadWork.KEY_MODE) ?: "VIDEO", output, System.currentTimeMillis()
            )
            Result.success(Data.Builder().putStringArray(DownloadWork.KEY_FILES, output.toTypedArray())
                .putInt(DownloadWork.KEY_PROGRESS, 100).putString(DownloadWork.KEY_TITLE, title).build())
        } catch (e: Exception) {
            if (isStopped) return failure("任务已取消")
            val message = e.message ?: e.javaClass.simpleName
            if (runAttemptCount < 5 && isTransient(e, message)) {
                setProgress(Data.Builder().putString(DownloadWork.KEY_TITLE, title)
                    .putString(DownloadWork.KEY_MESSAGE, "网络异常，准备自动重试（${runAttemptCount + 1}/3）")
                    .putString(DownloadWork.KEY_ERROR, message).build())
                return Result.retry()
            }
            failure(message)
        }
    }

    private fun isTransient(error: Throwable, message: String): Boolean {
        val lower = message.lowercase()
        return error is IOException || error is SocketTimeoutException || error is UnknownHostException ||
            listOf("http 429", "http 5", "too many requests", "限流", "timeout", "timed out",
                "connection", "网络", "socket", "temporarily unavailable").any { lower.contains(it) }
    }

    private fun failure(message: String) = Result.failure(Data.Builder().putString(DownloadWork.KEY_ERROR, message).build())

    private fun foreground(title: String, message: String): ForegroundInfo {
        val channel = "bili_download_tasks"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channel, "下载任务", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title)
            .setContentText(message).setOngoing(true).setOnlyAlertOnce(true).build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(DownloadWork.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(DownloadWork.NOTIFICATION_ID, notification)
    }
}
