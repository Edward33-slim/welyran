package com.downls10

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SpeedDialItem(val id: Long, val title: String, val url: String)

/** يخزّن مربعات صفحة البداية (Speed Dial) محلياً */
object SpeedDialStorage {
    private const val PREFS = "speeddial_prefs"
    private const val KEY_ITEMS = "items"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getItems(context: Context): List<SpeedDialItem> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<SpeedDialItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(SpeedDialItem(obj.optLong("id"), obj.optString("title"), obj.optString("url")))
        }
        return list
    }

    private fun saveItems(context: Context, items: List<SpeedDialItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("url", it.url)
            })
        }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun addItem(context: Context, title: String, url: String) {
        val list = getItems(context).toMutableList()
        list.add(SpeedDialItem(System.currentTimeMillis(), title, url))
        saveItems(context, list)
    }

    fun renameItem(context: Context, id: Long, newTitle: String) {
        val list = getItems(context).map { if (it.id == id) it.copy(title = newTitle) else it }
        saveItems(context, list)
    }

    fun removeItem(context: Context, id: Long) {
        val list = getItems(context).filterNot { it.id == id }
        saveItems(context, list)
    }
}
