package com.example.bilidownload

import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.example.bilidownload.api.BiliApi
import com.example.bilidownload.api.BiliLoginManager
import com.example.bilidownload.download.StreamDownloader
import com.example.bilidownload.engine.PythonBridge
import com.example.bilidownload.model.*
import com.example.bilidownload.security.CookieVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

private enum class AppTab(val label: String) {
    HOME("首页"), DOWNLOADS("已下载"), SYNC("B站同步"), PROFILE("我的")
}
private enum class SaveMode(val label: String) {
    VIDEO("视频 + 音频（MP4）"), COVER("封面图片"), DANMAKU("弹幕 XML"), SUBTITLE("字幕 SRT")
}

@Composable
fun BiliDownloadApp(api: BiliApi, downloader: StreamDownloader, vault: CookieVault) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loginManager = remember { BiliLoginManager() }
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var cookie by remember { mutableStateOf(vault.read()) }
    var profile by remember { mutableStateOf<BiliProfile?>(null) }
    var loginBusy by remember { mutableStateOf(false) }
    var globalMessage by remember { mutableStateOf("") }
    var homeInput by remember { mutableStateOf("") }
    var loginEpoch by remember { mutableIntStateOf(0) }

    LaunchedEffect(loginEpoch, cookie) {
        profile = if (cookie.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching { api.getNav(cookie) }.getOrNull()
        }
    }

    fun startBiliAppLogin() {
        if (loginBusy) return
        loginBusy = true
        globalMessage = "正在向 B 站申请 App 授权链接…"
        scope.launch {
            try {
                val ticket = withContext(Dispatchers.IO) { loginManager.createTicket() }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ticket.url)).apply {
                    setPackage("tv.danmaku.bili")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    error("手机上的 B 站 App 没有接管这个官方授权链接。请更新 B 站 App 后重试；本应用不会伪造登录成功。")
                }
                globalMessage = "已将 B 站授权链接交给 B 站 App。请在 B 站 App 内确认授权，正在等待结果…"
                val result = withContext(Dispatchers.IO) {
                    var accepted: BiliLoginManager.LoginResult? = null
                    repeat(90) {
                        accepted = loginManager.poll(ticket)
                        if (accepted != null) return@withContext accepted
                        Thread.sleep(1800)
                    }
                    accepted
                }
                if (result == null) {
                    globalMessage = "等待 B 站 App 授权超时。请确认 B 站 App 已完成确认后再试。"
                } else {
                    cookie = result.cookie
                    vault.save(result.cookie)
                    profile = BiliProfile(result.uid, result.name, result.avatar, result.vipType)
                    loginEpoch++
                    globalMessage = "B站账号授权成功：${result.name}"
                }
            } catch (e: Exception) {
                globalMessage = e.message ?: "B站 App 授权失败"
            } finally {
                loginBusy = false
            }
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFE83E70),
            secondary = androidx.compose.ui.graphics.Color(0xFFF7DCE5),
            background = androidx.compose.ui.graphics.Color(0xFFF7F5F7),
            surface = androidx.compose.ui.graphics.Color.White
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(when (item) { AppTab.HOME -> "⌂"; AppTab.DOWNLOADS -> "↓"; AppTab.SYNC -> "↻"; AppTab.PROFILE -> "●" }) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (globalMessage.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(globalMessage, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { globalMessage = "" }) { Text("关闭") }
                        }
                    }
                }
                when (tab) {
                    AppTab.HOME -> HomePage(api, downloader, cookie, homeInput, { homeInput = it }, { globalMessage = it }, { tab = AppTab.DOWNLOADS }, { tab = AppTab.PROFILE })
                    AppTab.DOWNLOADS -> DownloadsPage(downloader)
                    AppTab.SYNC -> SyncPage(api, cookie, { video ->
                        homeInput = video.bvid.ifBlank { "av${video.aid}" }
                        tab = AppTab.HOME
                    })
                    AppTab.PROFILE -> ProfilePage(
                        profile, cookie.isNotBlank(), loginBusy, globalMessage,
                        onLogin = { startBiliAppLogin() },
                        onLogout = {
                            cookie = ""
                            profile = null
                            vault.save("")
                            loginEpoch++
                            globalMessage = "已退出并清除本机保存的 B 站 Cookie。"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    api: BiliApi,
    downloader: StreamDownloader,
    cookie: String,
    input: String,
    onInput: (String) -> Unit,
    onMessage: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var selectedPage by remember { mutableIntStateOf(0) }
    var allPages by remember { mutableStateOf(false) }
    var qualities by remember { mutableStateOf(emptyList<StreamChoice>()) }
    var quality by remember { mutableIntStateOf(80) }
    var mode by remember { mutableStateOf(SaveMode.VIDEO) }
    var modeMenu by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var subtitleTracks by remember { mutableStateOf(emptyList<SubtitleTrack>()) }
    var selectedSubtitleLanguage by remember { mutableStateOf("") }
    var subtitleMenu by remember { mutableStateOf(false) }
    var subtitleLoading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("粘贴 BV / AV / 视频链接或 b23.tv 短链开始解析。") }
    var progress by remember { mutableFloatStateOf(0f) }
    var autoPlay by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var cancelFlag by remember { mutableStateOf(false) }
    var taskKey by remember { mutableIntStateOf(0) }
    val currentPage = info?.pages?.getOrNull(selectedPage)
    val chosenQuality = qualities.firstOrNull { it.quality == quality } ?: qualities.firstOrNull()

    fun parseVideo() {
        if (input.isBlank() || busy) return
        busy = true; status = "正在解析视频信息和可用画质…"; progress = 0f
        showPlayer = false
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { api.getVideoInfo(PythonBridge.normalizeVideoInput(input), cookie) }
                val pages = if (parsed.pages.isNotEmpty()) parsed.pages else listOf(VideoPage(parsed.cid, 1, parsed.title, parsed.durationSeconds))
                val normalized = parsed.copy(pages = pages)
                info = normalized
                selectedPage = 0
                allPages = false
                subtitleTracks = emptyList()
                selectedSubtitleLanguage = ""
                try {
                    val streams = withContext(Dispatchers.IO) { api.getPlayStreams(normalized, pages.first().cid, 127, cookie) }
                    qualities = streams.acceptedQualities
                    quality = qualities.firstOrNull()?.quality ?: 80
                    status = "解析成功：${pages.size} 个分P，已读取接口实际返回的画质。"
                } catch (q: Exception) {
                    qualities = emptyList(); quality = 80
                    status = "视频信息已解析；游客画质列表暂不可用（${q.message ?: "接口限制"}），仍可尝试下载。"
                }
                if (autoPlay) showPlayer = true
            } catch (e: Exception) {
                info = null; qualities = emptyList()
                status = "解析失败：${e.message ?: "请检查链接"}"
            } finally { busy = false }
        }
    }

    fun selectPart(index: Int) {
        selectedPage = index
        allPages = false
        val current = info ?: return
        val page = current.pages.getOrNull(index) ?: return
        scope.launch {
            try {
                val streams = withContext(Dispatchers.IO) {
                    api.getPlayStreams(current, page.cid, 127, cookie)
                }
                qualities = streams.acceptedQualities
                quality = qualities.firstOrNull()?.quality ?: 80
                if (mode == SaveMode.SUBTITLE) {
                    runCatching { api.getSubtitleTracks(current, page.cid, cookie) }
                        .onSuccess { tracks ->
                            subtitleTracks = tracks
                            if (tracks.none { it.language == selectedSubtitleLanguage }) {
                                selectedSubtitleLanguage = tracks.firstOrNull()?.language.orEmpty()
                            }
                        }
                }
                status = "已切换到 P${page.page}，已更新该分P实际可用画质。"
            } catch (e: Exception) {
                qualities = emptyList()
                quality = 80
                status = "已切换到 P${page.page}，画质列表暂不可用；仍可尝试下载。"
            }
        }
    }

    fun loadSubtitleTracks() {
        val video = info ?: return
        val page = currentPage ?: return
        subtitleLoading = true
        scope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { api.getSubtitleTracks(video, page.cid, cookie) }
                subtitleTracks = tracks
                if (tracks.none { it.language == selectedSubtitleLanguage }) {
                    selectedSubtitleLanguage = tracks.firstOrNull()?.language.orEmpty()
                }
                status = if (tracks.isEmpty()) "当前分P没有公开字幕" else "找到 ${tracks.size} 条字幕轨道"
            } catch (e: Exception) {
                subtitleTracks = emptyList()
                status = "读取字幕列表失败：${e.message ?: "接口暂不可用"}"
            } finally { subtitleLoading = false }
        }
    }

    fun startDownload() {
        val video = info ?: return
        if (busy) return
        val pages = if (allPages) video.pages else listOfNotNull(currentPage)
        if (pages.isEmpty()) { status = "请先选择有效分P"; return }
        runCatching {
            DownloadWork.enqueue(context, video, pages, chosenQuality?.quality ?: quality, mode.name, selectedSubtitleLanguage)
        }.onSuccess {
            status = "下载任务已加入后台队列。离开页面后仍会继续，可在“已下载”查看任务。"
            onOpenDownloads()
        }.onFailure {
            status = "创建后台下载任务失败：${it.message ?: "未知错误"}"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("bilibilidl", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("BILIBILI VIDEO TOOL / KOTLIN", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onOpenProfile) { Text("B站登录 ↗") }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("把喜欢的视频，认真保存下来。", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("支持 BV、AV、完整视频链接和 b23.tv 短链")
                OutlinedTextField(value = input, onValueChange = onInput, modifier = Modifier.fillMaxWidth(),
                    label = { Text("视频链接 / BV号 / AV号") }, singleLine = true)
                Button(onClick = { parseVideo() }, enabled = !busy && input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "处理中…" else "解析视频")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoPlay, onCheckedChange = { autoPlay = it })
                    Text("解析后自动播放")
                }
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("解析状态", fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
        info?.let { video ->
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(model = video.coverUrl, contentDescription = "视频封面", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
                    Text(video.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("UP主：${video.owner} · 时长：${formatDuration(video.durationSeconds)}")
                    Text("播放 ${video.viewCount} · 弹幕 ${video.danmakuCount} · 点赞 ${video.likeCount}")
                    if (video.description.isNotBlank()) Text(video.description, maxLines = 5, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { showPlayer = !showPlayer }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showPlayer) "关闭在线播放器" else "在线播放（B站播放器） ▶")
                    }
                    if (showPlayer) {
                        val page = currentPage ?: video.pages.firstOrNull()
                        val playerUrl = if (page != null) {
                            val idParam = if (video.bvid.isNotBlank()) "bvid=${video.bvid}" else "aid=${video.aid}"
                            "https://player.bilibili.com/player.html?$idParam&cid=${page.cid}&danmaku=1&autoplay=0&high_quality=1&as_wide=1"
                        } else video.canonicalUrl
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    webChromeClient = WebChromeClient()
                                    webViewClient = WebViewClient()
                                    loadUrl(playerUrl)
                                }
                            },
                            update = { it.loadUrl(playerUrl) },
                            modifier = Modifier.fillMaxWidth().height(240.dp)
                        )
                    }
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("下载设置", fontWeight = FontWeight.Bold)
                    Box {
                        OutlinedButton(onClick = { qualityMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("画质：${chosenQuality?.description ?: "接口暂未返回画质列表（尝试 1080P）"}")
                        }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            qualities.forEach { q ->
                                DropdownMenuItem(text = { Text(q.description) }, onClick = { quality = q.quality; qualityMenu = false })
                            }
                            if (qualities.isEmpty()) DropdownMenuItem(text = { Text("暂无真实画质选项") }, onClick = { qualityMenu = false })
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { modeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("保存内容：${mode.label}") }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            SaveMode.entries.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { mode = m; modeMenu = false; if (m == SaveMode.SUBTITLE) loadSubtitleTracks() }) }
                        }
                    }
                    if (mode == SaveMode.SUBTITLE) {
                        Box {
                            OutlinedButton(onClick = { if (subtitleTracks.isEmpty()) loadSubtitleTracks() else subtitleMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = !subtitleLoading) {
                                Text(if (subtitleLoading) "正在读取字幕…" else "字幕语言：${selectedSubtitleLanguage.ifBlank { "未选择" }}")
                            }
                            DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                                subtitleTracks.distinctBy { it.language }.forEach { track ->
                                    DropdownMenuItem(text = { Text(track.language.ifBlank { "未标注语言" }) }, onClick = { selectedSubtitleLanguage = track.language; subtitleMenu = false })
                                }
                                if (subtitleTracks.isEmpty()) DropdownMenuItem(text = { Text("当前分P无可用字幕") }, onClick = { subtitleMenu = false })
                            }
                        }
                        Text("多分P下载时会优先匹配所选语言；若某一分P没有该语言，则回退到该分P的第一条可用字幕。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("分P列表", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allPages, onCheckedChange = { allPages = it })
                            Text("全部分P")
                        }
                    }
                    video.pages.forEachIndexed { index, page ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selectPart(index) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedPage == index && !allPages, onClick = { selectPart(index) })
                            Text("P${page.page} · ${page.part}", modifier = Modifier.weight(1f))
                            Text(formatDuration(page.durationSeconds), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("下载任务", fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
                    Text(if (busy) "正在处理：${progress.toInt()}%" else "准备就绪 · Download/bilibilidl")
                    Button(onClick = { startDownload() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("开始下载") }
                    OutlinedButton(onClick = { cancelFlag = true }, enabled = busy, modifier = Modifier.fillMaxWidth()) { Text("取消当前任务") }
                }
            }
            VideoActions(api, video, cookie, onMessage)
        }
    }
}

