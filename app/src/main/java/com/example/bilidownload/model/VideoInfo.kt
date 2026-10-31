package com.example.bilidownload.model

data class VideoPage(
    val cid: Long,
    val page: Int,
    val part: String
)

data class VideoInfo(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val owner: String,
    val durationSeconds: Int,
    val coverUrl: String,
    val pages: List<VideoPage>
)

data class StreamChoice(
    val quality: Int,
    val description: String
)

data class PlayStreams(
    val videoUrl: String?,
    val audioUrl: String?,
    val progressiveUrl: String?,
    val acceptedQualities: List<StreamChoice>
)
