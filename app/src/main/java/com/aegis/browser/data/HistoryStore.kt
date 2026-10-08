package com.aegis.browser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Browsing history persisted as a JSON array in filesDir/history.json.
 * Entries are kept newest-first. Recorded from MainActivity.onPageFinished.
 */
data class HistoryEntry(
    val title: String,
    val url: String,
    val timestamp: Long
)

class HistoryStore(private val context: Context) {

    companion object {
        private const val FILE_NAME = "history.json"
        private const val MAX_ENTRIES = 500
    }

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** Record a visit. Consecutive duplicates (same URL as the newest entry) are skipped. */
    fun record(title: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank() || cleanUrl == "about:blank") return
        val entries = load().toMutableList()
        if (entries.isNotEmpty() && entries[0].url == cleanUrl) return
        entries.add(
            0,
            HistoryEntry(
                title = title.ifBlank { cleanUrl },
                url = cleanUrl,
                timestamp = System.currentTimeMillis()
            )
        )
        save(entries.take(MAX_ENTRIES))
    }

    /** All entries, newest first. */
    fun all(): List<HistoryEntry> = load()

    fun clear() {
        save(emptyList())
    }

    private fun load(): List<HistoryEntry> {
        return try {
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else HistoryEntry(
                    title = o.optString("title"),
                    url = url,
                    timestamp = o.optLong("timestamp")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(entries: List<HistoryEntry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject()
                        .put("title", e.title)
                        .put("url", e.url)
                        .put("timestamp", e.timestamp)
                )
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // History must never crash the browser; a failed write is simply lost.
        }
    }
}
