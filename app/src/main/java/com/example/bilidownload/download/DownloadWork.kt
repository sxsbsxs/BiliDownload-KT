package com.example.bilidownload.download

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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

    fun enqueue(context: Context, video: VideoInfo, pages: List<VideoPage>, quality: Int, mode: String, subtitleLanguage: String) {
        require(pages.isNotEmpty()) { "没有可下载的分P" }
        val pageArray = JSONArray()
        pages.forEach { pageArray.put(JSONObject().put("cid", it.cid).put("page", it.page).put("part", it.part).put("duration", it.durationSeconds)) }
        val payload = JSONObject().put("bvid", video.bvid).put("aid", video.aid).put("cid", video.cid)
            .put("owner", video.owner).put("duration", video.durationSeconds).put("cover", video.coverUrl)
            .put("pages", pageArray).toString()
        val request = OneTimeWorkRequestBuilder<BiliDownloadWorker>()
            .setInputData(workDataOf(KEY_TITLE to video.title, KEY_VIDEO to payload, KEY_MODE to mode,
                KEY_LANGUAGE to subtitleLanguage, KEY_QUALITY to quality))
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("bili-download-${request.id}", ExistingWorkPolicy.KEEP, request)
    }
}
