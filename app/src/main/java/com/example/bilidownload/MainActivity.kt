package com.example.bilidownload

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.bilidownload.api.BiliApi
import com.example.bilidownload.download.StreamDownloader
import com.example.bilidownload.model.VideoInfo
import com.example.bilidownload.security.CookieVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = CookieVault(this)
        val api = BiliApi()
        val downloader = StreamDownloader(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BiliHome(
                        initialCookie = vault.read(),
                        onSaveCookie = vault::save,
                        api = api,
                        downloader = downloader
                    )
                }
            }
        }
    }
}

@Composable
private fun BiliHome(
    initialCookie: String,
    onSaveCookie: (String) -> Unit,
    api: BiliApi,
    downloader: StreamDownloader
) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    var input by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf(initialCookie) }
    var video by remember { mutableStateOf<VideoInfo?>(null) }
    var selectedCid by remember { mutableLongStateOf(0L) }
    var quality by remember { mutableIntStateOf(80) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("粘贴 B站视频链接或 BV 号开始解析。") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BiliDownload KT", style = MaterialTheme.typography.headlineMedium)
        Text("Kotlin · Jetpack Compose · B站视频与封面下载")

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("视频链接 / BV 号") },
            placeholder = { Text("https://www.bilibili.com/video/BV...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = cookie,
            onValueChange = { cookie = it },
            label = { Text("B站 Cookie（可选）") },
            placeholder = { Text("SESSDATA=...; bili_jct=...") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            minLines = 2,
            maxLines = 4
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    busy = true
                    video = null
                    status = "正在解析视频信息…"
                    onSaveCookie(cookie)
                    scope.launch {
                        try {
                            val parsed = withContext(Dispatchers.IO) {
                                api.getVideoInfo(input, cookie)
                            }
                            video = parsed
                            selectedCid = parsed.pages.firstOrNull()?.cid ?: parsed.cid
                            status = "解析成功。请选择分P和画质。"
                        } catch (e: Exception) {
                            status = e.message ?: "解析失败，请检查链接或 Cookie。"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("解析视频") }
            OutlinedButton(
                onClick = {
                    cookie = ""
                    onSaveCookie("")
                    status = "已清除本机保存的 Cookie。"
                },
                modifier = Modifier.weight(1f)
            ) { Text("清除 Cookie") }
        }

        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.width(12.dp))
                Text("处理中，请稍候…")
            }
        }

        video?.let { info ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (info.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = info.coverUrl,
                            imageLoader = imageLoader,
                            contentDescription = "视频封面",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp),
                            onError = { status = "封面预览加载失败，但不影响尝试下载视频。" }
                        )
                    }
                    Text(
                        info.title,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text("UP主：${info.owner.ifBlank { "未知" }}")
                    Text("BV号：${info.bvid}")
                    Text("时长：${info.durationSeconds / 60}分${info.durationSeconds % 60}秒")
                    if (info.coverUrl.isNotBlank()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            onClick = {
                                try {
                                    val id = downloader.enqueueCover(info.coverUrl, info.title, cookie)
                                    status = "封面已加入系统下载队列（任务 ID：$id）。文件格式以资源 URL 为准；若服务端实际返回其他格式，扩展名可能需要手动调整。"
                                } catch (e: Exception) {
                                    status = e.message ?: "封面下载任务创建失败。"
                                }
                            }
                        ) { Text("下载封面图片") }
                    }
                }
            }

            if (info.pages.size > 1) {
                Text("分P选择", style = MaterialTheme.typography.titleMedium)
                info.pages.forEach { page ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedCid == page.cid,
                            onClick = { selectedCid = page.cid }
                        )
                        Text(
                            "P${page.page} · ${page.part}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Text("画质", style = MaterialTheme.typography.titleMedium)
            val qualities = listOf(
                127 to "8K", 126 to "杜比视界", 125 to "HDR", 120 to "4K",
                116 to "1080P 60帧", 112 to "1080P+", 80 to "1080P",
                64 to "720P", 32 to "480P", 16 to "360P"
            )
            qualities.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { (qn, label) ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = quality == qn, onClick = { quality = qn })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    status = "正在获取下载地址…"
                    onSaveCookie(cookie)
                    scope.launch {
                        try {
                            val streams = withContext(Dispatchers.IO) {
                                api.getPlayStreams(info, selectedCid, quality, cookie)
                            }
                            when {
                                streams.progressiveUrl != null -> {
                                    val id = downloader.enqueue(
                                        streams.progressiveUrl, info.title, "mp4", cookie
                                    )
                                    status = "视频已加入系统下载队列（任务 ID：$id）。"
                                }
                                streams.videoUrl != null && streams.audioUrl != null -> {
                                    val videoId = downloader.enqueue(
                                        streams.videoUrl, info.title, "video.m4s", cookie
                                    )
                                    val audioId = downloader.enqueue(
                                        streams.audioUrl, info.title, "audio.m4s", cookie
                                    )
                                    status = "视频流和音频流已加入下载队列（$videoId / $audioId）。当前版本尚未内置可靠的 DASH 合并器，请按 README 指引合并。"
                                }
                                else -> error(
                                    "接口未返回可用视频流。请检查 Cookie、视频权限或稍后重试。"
                                )
                            }
                        } catch (e: Exception) {
                            status = e.message ?: "获取下载地址失败。"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("开始下载视频") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                status,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            "下载目录：Download/BiliDownload。Cookie 等同账号凭据，请勿分享或上传。仅保存你有权下载的内容。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
