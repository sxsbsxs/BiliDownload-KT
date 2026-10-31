package com.example.bilidownload

import android.os.Build
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.bilidownload.api.BiliApi
import com.example.bilidownload.download.StreamDownloader
import com.example.bilidownload.model.VideoInfo
import com.example.bilidownload.security.CookieVault
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var qualityChoices by remember { mutableStateOf(emptyList<com.example.bilidownload.model.StreamChoice>()) }
    var showLogin by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrKey by remember { mutableStateOf("") }
    var loginMessage by remember { mutableStateOf("点击获取 B 站官方扫码二维码。") }
    var loginBusy by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrTicket by remember { mutableStateOf<BiliApi.QrLoginTicket?>(null) }
    var qrStatus by remember { mutableStateOf("点击下方按钮获取官方扫码二维码") }
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

        OutlinedButton(onClick = { showLogin = !showLogin }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showLogin) "返回下载页面" else "哔哩哔哩扫码登录")
        }


        if (showLogin) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("扫码登录", style = MaterialTheme.typography.titleLarge)
                    Text("哔哩哔哩", style = MaterialTheme.typography.headlineSmall)
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "哔哩哔哩登录二维码",
                            modifier = Modifier.size(240.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("使用哔哩哔哩手机 App 扫码登录。")
                    }
                    Text(loginMessage, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        enabled = !loginBusy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            loginBusy = true
                            qrBitmap = null
                            qrKey = ""
                            loginMessage = "正在申请二维码…"
                            scope.launch {
                                try {
                                    val pair = withContext(Dispatchers.IO) { api.generateQrLogin() }
                                    qrKey = pair.second
                                    val matrix = QRCodeWriter().encode(pair.first, BarcodeFormat.QR_CODE, 640, 640)
                                    val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
                                    for (y in 0 until 640) for (x in 0 until 640) {
                                        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                                    }
                                    qrBitmap = bitmap
                                    loginMessage = "请扫码，并在手机 B 站 App 上确认登录。"
                                    while (qrKey.isNotBlank()) {
                                        delay(2000)
                                        val state = withContext(Dispatchers.IO) { api.pollQrLogin(qrKey) }
                                        when (state.code) {
                                            0 -> {
                                                cookie = state.cookie
                                                onSaveCookie(state.cookie)
                                                loginMessage = "登录成功，登录状态已加密保存在本机。"
                                                qrKey = ""
                                                status = "B站扫码登录成功，可以开始获取画质并下载。"
                                            }
                                            86101 -> loginMessage = "等待扫码，请用 B 站 App 扫描二维码。"
                                            86090 -> loginMessage = "已扫码，请在手机上确认登录。"
                                            86038 -> {
                                                loginMessage = "二维码已过期，请重新获取。"
                                                qrKey = ""
                                            }
                                            else -> loginMessage = state.message.ifBlank { "正在等待扫码确认…" }
                                        }
                                    }
                                } catch (e: Exception) {
                                    loginMessage = e.message ?: "获取二维码失败，请稍后重试。"
                                } finally {
                                    loginBusy = false
                                }
                            }
                        }
                    ) { Text(if (qrBitmap == null) "获取登录二维码" else "刷新二维码") }
                    OutlinedButton(onClick = { showLogin = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("返回下载页面")
                    }
                }
            }
        }

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
            label = { Text("B站 Cookie（扫码登录后自动填写）") },
            placeholder = { Text("请先使用上方扫码登录") },
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
                            qualityChoices = try {
                                withContext(Dispatchers.IO) { api.getPlayStreams(parsed, selectedCid, 127, cookie).acceptedQualities }
                            } catch (_: Exception) {
                                try {
                                    withContext(Dispatchers.IO) { api.getPlayStreams(parsed, selectedCid, 80, cookie).acceptedQualities }
                                } catch (_: Exception) {
                                    listOf(
                                        com.example.bilidownload.model.StreamChoice(80, "1080P（尝试）"),
                                        com.example.bilidownload.model.StreamChoice(64, "720P（尝试）"),
                                        com.example.bilidownload.model.StreamChoice(32, "480P（尝试）")
                                    )
                                }
                            }
                            quality = qualityChoices.maxByOrNull { it.quality }?.quality ?: 80
                            status = if (cookie.isBlank()) "视频已解析。游客模式会尝试公开画质；高画质可能需要登录。" else "解析成功。已读取该视频和账号当前可用的画质。"
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
            if (qualityChoices.isEmpty()) {
                Text("暂未获取到该视频可用的画质列表。请先扫码登录，或确认视频是否有播放权限。")
            } else {
                qualityChoices.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { choice ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = quality == choice.quality, onClick = { quality = choice.quality })
                                Text(choice.description, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Button(
                enabled = !busy && video != null,
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
                                    status = "视频和音频正在下载，完成后会自动合并为 MP4…"
                                    val merged = withContext(Dispatchers.IO) {
                                        downloader.awaitAndMergeDash(videoId, audioId, info.title)
                                    }
                                    status = "下载并合并完成：$merged"
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
