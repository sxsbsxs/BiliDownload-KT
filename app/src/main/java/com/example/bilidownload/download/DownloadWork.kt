package com.example.bilidownload.download

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.io.File
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Data
import com.example.bilidownload.model.VideoInfo
import com.example.bilidownload.model.VideoPage
import org.json.JSONArray
import org.json.JSONObject

object DownloadWork {
    const val TAG = "bili-download"
    const val NOTIFICATION_ID = 7301
    const val KEY_TITLE = "title"
    const val KEY_VIDEO = "video"
    const val KEY_MODE = "mode"
    const val KEY_LANGUAGE = "language"
    const val KEY_QUALITY = "quality"
    const val KEY_PROGRESS = "progress"
    const val KEY_MESSAGE = "message"
    const val KEY_ERROR = "error"
    const val KEY_FILES = "files"
    const val KEY_SPEED = "speed"
    const val KEY_ETA = "eta"
    const val KEY_TASK_ID = "task_id"
    const val KEY_CREATED_AT = "created_at"

    fun enqueue(context: Context, video: VideoInfo, pages: List<VideoPage>, quality: Int, mode: String, subtitleLanguage: String) {
        require(pages.isNotEmpty()) { "没有可下载的分P" }
        val pageArray = JSONArray()
        pages.forEach { pageArray.put(JSONObject().put("cid", it.cid).put("page", it.page).put("part", it.part).put("duration", it.durationSeconds)) }
        val payload = JSONObject().put("bvid", video.bvid).put("aid", video.aid).put("cid", video.cid)
            .put("owner", video.owner).put("duration", video.durationSeconds).put("cover", video.coverUrl)
            .put("pages", pageArray).toString()
        val taskId = UUID.randomUUID().toString()
        val taskFolder = File(context.filesDir, "download_tasks").apply { mkdirs() }
        File(taskFolder, "$taskId.json").writeText(payload)
        val createdAt = System.currentTimeMillis()
        val input = workDataOf(KEY_TITLE to video.title, KEY_MODE to mode,
            KEY_LANGUAGE to subtitleLanguage, KEY_QUALITY to quality, KEY_TASK_ID to taskId, KEY_CREATED_AT to createdAt)
        File(taskFolder, "$taskId.meta.json").writeText(JSONObject().put(KEY_TITLE, video.title)
            .put(KEY_MODE, mode).put(KEY_LANGUAGE, subtitleLanguage).put(KEY_QUALITY, quality)
            .put(KEY_TASK_ID, taskId).put(KEY_CREATED_AT, createdAt).toString())
        val request = OneTimeWorkRequestBuilder<BiliDownloadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(TAG).addTag("bili-task-$taskId").build()
        WorkManager.getInstance(context).enqueueUniqueWork("bili-download-${taskId}", ExistingWorkPolicy.KEEP, request)
    }


    fun inputFor(context: Context, tags: Set<String>): Data {
        val taskId = tags.firstOrNull { it.startsWith("bili-task-") }?.removePrefix("bili-task-")
            ?: return Data.Builder().build()
        val file = File(context.filesDir, "download_tasks/$taskId.meta.json")
        if (!file.isFile) return Data.Builder().putString(KEY_TASK_ID, taskId).build()
        val json = JSONObject(file.readText())
        return workDataOf(KEY_TITLE to json.optString(KEY_TITLE), KEY_MODE to json.optString(KEY_MODE, "VIDEO"),
            KEY_LANGUAGE to json.optString(KEY_LANGUAGE), KEY_QUALITY to json.optInt(KEY_QUALITY, 80),
            KEY_TASK_ID to taskId, KEY_CREATED_AT to json.optLong(KEY_CREATED_AT, 0L))
    }

    fun resume(context: Context, input: androidx.work.Data) {
        val request = OneTimeWorkRequestBuilder<BiliDownloadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(TAG).build()
        val taskId = input.getString(KEY_TASK_ID) ?: request.id.toString()
        WorkManager.getInstance(context).enqueueUniqueWork("bili-download-${taskId}", ExistingWorkPolicy.REPLACE, request)
    }

    fun retry(context: Context, input: androidx.work.Data) = resume(context, input)

    fun pause(context: Context, id: UUID) {
        WorkManager.getInstance(context).cancelWorkById(id)
    }
}
