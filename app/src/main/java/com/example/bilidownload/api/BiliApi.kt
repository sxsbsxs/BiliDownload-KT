package com.example.bilidownload.api

import com.example.bilidownload.model.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONObject
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class BiliApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun normalizeCookie(raw: String): String = raw.trim()
        .removePrefix("Cookie:").removePrefix("cookie:")
        .replace("\\r", "").replace("\\n", "").trim()
        .removeSurrounding("\"")

    private fun request(url: String, cookie: String = "", referer: String = "https://www.bilibili.com/"): JSONObject {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
        val c = normalizeCookie(cookie)
        if (c.isNotBlank()) builder.header("Cookie", c)
        client.newCall(builder.get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(180)}")
            return JSONObject(body)
        }
    }

    private fun requestBytes(url: String, cookie: String = ""): ByteArray {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Accept", "*/*")
        val c = normalizeCookie(cookie)
        if (c.isNotBlank()) b.header("Cookie", c)
        client.newCall(b.get().build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} 下载失败")
            return response.body?.bytes() ?: error("服务器返回空内容")
        }
    }

    private fun dataOrThrow(json: JSONObject): JSONObject {
        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", "B站接口错误"))
            when (code) {
                -101 -> error("该操作需要登录，当前游客权限不足（-101）")
                -111 -> error("B站登录凭据校验失败（-111）")
                -352 -> error("B站触发风控（-352），请稍后重试")
                else -> error("B站接口错误（$code）：$msg")
            }
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    private fun getData(url: String, cookie: String = "") = dataOrThrow(request(url, cookie))

    fun extractVideoInput(input: String): Pair<String, String> {
        val value = input.trim()
        Regex("(?i)BV[0-9A-Za-z]{10}").find(value)?.value?.let { return "bvid" to it }
        Regex("(?i)(?:^|[/?:=&])av\\s*(\\d+)").find(value)?.groupValues?.get(1)?.let { return "aid" to it }
        Regex("^\\s*(\\d+)\\s*$").matchEntire(value)?.groupValues?.get(1)?.let { return "aid" to it }
        if (value.contains("b23.tv") || value.contains("bili2233.cn")) {
            val short = if (value.startsWith("http")) value else "https://$value"
            val req = Request.Builder().url(short).header("User-Agent", USER_AGENT).get().build()
            client.newCall(req).execute().use { response ->
                val target = response.request.url.toString()
                Regex("(?i)BV[0-9A-Za-z]{10}").find(target)?.value?.let { return "bvid" to it }
                Regex("(?i)/video/av(\\d+)").find(target)?.groupValues?.get(1)?.let { return "aid" to it }
                val html = response.body?.string().orEmpty()
                Regex("(?i)BV[0-9A-Za-z]{10}").find(html)?.value?.let { return "bvid" to it }
            }
        }
        val parsed = runCatching { okhttp3.HttpUrl.get(if (value.startsWith("http")) value else "https://$value") }.getOrNull()
        if (parsed != null && parsed.host.endsWith("bilibili.com")) {
            Regex("(?i)BV[0-9A-Za-z]{10}").find(parsed.encodedPath)?.value?.let { return "bvid" to it }
            Regex("(?i)av(\\d+)").find(parsed.encodedPath)?.groupValues?.get(1)?.let { return "aid" to it }
        }
        error("无法识别视频地址，请输入 BV、AV、B站视频链接或 b23.tv 短链")
    }

    fun getVideoInfo(input: String, cookie: String = ""): VideoInfo {
        val (kind, id) = extractVideoInput(input)
        val query = if (kind == "bvid") "bvid=${encode(id)}" else "aid=${encode(id)}"
        val data = getData("$API/x/web-interface/view?$query", cookie)
        val pagesJson = data.optJSONArray("pages") ?: JSONArray()
        val pages = (0 until pagesJson.length()).map { i ->
            val p = pagesJson.getJSONObject(i)
            VideoPage(p.optLong("cid"), p.optInt("page", i + 1), p.optString("part", "P${i + 1}"), p.optInt("duration"))
        }
        val stat = data.optJSONObject("stat") ?: JSONObject()
        val owner = data.optJSONObject("owner") ?: JSONObject()
        val bvid = data.optString("bvid")
        val aid = data.optLong("aid")
        return VideoInfo(
            bvid = bvid, aid = aid, cid = data.optLong("cid", pages.firstOrNull()?.cid ?: 0),
            title = data.optString("title", "Bilibili video"),
            owner = owner.optString("name", "未知UP主"),
            durationSeconds = data.optInt("duration"),
            coverUrl = normalizeImage(data.optString("pic")),
            pages = pages,
            description = data.optString("desc"),
            ownerMid = owner.optLong("mid"),
            viewCount = stat.optLong("view"), danmakuCount = stat.optLong("danmaku"),
            likeCount = stat.optLong("like"), coinCount = stat.optLong("coin"),
            favoriteCount = stat.optLong("favorite"), replyCount = stat.optLong("reply"),
            canonicalUrl = "https://www.bilibili.com/video/$bvid"
        )
    }

    fun getNav(cookie: String): BiliProfile? {
        val d = getData("$API/x/web-interface/nav", cookie)
        if (!d.optBoolean("isLogin")) return null
        return BiliProfile(d.optLong("mid"), d.optString("uname", "B站用户"),
            normalizeImage(d.optString("face")), d.optInt("vipType"))
    }

    private fun mixinKey(cookie: String): String {
        val d = getData("$API/x/web-interface/nav", cookie)
        val wbi = d.optJSONObject("wbi_img") ?: error("B站 WBI 签名密钥不可用")
        val source = wbi.optString("img_url").substringAfterLast('/').substringBefore('.') +
            wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
        if (source.length < 64) error("B站 WBI 签名密钥长度异常")
        return WBI_ORDER.joinToString("") { source[it].toString() }.take(32)
    }

    private fun signedPlayUrl(info: VideoInfo, qn: Int, cookie: String): JSONObject {
        val params = sortedMapOf(
            "cid" to info.cid.toString(), "qn" to qn.toString(),
            "fnval" to "4048", "fnver" to "0", "fourk" to "1",
            "wts" to (System.currentTimeMillis() / 1000).toString()
        )
        if (info.bvid.isNotBlank()) params["bvid"] = info.bvid else params["avid"] = info.aid.toString()
        val query = params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v.filterNot { it in "!'()*" })}"
        }
        val rid = md5(query + mixinKey(cookie))
        return getData("$API/x/player/wbi/playurl?$query&w_rid=$rid", cookie)
    }

    fun getPlayStreams(info: VideoInfo, cid: Long, qn: Int, cookie: String = ""): PlayStreams {
        val target = info.copy(cid = cid)
        val data = try {
            signedPlayUrl(target, qn, cookie)
        } catch (wbiError: Exception) {
            val params = "cid=$cid&qn=$qn&fnval=4048&fnver=0&fourk=1&" +
                if (target.bvid.isNotBlank()) "bvid=${encode(target.bvid)}" else "avid=${target.aid}"
            getData("$API/x/player/playurl?$params", cookie)
        }
        val dash = data.optJSONObject("dash") ?: JSONObject()
        val regularVideo = dash.optJSONArray("video") ?: JSONArray()
        val dolbyVideo = dash.optJSONObject("dolby")?.optJSONArray("video") ?: JSONArray()
        val videoCandidates = buildList {
            for (i in 0 until regularVideo.length()) regularVideo.optJSONObject(i)?.let(::add)
            for (i in 0 until dolbyVideo.length()) dolbyVideo.optJSONObject(i)?.let(::add)
        }.distinctBy { it.optInt("id") to it.optString("baseUrl", it.optString("base_url")) }
        val audioArray = dash.optJSONArray("audio") ?: JSONArray()
        val audioCandidates = (0 until audioArray.length()).mapNotNull { audioArray.optJSONObject(it) }
        val actualIds = videoCandidates.map { it.optInt("id") }.filter { it > 0 }.toSet()
        val accepted = LinkedHashMap<Int, String>()
        val ids = data.optJSONArray("accept_quality") ?: JSONArray()
        val desc = data.optJSONArray("accept_description") ?: JSONArray()
        for (i in 0 until ids.length()) {
            val id = ids.optInt(i)
            if (id > 0 && (actualIds.isEmpty() || id in actualIds)) {
                accepted[id] = desc.optString(i).ifBlank { qualityLabel(id) }
            }
        }
        videoCandidates.forEach { stream ->
            val id = stream.optInt("id")
            if (id > 0) accepted.putIfAbsent(id, qualityLabel(id))
        }
        val durl = data.optJSONArray("durl")
        val durlQuality = data.optInt("quality")
        if (videoCandidates.isEmpty() && durl != null && durl.length() > 0 && durlQuality > 0) {
            accepted[durlQuality] = qualityLabel(durlQuality)
        }
        val chosen = videoCandidates.filter { it.optInt("id") <= qn }.maxByOrNull { it.optInt("id") }
            ?: videoCandidates.minByOrNull { kotlin.math.abs(it.optInt("id") - qn) }
        val audio = audioCandidates.maxByOrNull { it.optLong("bandwidth") }
        val progressive = durl?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
        return PlayStreams(
            videoUrl = chosen?.optString("baseUrl")?.takeIf { it.isNotBlank() }
                ?: chosen?.optString("base_url")?.takeIf { it.isNotBlank() }
                ?: chosen?.optJSONArray("backupUrl")?.optString(0),
            audioUrl = audio?.optString("baseUrl")?.takeIf { it.isNotBlank() }
                ?: audio?.optString("base_url")?.takeIf { it.isNotBlank() }
                ?: audio?.optJSONArray("backupUrl")?.optString(0),
            progressiveUrl = progressive,
            acceptedQualities = accepted.entries.sortedByDescending { it.key }.map { StreamChoice(it.key, it.value) },
            videoBackups = chosen?.optJSONArray("backupUrl").toStringList(),
            audioBackups = audio?.optJSONArray("backupUrl").toStringList(),
            format = if (progressive != null) "durl" else "dash"
        )
    }

    fun getPopularVideos(page: Int = 1, pageSize: Int = 20, cookie: String = ""): List<VideoInfo> {
        val d = getData("$API/x/web-interface/popular?pn=${page.coerceAtLeast(1)}&ps=${pageSize.coerceIn(1,50)}", cookie)
        val arr = d.optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            videoFromSummary(item.optJSONObject("archive") ?: item)
        }.filter { it.bvid.isNotBlank() }
    }

    fun getComments(aid: Long, page: Int = 1, pageSize: Int = 20, cookie: String = ""): List<BiliComment> {
        val d = getData("$API/x/v2/reply?type=1&oid=$aid&pn=${page.coerceAtLeast(1)}&ps=${pageSize.coerceIn(1,49)}&sort=2", cookie)
        val arr = d.optJSONArray("replies") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val member = item.optJSONObject("member") ?: JSONObject()
            val content = item.optJSONObject("content") ?: JSONObject()
            BiliComment(item.optLong("rpid"), member.optLong("mid"), member.optString("uname"),
                normalizeImage(member.optString("avatar")), content.optString("message"),
                item.optInt("like"), item.optLong("ctime"))
        }
    }

    fun getDanmakuXml(cid: Long, cookie: String = ""): ByteArray =
        requestBytes("https://comment.bilibili.com/$cid.xml", cookie)

    fun getSubtitleTracks(info: VideoInfo, cid: Long, cookie: String = ""): List<SubtitleTrack> {
        val d = getData("$API/x/player/v2?bvid=${encode(info.bvid)}&cid=$cid", cookie)
        val arr = d.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = normalizeImage(item.optString("subtitle_url"))
            if (url.isBlank()) null else SubtitleTrack(item.optLong("id"),
                item.optString("lan_doc", item.optString("lan")), url, item.optString("author"))
        }
    }

    fun downloadSubtitle(track: SubtitleTrack, cookie: String = ""): String {
        val json = JSONObject(String(requestBytes(track.url, cookie), Charsets.UTF_8))
        val body = json.optJSONArray("body") ?: JSONArray()
        val out = StringBuilder()
        fun stamp(sec: Double): String {
            val ms = (sec.coerceAtLeast(0.0) * 1000).toLong()
            val h = ms / 3600000; val m = (ms % 3600000) / 60000
            val s = (ms % 60000) / 1000; val milli = ms % 1000
            return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
        }
        for (i in 0 until body.length()) {
            val line = body.optJSONObject(i) ?: continue
            out.append(i + 1).append('\n')
                .append(stamp(line.optDouble("from"))).append(" --> ")
                .append(stamp(line.optDouble("to"))).append('\n')
                .append(line.optString("content")).append("\n\n")
        }
        if (out.isBlank()) error("字幕内容为空")
        return out.toString()
    }

    fun getFavoriteFolders(cookie: String): List<FavoriteFolder> {
        val profile = getNav(cookie) ?: error("查看收藏夹需要登录")
        val d = getData("$API/x/v3/fav/folder/created/list-all?up_mid=${profile.uid}&type=2", cookie)
        val arr = d.optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            FavoriteFolder(item.optLong("id"), item.optString("title", "收藏夹"), item.optInt("media_count"))
        }
    }

    fun getHistory(cookie: String, maxPages: Int = 8): List<VideoInfo> {
        if (getNav(cookie) == null) error("同步观看历史需要登录")
        val output = LinkedHashMap<String, VideoInfo>()
        var max = 0L; var viewAt = 0L
        repeat(maxPages.coerceIn(1, 20)) {
            val d = getData("$API/x/web-interface/history/cursor?max=$max&view_at=$viewAt&business=archive&ps=30", cookie)
            val rows = d.optJSONArray("list") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val history = row.optJSONObject("history") ?: JSONObject()
                val bvid = row.optString("bvid", history.optString("bvid"))
                if (bvid.isBlank()) continue
                output.putIfAbsent(bvid, VideoInfo(
                    bvid, row.optLong("aid", history.optLong("oid")), history.optLong("cid"),
                    row.optString("title"), row.optString("author_name"), row.optInt("duration"),
                    normalizeImage(row.optString("cover")), emptyList()
                ))
            }
            val cursor = d.optJSONObject("cursor") ?: break
            val nextMax = cursor.optLong("max"); val nextView = cursor.optLong("view_at")
            if (rows.length() == 0 || (nextMax == max && nextView == viewAt)) return output.values.toList()
            max = nextMax; viewAt = nextView
        }
        return output.values.toList()
    }

    fun getFavoriteVideos(cookie: String, pageSize: Int = 100): List<VideoInfo> {
        val profile = getNav(cookie) ?: error("同步收藏夹需要登录")
        val folders = getData("$API/x/v3/fav/folder/created/list-all?up_mid=${profile.uid}&ps=50&pn=1", cookie)
            .optJSONArray("list") ?: JSONArray()
        val output = LinkedHashMap<String, VideoInfo>()
        for (i in 0 until minOf(folders.length(), 30)) {
            val folder = folders.optJSONObject(i) ?: continue
            val id = folder.optLong("id").takeIf { it > 0 } ?: continue
            val data = runCatching { getData("$API/x/v3/fav/resource/list?media_id=$id&pn=1&ps=${pageSize.coerceIn(1,100)}&order=mtime&type=0", cookie) }.getOrNull() ?: continue
            val medias = data.optJSONArray("medias") ?: continue
            for (j in 0 until medias.length()) {
                val item = medias.optJSONObject(j) ?: continue
                val v = videoFromSummary(item)
                if (v.bvid.isNotBlank()) output.putIfAbsent(v.bvid, v)
            }
        }
        return output.values.toList()
    }

    fun searchVideos(keyword: String, page: Int = 1, cookie: String = ""): List<VideoInfo> {
        val q = encode(keyword)
        val d = getData("$API/x/web-interface/search/type?search_type=video&keyword=$q&page=${page.coerceAtLeast(1)}", cookie)
        val arr = d.optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val bvid = item.optString("bvid")
            if (bvid.isBlank()) null else VideoInfo(bvid, item.optLong("aid"), 0,
                item.optString("title").replace(Regex("<[^>]+>"), ""), item.optString("author"),
                item.optInt("duration").let { parseDuration(it.toString()) },
                normalizeImage(item.optString("pic")), emptyList())
        }
    }

    fun likeVideo(info: VideoInfo, cookie: String, like: Boolean = true) =
        postAction("/x/web-interface/archive/like", mapOf("aid" to info.aid.toString(), "bvid" to info.bvid, "like" to if (like) "1" else "2"), cookie)

    fun coinVideo(info: VideoInfo, cookie: String, count: Int = 1, alsoLike: Boolean = false) =
        postAction("/x/web-interface/coin/add", mapOf("aid" to info.aid.toString(), "multiply" to count.coerceIn(1,2).toString(), "select_like" to if (alsoLike) "1" else "0"), cookie)

    fun favoriteVideo(info: VideoInfo, folderId: Long, cookie: String, add: Boolean = true) =
        postAction("/x/v3/fav/resource/deal", mapOf("rid" to info.aid.toString(), "type" to "2",
            "add_media_ids" to if (add) folderId.toString() else "",
            "del_media_ids" to if (add) "" else folderId.toString()), cookie)

    fun followUser(mid: Long, cookie: String, follow: Boolean = true) =
        postAction("/x/relation/modify", mapOf("fid" to mid.toString(), "act" to if (follow) "1" else "2", "re_src" to "11"), cookie)

    private fun postAction(path: String, fields: Map<String, String>, cookie: String): JSONObject {
        val csrf = normalizeCookie(cookie).split(';').map { it.trim() }.firstOrNull { it.startsWith("bili_jct=") }
            ?.substringAfter('=') ?: error("该操作需要登录授权（缺少 bili_jct）")
        val body = FormBody.Builder().apply {
            fields.forEach { (k,v) -> if (v.isNotEmpty()) add(k,v) }
            add("csrf", csrf)
        }.build()
        val req = Request.Builder().url(API + path).header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/").header("Cookie", normalizeCookie(cookie))
            .post(body).build()
        client.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("互动请求失败 HTTP ${response.code}")
            return dataOrThrow(JSONObject(raw))
        }
    }

    private fun videoFromSummary(item: JSONObject): VideoInfo {
        val owner = item.optJSONObject("owner") ?: item.optJSONObject("upper") ?: JSONObject()
        val stat = item.optJSONObject("stat") ?: JSONObject()
        val bvid = item.optString("bvid")
        return VideoInfo(bvid, item.optLong("aid", item.optLong("id")), item.optLong("cid"),
            item.optString("title").replace(Regex("<[^>]+>"), ""), owner.optString("name", item.optString("author")),
            item.optInt("duration"), normalizeImage(item.optString("pic", item.optString("cover"))),
            emptyList(), item.optString("desc"), owner.optLong("mid"), stat.optLong("view"),
            stat.optLong("danmaku"), stat.optLong("like"), stat.optLong("coin"),
            stat.optLong("favorite"), stat.optLong("reply"))
    }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun normalizeImage(s: String): String = when {
        s.startsWith("//") -> "https:$s"
        s.startsWith("http://") -> "https://${s.removePrefix("http://")}"
        else -> s
    }

    private fun parseDuration(s: String): Int {
        val p = s.split(':').mapNotNull { it.toIntOrNull() }
        return when (p.size) { 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> s.toIntOrNull() ?: 0 }
    }

    private fun qualityLabel(q: Int): String = mapOf(
        127 to "8K 超高清", 126 to "杜比视界", 125 to "HDR", 120 to "4K 超清",
        116 to "1080P60", 112 to "1080P+", 80 to "1080P 高清",
        74 to "720P60", 64 to "720P 高清", 32 to "480P 清晰", 16 to "360P 流畅", 6 to "240P"
    )[q] ?: "画质 $q"

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(Locale.US, it) }

    companion object {
        private const val API = "https://api.bilibili.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val WBI_ORDER = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,52,44,34)
    }
}
