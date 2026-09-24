package com.downls10

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** سجل دائم لكل روابط التنزيل التي طلبها المستخدم. */
object DownloadHistory {
    data class Entry(val url: String, val time: Long)

    private const val PREFS = "download_history_prefs"
    private const val KEY = "items"
    private const val MAX = 500

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(context: Context, url: String) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        val old = get(context).toMutableList()
        old.removeAll { it.url == clean }
        old.add(0, Entry(clean, System.currentTimeMillis()))
        while (old.size > MAX) old.removeAt(old.lastIndex)
        val arr = JSONArray()
        old.forEach { arr.put(JSONObject().apply { put("url", it.url); put("time", it.time) }) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val url = obj.optString("url")
                if (url.isNotBlank()) list.add(Entry(url, obj.optLong("time")))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) { prefs(context).edit().remove(KEY).apply() }
}
