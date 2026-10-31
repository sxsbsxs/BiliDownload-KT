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
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.example.bilidownload.api.BiliApi
import com.example.bilidownload.model.VideoInfo
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

    /**
     * Direct streaming downloader ported from bilibilidl/downloader.py.
     * Writes .part files, resumes with Range, retries backup CDN URLs, and reports progress.
     */
    fun downloadVideo(
        api: BiliApi,
        info: VideoInfo,
        cid: Long,
        quality: Int,
        cookie: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): String {
        val streams = api.getPlayStreams(info, cid, quality, cookie)
        val folder = File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }
        val title = info.title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]+"), "_")
            .trim().take(100).ifBlank { "bilibili_video" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = uniqueFile(folder, "${title}_${stamp}.mp4")
        val http = OkHttpClient.Builder().connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build()
        fun download(urls: List<String>, target: File) {
            val failures = mutableListOf<String>()
            for (url in urls.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()) {
                if (isCancelled()) error("下载已取消")
                try {
                    val part = File(target.parentFile, target.name + ".part")
                    var offset = if (part.exists()) part.length() else 0L
                    val builder = Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                        .header("Referer", "https://www.bilibili.com/")
                        .header("Accept", "*/*")
                    val normalized = api.normalizeCookie(cookie)
                    if (normalized.isNotBlank()) builder.header("Cookie", normalized)
                    if (offset > 0) builder.header("Range", "bytes=$offset-")
                    http.newCall(builder.get().build()).execute().use { response ->
                        if (offset > 0 && response.code != 206) {
                            part.delete()
                            offset = 0
                        }
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body ?: error("空响应")
                        val total = body.contentLength().coerceAtLeast(0) + offset
                        var done = offset
                        FileOutputStream(part, offset > 0).use { out ->
                            val inputStream = body.byteStream()
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                if (isCancelled()) error("下载已取消")
                                val n = inputStream.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                out.write(buffer, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                            out.fd.sync()
                        }
                        if (part.length() <= 0) error("下载文件为空")
                        if (target.exists()) target.delete()
                        if (!part.renameTo(target)) error("无法完成临时文件重命名")
                        return
                    }
                } catch (e: Exception) {
                    if (isCancelled() || e.message?.contains("取消") == true) throw e
                    failures += (e.message ?: e.javaClass.simpleName)
                }
            }
            error("所有下载地址均失败：${failures.joinToString("；")}")
        }
        if (!streams.progressiveUrl.isNullOrBlank()) {
            download(listOf(streams.progressiveUrl), output)
        } else {
            val videoUrl = streams.videoUrl ?: error("B站未返回视频轨道")
            val audioUrl = streams.audioUrl ?: error("B站未返回音频轨道")
            val videoFile = File(folder, "${title}_${stamp}.video.m4s")
            val audioFile = File(folder, "${title}_${stamp}.audio.m4s")
            try {
                download(listOf(videoUrl) + streams.videoBackups, videoFile)
                download(listOf(audioUrl) + streams.audioBackups, audioFile)
                if (isCancelled()) error("下载已取消")
                muxLocalTracks(videoFile, audioFile, output)
            } catch (e: Exception) {
                // Keep the downloaded elementary tracks when muxing fails for diagnosis/recovery.
                throw IllegalStateException("音视频下载或合并失败：${e.message}。原始轨道保留在应用下载目录。", e)
            } finally {
                if (output.exists() && output.length() > 0) {
                    videoFile.delete()
                    audioFile.delete()
                }
            }
        }
        if (!output.isFile || output.length() <= 0) error("下载结果校验失败")
        return exportToPublicDownloads(output)
    }

    fun downloadBytes(
        url: String,
        displayName: String,
        cookie: String = "",
        maxBytes: Long = 50L * 1024L * 1024L
    ): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .get().build()
        val folder = File(context.filesDir, "downloads").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = uniqueFile(folder, safeName)
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("资源下载失败 HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: error("资源内容为空")
            if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) error("资源为空或超过大小限制")
            target.writeBytes(bytes)
        }
        return exportToPublicDownloads(target)
    }

    fun saveText(displayName: String, text: String): String {
        val folder = File(context.filesDir, "downloads").apply { mkdirs() }
        val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val file = uniqueFile(folder, safe)
        file.writeText(text, Charsets.UTF_8)
        if (!file.isFile || file.length() <= 0) error("保存文本文件失败")
        return exportToPublicDownloads(file)
    }

    private fun uniqueFile(folder: File, requestedName: String): File {
        val requested = File(folder, requestedName)
        if (!requested.exists()) return requested
        val base = requested.nameWithoutExtension
        val ext = requested.extension
        var index = 2
        while (true) {
            val candidate = File(folder, if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /** Delete only files owned by this app private downloads directory. */
    fun deleteLocalFile(file: File): Boolean {
        val root = File(context.filesDir, "downloads").canonicalFile
        val target = file.canonicalFile
        require(target.parentFile == root) { "只能删除应用下载目录中的文件" }
        require(target.isFile) { "文件不存在或不是普通文件" }
        return target.delete()
    }

    fun localFiles(): List<File> {
        val folder = File(context.filesDir, "downloads")
        if (!folder.exists()) return emptyList()
        return folder.listFiles()?.filter { it.isFile && it.length() > 0 &&
            !it.name.endsWith(".part") && !it.name.endsWith(".m4s") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private val httpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private fun muxLocalTracks(video: File, audio: File, output: File) {
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ve.setDataSource(video.absolutePath)
            ae.setDataSource(audio.absolutePath)
            val vi = (0 until ve.trackCount).firstOrNull {
                ve.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("视频轨道无法识别")
            val ai = (0 until ae.trackCount).firstOrNull {
                ae.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("音频轨道无法识别")
            ve.selectTrack(vi); ae.selectTrack(ai)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vo = muxer.addTrack(ve.getTrackFormat(vi))
            val ao = muxer.addTrack(ae.getTrackFormat(ai))
            muxer.start()
            writeSamples(ve, muxer, vo)
            writeSamples(ae, muxer, ao)
            muxer.stop()
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            runCatching { muxer?.release() }
            ve.release(); ae.release()
        }
    }

    private fun writeSamples(extractor: MediaExtractor, muxer: MediaMuxer, outTrack: Int) {
        val info = MediaCodec.BufferInfo()
        var capacity = 4 * 1024 * 1024
        val fmt = extractor.getTrackFormat(extractor.sampleTrackIndex.coerceAtLeast(0))
        if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            capacity = maxOf(capacity, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        }
        var buffer = ByteBuffer.allocateDirect(capacity)
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            if (size > buffer.capacity()) {
                buffer = ByteBuffer.allocateDirect(size + 1024 * 1024)
                buffer.clear()
                val retry = extractor.readSampleData(buffer, 0)
                if (retry < 0) break
                info.set(0, retry, extractor.sampleTime, extractor.sampleFlags)
            } else {
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            }
            buffer.position(0); buffer.limit(info.size)
            muxer.writeSampleData(outTrack, buffer, info)
            if (!extractor.advance()) break
        }
    }

    private fun exportToPublicDownloads(source: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val name = source.name
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".mp4", true)) "video/mp4" else "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/bilibilidl")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建 Download/bilibilidl 文件")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: error("无法写入公共下载目录")
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                return uri.toString()
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "bilibilidl")
            .apply { if (!exists()) mkdirs() }
        val target = File(folder, source.name)
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
