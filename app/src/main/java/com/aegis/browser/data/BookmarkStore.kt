package com.aegis.browser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bookmarks persisted as JSON in filesDir/bookmarks.json:
 * { "folders": ["General", ...], "items": [{title, url, folder, timestamp}, ...] }
 */
data class Bookmark(
    val title: String,
    val url: String,
    val folder: String,
    val timestamp: Long
)

class BookmarkStore(private val context: Context) {

    companion object {
        const val DEFAULT_FOLDER = "General"
        private const val FILE_NAME = "bookmarks.json"
    }

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun folders(): List<String> {
        val arr = doc().optJSONArray("folders") ?: return listOf(DEFAULT_FOLDER)
        val names = (0 until arr.length())
            .map { arr.optString(it) }
            .filter { it.isNotBlank() }
        return names.ifEmpty { listOf(DEFAULT_FOLDER) }
    }

    /** Returns false when the name is blank or the folder already exists. */
    fun addFolder(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val d = doc()
        val arr = d.optJSONArray("folders") ?: JSONArray()
        val existing = (0 until arr.length()).map { arr.optString(it) }
        if (existing.any { it.equals(clean, ignoreCase = true) }) return false
        arr.put(clean)
        d.put("folders", arr)
        return write(d)
    }

    /** Adds a bookmark; an existing entry for the same URL is replaced. */
    fun add(title: String, url: String, folder: String = DEFAULT_FOLDER) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val d = doc()
        val arr = d.optJSONArray("items") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("url") != cleanUrl) kept.put(o)
        }
        kept.put(
            JSONObject()
                .put("title", title.ifBlank { cleanUrl })
                .put("url", cleanUrl)
                .put("folder", folder.ifBlank { DEFAULT_FOLDER })
                .put("timestamp", System.currentTimeMillis())
        )
        d.put("items", kept)
        write(d)
    }

    fun remove(url: String, folder: String) {
        val d = doc()
        val arr = d.optJSONArray("items") ?: return
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!(o.optString("url") == url && o.optString("folder") == folder)) kept.put(o)
        }
        d.put("items", kept)
        write(d)
    }

    fun items(folder: String): List<Bookmark> = all().filter { it.folder == folder }

    fun all(): List<Bookmark> {
        val arr = doc().optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) null
            else Bookmark(
                title = o.optString("title"),
                url = url,
                folder = o.optString("folder", DEFAULT_FOLDER).ifBlank { DEFAULT_FOLDER },
                timestamp = o.optLong("timestamp")
            )
        }.sortedByDescending { it.timestamp }
    }

    private fun doc(): JSONObject {
        return try {
            if (!file.exists()) fresh() else JSONObject(file.readText())
        } catch (e: Exception) {
            fresh()
        }
    }

    private fun fresh(): JSONObject = JSONObject()
        .put("folders", JSONArray().put(DEFAULT_FOLDER))
        .put("items", JSONArray())

    private fun write(d: JSONObject): Boolean {
        return try {
            file.writeText(d.toString())
            true
        } catch (e: Exception) {
            false
        }
    }
}
