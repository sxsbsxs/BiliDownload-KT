package com.example.bilidownload.download

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
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
        val normalizedCookie = cookie.trim()
            .removePrefix("Cookie:").removePrefix("cookie:")
            .replace("\r", "").replace("\n", "").trim()
        if (normalizedCookie.isNotBlank()) request.addRequestHeader("Cookie", normalizedCookie)
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


    /**
     * Waits for both DownloadManager tasks, muxes their fragmented MP4 tracks,
     * and publishes one playable MP4 in the Downloads collection.
     */
    fun awaitAndMergeDash(videoId: Long, audioId: Long, title: String): String {
        val deadline = System.currentTimeMillis() + 45L * 60L * 1000L
        var completedUris: Pair<Uri, Uri>? = null
        while (true) {
            val video = downloadState(videoId)
            val audio = downloadState(audioId)
            if (video.first == DownloadManager.STATUS_FAILED) {
                error("视频流下载失败，DownloadManager 原因码：${video.second}")
            }
            if (audio.first == DownloadManager.STATUS_FAILED) {
                error("音频流下载失败，DownloadManager 原因码：${audio.second}")
            }
            if (video.first == DownloadManager.STATUS_SUCCESSFUL && audio.first == DownloadManager.STATUS_SUCCESSFUL) {
                val videoUri = video.third ?: error("未取得视频流文件")
                val audioUri = audio.third ?: error("未取得音频流文件")
                completedUris = videoUri to audioUri
                break
            }
            if (System.currentTimeMillis() > deadline) error("等待视频/音频流下载超时")
            Thread.sleep(1000)
        }

        val (videoUri, audioUri) = completedUris ?: error("下载任务未完成")
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ").take(72).ifBlank { "bilibili_video" }
        val temp = File(context.cacheDir, "bili_${System.currentTimeMillis()}.mp4")
        muxDashTracks(videoUri, audioUri, temp)
        return try {
            publishMergedFile(temp, "${safeTitle}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
        } finally {
            temp.delete()
        }
    }

    private fun downloadState(id: Long): Triple<Int, Int, Uri?> {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            ?: error("无法查询下载任务 $id")
        cursor.use {
            if (!it.moveToFirst()) error("下载任务 $id 不存在")
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val local = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            return Triple(status, reason, local?.let(Uri::parse))
        }
    }

    private fun muxDashTracks(videoUri: Uri, audioUri: Uri, output: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(context, videoUri, null)
            audioExtractor.setDataSource(context, audioUri, null)
            val videoTrack = (0 until videoExtractor.trackCount).firstOrNull {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("视频流文件中没有可识别的视频轨道")
            val audioTrack = (0 until audioExtractor.trackCount).firstOrNull {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("音频流文件中没有可识别的音频轨道")
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()
            writeExtractorSamples(videoExtractor, muxer, outVideo)
            writeExtractorSamples(audioExtractor, muxer, outAudio)
            muxer.stop()
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun writeExtractorSamples(extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int) {
        val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(trackIndex, buffer, info)
            if (!extractor.advance()) break
        }
    }

    private fun publishMergedFile(source: File, displayName: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BiliDownload")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载目录中的 MP4 文件")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: error("无法写入合并后的视频文件")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                return uri.toString()
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BiliDownload")
        if (!directory.exists() && !directory.mkdirs()) error("无法创建应用下载目录")
        val target = File(directory, displayName)
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    fun queryStatus(id: Long): Int? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }
}
