package com.example.bilidownload.engine

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Small, defensive bridge between the Kotlin app and the embedded Python engine.
 * Kotlin remains the owner of UI, lifecycle, networking and download tasks.
 */
object PythonBridge {
    @Volatile
    private var available: Boolean = false

    @Synchronized
    fun initialize(context: Context) {
        if (available) return
        runCatching {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            Python.getInstance().getModule("bili_engine")
            available = true
        }.onFailure {
            available = false
        }
    }

    fun isAvailable(): Boolean = available

    /**
     * Normalize common Bilibili inputs in Python. If Python cannot start for any
     * reason, return the original input and let the existing Kotlin parser handle it.
     */
    fun normalizeVideoInput(input: String): String {
        if (!available) return input
        return runCatching {
            Python.getInstance()
                .getModule("bili_engine")
                .callAttr("normalize_video_input", input)
                .toString()
        }.getOrDefault(input)
    }
}
