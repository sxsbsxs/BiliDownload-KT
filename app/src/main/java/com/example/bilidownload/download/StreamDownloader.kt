package com.example.bilidownload.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StreamDownloader(private val context: Context) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueue(url: String, title: String, suffix: String, cookie: String): Long {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "下载地址无效"
        }
        val safeTitle = title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(72)
            .ifBlank { "bilibili_video" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${safeTitle}_${timestamp}.$suffix"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("BiliDownload-KT 下载任务")
            .setMimeType(
                when {
                    suffix.endsWith("m4s") -> "application/octet-stream"
                    suffix.equals("mp4", true) -> "video/mp4"
                    suffix.equals("gif", true) -> "image/gif"
                    suffix.equals("webp", true) -> "image/webp"
                    else -> "image/jpeg"
                }
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("Referer", "https://www.bilibili.com/")
            .addRequestHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
            )
        if (cookie.isNotBlank()) request.addRequestHeader("Cookie", cookie)
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "BiliDownload/$fileName"
        )
        return manager.enqueue(request)
    }

    fun enqueueCover(url: String, title: String, cookie: String): Long {
        val extension = when {
            Regex("""(?i)\.gif(?:$|[?#])""").containsMatchIn(url) -> "gif"
            Regex("""(?i)\.webp(?:$|[?#])""").containsMatchIn(url) -> "webp"
            Regex("""(?i)\.png(?:$|[?#])""").containsMatchIn(url) -> "png"
            else -> "jpg"
        }
        return enqueue(url, "${title}_cover", extension, cookie)
    }

    fun queryStatus(id: Long): Int? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }
}
