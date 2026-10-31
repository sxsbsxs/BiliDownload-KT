package com.example.bilidownload.engine

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Embedded Python adapter.
 *
 * Kotlin remains responsible for Android lifecycle, UI, network requests, cookies,
 * persistent download work and file access. Python provides deterministic parsing
 * helpers and can be extended without moving privileged account state out of Kotlin.
 */
object PythonBridge {
    @Volatile
    private var started = false

    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        runCatching {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            Python.getInstance().getModule("bili_engine")
            started = true
        }.onFailure {
            started = false
        }
    }

    fun isAvailable(): Boolean = started

    /**
     * Convert a BV/AV id or supported Bilibili URL to a canonical identifier.
     * If the embedded runtime is unavailable, preserve the input so the existing
     * Kotlin parser can still handle it.
     */
    fun normalizeVideoInput(input: String): String {
        if (!started) return input.trim()
        return runCatching {
            Python.getInstance()
                .getModule("bili_engine")
                .callAttr("normalize_video_input", input)
                .toString()
        }.getOrDefault(input.trim())
    }

    fun summarizePlayurlJson(rawJson: String): String? {
        if (!started) return null
        return runCatching {
            Python.getInstance()
                .getModule("bili_engine")
                .callAttr("summarize_playurl_json", rawJson)
                .toString()
        }.getOrNull()
    }
}
