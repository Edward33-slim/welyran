package com.downls10

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** يحفظ قائمة التنزيلات بشكل دائم حتى تبقى موجودة بعد إغلاق التطبيق تماماً */
object DownloadPersistence {
    private const val PREFS = "downloads_prefs"
    private const val KEY_ITEMS = "items"

    /** معرّفات التنزيلات التي كانت جارية عندما أُنهيت العملية (تُستخدم لاستئنافها تلقائياً من الخدمة) */
    @Volatile
    var interruptedIds: Set<Long> = emptySet()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, items: List<DownloadItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("url", item.url)
                put("fileName", item.fileName)
                put("extension", item.extension)
                put("progress", item.progress)
                put("status", item.status)
                put("speed", item.speed)
                put("state", item.state.name)
                put("downloadedBytes", item.downloadedBytes)
                put("totalBytes", item.totalBytes)
                put("localUri", item.localUri ?: "")
            })
        }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun load(context: Context): List<DownloadItem> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<DownloadItem>()
        val interrupted = mutableSetOf<Long>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val savedState = try {
                DownloadState.valueOf(obj.optString("state", "PAUSED"))
            } catch (_: Exception) {
                DownloadState.PAUSED
            }
            if (savedState == DownloadState.DOWNLOADING) interrupted.add(obj.optLong("id"))
            val state = if (savedState == DownloadState.DOWNLOADING) DownloadState.PAUSED else savedState
            val status = if (savedState == DownloadState.DOWNLOADING) "متوقف مؤقتاً" else obj.optString("status")
            list.add(
                DownloadItem(
                    id = obj.optLong("id"),
                    url = obj.optString("url"),
                    fileName = obj.optString("fileName"),
                    extension = obj.optString("extension"),
                    progress = obj.optInt("progress"),
                    status = status,
                    speed = "0 KB/s",
                    state = state,
                    downloadedBytes = obj.optLong("downloadedBytes"),
                    totalBytes = obj.optLong("totalBytes"),
                    localUri = obj.optString("localUri").takeIf { it.isNotBlank() }
                )
            )
        }
        interruptedIds = interrupted
        return list
    }
}
