package com.example.bilidownload.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uses Bilibili's own web QR-ticket handoff: open the ticket URL with ACTION_VIEW so
 * Android can route it to the installed Bilibili app. No QR image is rendered in this app.
 * Login is accepted only after /x/web-interface/nav confirms isLogin=true.
 */
class BiliLoginManager {
    data class Ticket(val url: String, val key: String)
    data class LoginResult(val cookie: String, val uid: Long, val name: String, val avatar: String, val vipType: Int)

    private val savedCookies = mutableMapOf<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val old = savedCookies[url.host].orEmpty().associateBy { it.name }.toMutableMap()
            cookies.forEach { old[it.name] = it }
            savedCookies[url.host] = old.values.toList()
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            savedCookies.entries.flatMap { it.value }.filter { it.matches(url) }
    }
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()

    fun createTicket(): Ticket {
        val request = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
            .header("User-Agent", UA).header("Referer", "https://www.bilibili.com/").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("申请 B 站 App 登录授权失败：HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.optInt("code") != 0) error(json.optString("message", "B站未返回授权链接"))
            val data = json.optJSONObject("data") ?: error("B站授权响应缺少数据")
            val url = data.optString("url")
            val key = data.optString("qrcode_key")
            if (url.isBlank() || key.isBlank()) error("B站没有返回有效的登录票据")
            return Ticket(url, key)
        }
    }

    fun poll(ticket: Ticket): LoginResult? {
        val request = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=${ticket.key}")
            .header("User-Agent", UA).header("Referer", "https://www.bilibili.com/")
            .header("Accept", "application/json, text/plain, */*").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("查询 B 站 App 授权状态失败：HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val data = json.optJSONObject("data") ?: JSONObject()
            when (data.optInt("code", json.optInt("code", -1))) {
                86101 -> return null
                86090 -> return null
                86038 -> error("B站登录授权已过期，请重新点击登录")
                0 -> {
                    val url = "https://www.bilibili.com/".toHttpUrl()
                    val cookies = cookieJar.loadForRequest(url)
                    val cookie = cookies.filter { it.name in setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid") }
                        .joinToString("; ") { "${it.name}=${it.value}" }
                    if (!cookie.contains("SESSDATA=")) error("B站确认了授权，但没有返回登录凭据")
                    val navRequest = Request.Builder().url("https://api.bilibili.com/x/web-interface/nav")
                        .header("User-Agent", UA).header("Referer", "https://www.bilibili.com/")
                        .header("Cookie", cookie).get().build()
                    client.newCall(navRequest).execute().use { navResponse ->
                        if (!navResponse.isSuccessful) error("B站登录态校验失败：HTTP ${navResponse.code}")
                        val nav = JSONObject(navResponse.body?.string().orEmpty())
                        val profile = nav.optJSONObject("data") ?: JSONObject()
                        if (nav.optInt("code") != 0 || !profile.optBoolean("isLogin")) error("B站没有确认登录状态")
                        return LoginResult(cookie, profile.optLong("mid"), profile.optString("uname", "B站用户"),
                            profile.optString("face"), profile.optInt("vipType"))
                    }
                }
                else -> error(data.optString("message", "B站登录状态异常"))
            }
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}
