package com.example.bilidownload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.bilidownload.api.BiliApi
import com.example.bilidownload.download.StreamDownloader
import com.example.bilidownload.security.CookieVault

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = CookieVault(this)
        val api = BiliApi()
        val downloader = StreamDownloader(this)
        setContent {
            BiliDownloadApp(
                api = api,
                downloader = downloader,
                vault = vault
            )
        }
    }
}
