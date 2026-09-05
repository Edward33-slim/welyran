package com.downloads10.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DownloadStore {
    private const val PREF = "downloads10_store"
    private const val KEY_ITEMS = "items"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_SPEED = "show_speed"
    private const val KEY_CONCURRENT = "concurrent"
    private const val KEY_RETRY = "retry"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized fun all(c: Context): MutableList<DownloadItem> {
        val raw = prefs(c).getString(KEY_ITEMS, "[]") ?: "[]"
        val a = JSONArray(raw)
        val out = mutableListOf<DownloadItem>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out += DownloadItem(
                id = o.getLong("id"), url = o.getString("url"), fileName = o.getString("fileName"),
                size = o.optLong("size", -1), downloaded = o.optLong("downloaded", 0),
                speed = o.optLong("speed", 0), status = runCatching { DownloadItem.Status.valueOf(o.optString("status")) }.getOrDefault(DownloadItem.Status.QUEUED),
                error = o.optString("error"), createdAt = o.optLong("createdAt", 0), localUri = o.optString("localUri")
            )
        }
        return out.sortedByDescending { it.createdAt }.toMutableList()
    }

    @Synchronized fun save(c: Context, items: List<DownloadItem>) {
        val a = JSONArray()
        items.forEach { d ->
            a.put(JSONObject().apply {
                put("id", d.id); put("url", d.url); put("fileName", d.fileName); put("size", d.size)
                put("downloaded", d.downloaded); put("speed", d.speed); put("status", d.status.name)
                put("error", d.error); put("createdAt", d.createdAt); put("localUri", d.localUri)
            })
        }
        prefs(c).edit().putString(KEY_ITEMS, a.toString()).apply()
    }

    fun folder(c: Context): String? = prefs(c).getString(KEY_FOLDER, null)
    fun setFolder(c: Context, uri: String) = prefs(c).edit().putString(KEY_FOLDER, uri).apply()
    fun showSpeed(c: Context): Boolean = prefs(c).getBoolean(KEY_SPEED, true)
    fun setShowSpeed(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_SPEED, v).apply()
    fun concurrent(c: Context): Int = prefs(c).getInt(KEY_CONCURRENT, 3).coerceIn(1, 10)
    fun setConcurrent(c: Context, v: Int) = prefs(c).edit().putInt(KEY_CONCURRENT, v.coerceIn(1, 10)).apply()
    fun retry(c: Context): Boolean = prefs(c).getBoolean(KEY_RETRY, true)
    fun setRetry(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_RETRY, v).apply()
}