@Composable
private fun VideoActions(api: BiliApi, video: VideoInfo, cookie: String, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("B站互动", fontWeight = FontWeight.Bold)
            Text("互动操作需要账号授权；游客仍可使用公开视频解析和下载。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { onMessage(runCatching { withContext(Dispatchers.IO) { api.likeVideo(video, cookie) }; "点赞成功" }.getOrElse { it.message ?: "点赞失败" }) }
                }, enabled = cookie.isNotBlank(), modifier = Modifier.weight(1f)) { Text("点赞") }
                OutlinedButton(onClick = {
                    scope.launch { onMessage(runCatching { withContext(Dispatchers.IO) { api.coinVideo(video, cookie) }; "投币成功" }.getOrElse { it.message ?: "投币失败" }) }
                }, enabled = cookie.isNotBlank(), modifier = Modifier.weight(1f)) { Text("投币") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        onMessage(runCatching {
                            withContext(Dispatchers.IO) { api.followUser(video.ownerMid, cookie) }
                            "已关注 UP 主"
                        }.getOrElse { it.message ?: "关注失败" })
                    }
                }, enabled = cookie.isNotBlank() && video.ownerMid > 0, modifier = Modifier.weight(1f)) { Text("关注 UP 主") }
                OutlinedButton(onClick = {
                    scope.launch {
                        onMessage(runCatching {
                            withContext(Dispatchers.IO) {
                                val folder = api.getFavoriteFolders(cookie).firstOrNull() ?: error("没有可用收藏夹")
                                api.favoriteVideo(video, folder.id, cookie)
                            }
                            "已加入默认收藏夹"
                        }.getOrElse { it.message ?: "收藏失败" })
                    }
                }, enabled = cookie.isNotBlank(), modifier = Modifier.weight(1f)) { Text("收藏") }
            }
        }
    }
}

