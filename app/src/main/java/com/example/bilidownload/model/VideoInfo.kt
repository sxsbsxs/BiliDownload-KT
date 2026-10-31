package com.example.bilidownload.model

data class VideoPage(
    val cid: Long,
    val page: Int,
    val part: String,
    val durationSeconds: Int = 0
)

data class VideoInfo(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val owner: String,
    val durationSeconds: Int,
    val coverUrl: String,
    val pages: List<VideoPage>,
    val description: String = "",
    val ownerMid: Long = 0L,
    val viewCount: Long = 0L,
    val danmakuCount: Long = 0L,
    val likeCount: Long = 0L,
    val coinCount: Long = 0L,
    val favoriteCount: Long = 0L,
    val replyCount: Long = 0L,
    val canonicalUrl: String = ""
)

data class StreamChoice(
    val quality: Int,
    val description: String
)

data class PlayStreams(
    val videoUrl: String?,
    val audioUrl: String?,
    val progressiveUrl: String?,
    val acceptedQualities: List<StreamChoice>,
    val videoBackups: List<String> = emptyList(),
    val audioBackups: List<String> = emptyList(),
    val progressiveBackups: List<String> = emptyList(),
    val format: String = "dash"
)

data class BiliProfile(
    val uid: Long,
    val name: String,
    val avatar: String = "",
    val vipType: Int = 0
)

data class BiliComment(
    val id: Long,
    val userId: Long,
    val userName: String,
    val avatar: String,
    val message: String,
    val likeCount: Int,
    val timestamp: Long
)

data class DanmakuItem(
    val seconds: Double,
    val mode: Int,
    val fontSize: Int,
    val color: Int,
    val text: String
)

data class SubtitleTrack(
    val id: Long,
    val language: String,
    val url: String,
    val author: String = ""
)

data class FavoriteFolder(
    val id: Long,
    val title: String,
    val mediaCount: Int
)

data class LocalDownload(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val mimeType: String
)

data class DownloadTaskState(
    val id: String,
    val title: String,
    val progress: Int = 0,
    val message: String = "",
    val state: String = "queued",
    val files: List<String> = emptyList(),
    val error: String = ""
)
