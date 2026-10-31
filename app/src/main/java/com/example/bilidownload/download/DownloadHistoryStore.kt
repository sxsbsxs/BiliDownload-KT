package com.example.bilidownload.download

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

data class DownloadHistoryEntry(
    val taskId: String,
    val title: String,
    val mode: String,
    val files: List<String>,
    val createdAt: Long
)

class DownloadHistoryStore(context: Context) : SQLiteOpenHelper(context, "download-history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE downloads (task_id TEXT PRIMARY KEY, title TEXT NOT NULL, mode TEXT NOT NULL, files TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX downloads_created_at ON downloads(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun record(taskId: String, title: String, mode: String, files: List<String>, createdAt: Long) {
        val values = android.content.ContentValues().apply {
            put("task_id", taskId.ifBlank { "legacy-$createdAt" })
            put("title", title)
            put("mode", mode)
            put("files", JSONArray(files).toString())
            put("created_at", createdAt)
        }
        writableDatabase.insertWithOnConflict("downloads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun list(): List<DownloadHistoryEntry> {
        val result = mutableListOf<DownloadHistoryEntry>()
        readableDatabase.query("downloads", arrayOf("task_id", "title", "mode", "files", "created_at"),
            null, null, null, null, "created_at DESC", "200").use { cursor ->
            while (cursor.moveToNext()) {
                val names = JSONArray(cursor.getString(3))
                result += DownloadHistoryEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    (0 until names.length()).map { names.optString(it) }, cursor.getLong(4))
            }
        }
        return result
    }

    @Synchronized
    fun delete(taskId: String) {
        writableDatabase.delete("downloads", "task_id = ?", arrayOf(taskId))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("downloads", null, null)
    }
}
