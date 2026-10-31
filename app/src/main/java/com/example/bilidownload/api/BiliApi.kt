package com.example.bilidownload.api

import com.example.bilidownload.model.PlayStreams
import com.example.bilidownload.model.StreamChoice
import com.example.bilidownload.model.VideoInfo
import com.example.bilidownload.model.VideoPage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class BiliApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getVideoInfo(input: String, cookie: String): VideoInfo {
        val bvid = extractBvid(input)
        val json = getJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", cookie)
        ensureSuccess(json)
        val data = json.getJSONObject("data")
        val pagesJson = data.optJSONArray("pages")
        val pages = mutableListOf<VideoPage>()
        if (pagesJson != null) {
            for (i in 0 until pagesJson.length()) {
                val p = pagesJson.getJSONObject(i)
                pages += VideoPage(
                    cid = p.getLong("cid"),
                    page = p.optInt("page", i + 1),
                    part = p.optString("part", "P${i + 1}")
                )
            }
        }
        val firstCid = data.optLong("cid", pages.firstOrNull()?.cid ?: 0L)
        return VideoInfo(
            bvid = data.getString("bvid"),
            aid = data.getLong("aid"),
            cid = firstCid,
            title = data.optString("title", "Bilibili video"),
            owner = data.optJSONObject("owner")?.optString("name").orEmpty(),
            durationSeconds = data.optInt("duration", 0),
            coverUrl = data.optString("pic", "").replace("http://", "https://"),
            pages = pages
        )
    }

    fun getPlayStreams(info: VideoInfo, cid: Long, qn: Int, cookie: String): PlayStreams {
        val nav = getJson("https://api.bilibili.com/x/web-interface/nav", cookie)
        ensureSuccess(nav)
        val wbi = nav.getJSONObject("data").getJSONObject("wbi_img")
        val imgKey = wbi.getString("img_url").substringAfterLast('/').substringBefore('.')
        val subKey = wbi.getString("sub_url").substringAfterLast('/').substringBefore('.')
        val mixinKey = getMixinKey(imgKey + subKey)

        val params = sortedMapOf(
            "avid" to info.aid.toString(),
            "cid" to cid.toString(),
            "qn" to qn.toString(),
            "fnval" to "4048",
            "fnver" to "0",
            "fourk" to "1",
            "otype" to "json",
            "platform" to "html5"
        )
        val wts = (System.currentTimeMillis() / 1000).toString()
        params["wts"] = wts
        val query = params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v.filterNot { it in "!'\u0028\u0029*" })}"
        }
        val wRid = md5(query + mixinKey)
        val signedUrl = "https://api.bilibili.com/x/player/wbi/playurl?$query&w_rid=$wRid"
        val json = getJson(signedUrl, cookie)
        ensureSuccess(json)
        val data = json.getJSONObject("data")
        val accepted = mutableListOf<StreamChoice>()
        val acceptedArray = data.optJSONArray("accept_quality")
        val descriptions = data.optJSONArray("accept_description")
        if (acceptedArray != null) {
            for (i in 0 until acceptedArray.length()) {
                accepted += StreamChoice(
                    acceptedArray.optInt(i),
                    descriptions?.optString(i).orEmpty().ifBlank { "画质 ${acceptedArray.optInt(i)}" }
                )
            }
        }
        val dash = data.optJSONObject("dash")
        val video = dash?.optJSONArray("video")?.let { arr ->
            val candidates = (0 until arr.length()).map { arr.getJSONObject(it) }
            // Prefer the requested quality; if unavailable, choose the closest lower quality.
            candidates.filter { it.optInt("id", 0) <= qn }
                .maxByOrNull { it.optInt("id", 0) }
                ?: candidates.minByOrNull { kotlin.math.abs(it.optInt("id", 0) - qn) }
        }
        val audio = dash?.optJSONArray("audio")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .maxByOrNull { it.optInt("bandwidth", 0) }
        }
        val durl = data.optJSONArray("durl")?.optJSONObject(0)
        return PlayStreams(
            videoUrl = video?.optString("baseUrl")?.takeIf { it.isNotBlank() }
                ?: video?.optJSONArray("backupUrl")?.optString(0)?.takeIf { it.isNotBlank() },
            audioUrl = audio?.optString("baseUrl")?.takeIf { it.isNotBlank() }
                ?: audio?.optJSONArray("backupUrl")?.optString(0)?.takeIf { it.isNotBlank() },
            progressiveUrl = durl?.optString("url")?.takeIf { it.isNotBlank() },
            acceptedQualities = accepted
        )
    }

    private fun getJson(url: String, cookie: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(180)}")
            return JSONObject(body)
        }
    }

    private fun ensureSuccess(json: JSONObject) {
        val code = json.optInt("code", -1)
        if (code != 0) {
            val message = json.optString("message", json.optString("msg", "Bilibili API error"))
            error("B站接口错误 ($code)：$message")
        }
    }

    private fun extractBvid(input: String): String {
        val match = Regex("BV[0-9A-Za-z]{10}").find(input.trim())
            ?: error("没有识别到 BV 号，请输入完整 B站视频链接或 BV 号")
        return match.value
    }

    private fun getMixinKey(orig: String): String {
        // Bilibili's published 64-index permutation.
        val table = intArrayOf(
            46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,52,44,34
        )
        return table.joinToString("") { index -> orig.getOrElse(index) { ' ' }.toString() }
            .take(32)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(Locale.US, it) }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"
    }
}