@Composable
private fun DownloadsPage(downloader: StreamDownloader) {
    var files by remember { mutableStateOf(downloader.localFiles()) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
        "gif" -> "image/gif"; "webp" -> "image/webp"; "xml" -> "application/xml"; "srt" -> "text/plain"; else -> "*/*"
    }
    fun contentUri(file: File) = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已下载", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { files = downloader.localFiles(); message = "" }) { Text("刷新") }
        }
        Text("共 ${files.size} 个文件 · ${formatBytes(files.sumOf { it.length() })}", style = MaterialTheme.typography.bodySmall)
        var workInfos by remember { mutableStateOf(emptyList<WorkInfo>()) }
        LaunchedEffect(Unit) {
            val manager = WorkManager.getInstance(context)
            while (true) {
                workInfos = runCatching { manager.getWorkInfosByTag(DownloadWork.TAG).get() }.getOrDefault(emptyList())
                delay(1000)
            }
        }
        val visibleTasks = workInfos.filter { it.state != WorkInfo.State.SUCCEEDED && it.state != WorkInfo.State.CANCELLED }
        if (visibleTasks.isNotEmpty()) Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("后台下载任务", fontWeight = FontWeight.Bold)
                visibleTasks.forEach { work ->
                    val title = work.progress.getString(DownloadWork.KEY_TITLE) ?: "B站下载任务"
                    val percent = work.progress.getInt(DownloadWork.KEY_PROGRESS, 0)
                    val message = work.progress.getString(DownloadWork.KEY_MESSAGE)
                        ?: if (work.state == WorkInfo.State.ENQUEUED) "排队中" else work.state.name
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    LinearProgressIndicator(progress = percent / 100f, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$message · $percent%", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { WorkManager.getInstance(context).cancelWorkById(work.id) },
                            enabled = work.state == WorkInfo.State.RUNNING || work.state == WorkInfo.State.ENQUEUED) { Text("取消") }
                    }
                }
            }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这里还空空的。解析并下载后，文件会显示在这里。") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(files, key = { it.absolutePath }) { file ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text("${formatBytes(file.length())} · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(file.lastModified())}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(file), mimeType(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                        .onFailure { message = it.message ?: "无法打开文件" }
                                }) { Text("打开") }
                                TextButton(onClick = {
                                    runCatching {
                                        val send = Intent(Intent.ACTION_SEND).apply { type = mimeType(file); putExtra(Intent.EXTRA_STREAM, contentUri(file)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                        context.startActivity(Intent.createChooser(send, "分享下载文件"))
                                    }.onFailure { message = it.message ?: "无法分享文件" }
                                }) { Text("分享") }
                                TextButton(onClick = { pendingDelete = file }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除本地文件？") },
            text = { Text("将从应用本地下载列表中删除“${file.name}”。已导出到系统 Download 文件夹的副本不会自动删除。") },
            confirmButton = { TextButton(onClick = {
                val deleted = runCatching { downloader.deleteLocalFile(file) }.getOrDefault(false)
                message = if (deleted) "已删除本地副本。" else "删除失败，文件可能已不存在。"
                files = downloader.localFiles(); pendingDelete = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}
@Composable
private fun SyncPage(api: BiliApi, cookie: String, onOpen: (VideoInfo) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var mode by remember { mutableStateOf("history") }
    var keyword by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<VideoInfo>()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("登录后可同步观看历史和收藏夹，也可搜索公开视频。") }

    fun load(which: String) {
        mode = which; busy = true; status = "正在加载…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (which) {
                        "history" -> api.getHistory(cookie)
                        "favorites" -> api.getFavoriteVideos(cookie)
                        else -> api.searchVideos(keyword, 1, cookie)
                    }
                }
                items = result
                status = "已加载 ${result.size} 条记录"
                if (which != "online") {
                    val arr = JSONArray()
                    result.forEach { v -> arr.put(JSONObject().put("bvid", v.bvid).put("aid", v.aid).put("title", v.title).put("owner", v.owner).put("cover", v.coverUrl).put("duration", v.durationSeconds)) }
                    context.getSharedPreferences("bilibilidl_sync", 0).edit().putString(which, arr.toString()).apply()
                }
            } catch (e: Exception) {
                status = e.message ?: "加载失败"
            } finally { busy = false }
        }
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("bilibilidl_sync", 0)
        val raw = prefs.getString("history", null)
        if (raw != null) {
            runCatching {
                val arr = JSONArray(raw)
                items = (0 until arr.length()).map { i ->
                    val x = arr.getJSONObject(i)
                    VideoInfo(x.optString("bvid"), x.optLong("aid"), 0, x.optString("title"), x.optString("owner"), x.optInt("duration"), x.optString("cover"), emptyList())
                }
                status = "显示本地缓存；点击同步获取最新数据。"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("B站同步", style = MaterialTheme.typography.headlineMedium)
        Text("观看历史 · 收藏夹 · 全站搜索", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = keyword, onValueChange = { keyword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("搜索视频标题或 UP 主") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("history" to "历史记录", "favorites" to "收藏夹", "online" to "B站搜索").forEach { (key, label) ->
                OutlinedButton(onClick = { if (key == "online" && keyword.isBlank()) status = "请输入搜索关键词" else load(key) }, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }
        Button(onClick = { load(if (mode == "online") "online" else mode) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "同步中…" else "同步 / 搜索") }
        Text(status, style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.bvid.ifBlank { it.aid.toString() } }) { v ->
                Card(Modifier.clickable { onOpen(v) }) {
                    Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = v.coverUrl, contentDescription = null, modifier = Modifier.size(92.dp, 62.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(v.owner, style = MaterialTheme.typography.bodySmall)
                            Text("点击交给首页解析", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePage(
    profile: BiliProfile?,
    hasCookie: Boolean,
    loginBusy: Boolean,
    message: String,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val files = remember { File(context.filesDir, "downloads").listFiles()?.filter { it.isFile && it.length() > 0 }.orEmpty() }
    val total = files.sumOf { it.length() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("我的", style = MaterialTheme.typography.headlineMedium)
        Card {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile?.avatar?.isNotBlank() == true) AsyncImage(model = profile.avatar, contentDescription = "头像", modifier = Modifier.size(72.dp))
                Text(profile?.name ?: "尚未登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (profile != null) "UID ${profile.uid}" else "授权登录后显示账号信息")
                Button(onClick = onLogin, enabled = !loginBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loginBusy) "正在等待 B站 App 授权…" else if (hasCookie) "重新授权 B站账号" else "打开 B站 App 授权登录")
                }
                if (hasCookie) OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("退出登录并清除本机 Cookie") }
                Text("登录流程通过 B站官方授权票据交给 B站 App，不在下载器里显示二维码。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本地下载统计", fontWeight = FontWeight.Bold)
                Text("文件数量：${files.size}")
                Text("占用空间：${formatBytes(total)}")
                Text("默认目录：Download/bilibilidl")
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("应用信息", fontWeight = FontWeight.Bold)
                Text("bilibilidl · Kotlin / Jetpack Compose")
                Text("参考仓库：github.com/sxsbsxs/bilibilidl", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sxsbsxs/bilibilidl")))
                }, modifier = Modifier.fillMaxWidth()) { Text("打开项目 GitHub 仓库 ↗") }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
