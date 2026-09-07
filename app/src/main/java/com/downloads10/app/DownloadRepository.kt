package com.downloads10.app

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object DownloadRepository {
    private const val PREF = "downloads10_repo"
    private const val KEY = "items"
    private val items = CopyOnWriteArrayList<DownloadItem>()
    private val idCounter = AtomicLong(System.currentTimeMillis())

    @Synchronized
    fun load(context: Context) {
        if (items.isNotEmpty()) return
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    DownloadItem(
                        id = o.getLong("id"),
                        url = o.getString("url"),
                        fileName = o.getString("fileName"),
                        totalBytes = o.optLong("totalBytes", -1),
                        downloadedBytes = o.optLong("downloadedBytes", 0),
                        status = o.optString("status", DownloadItem.STATUS_QUEUED),
                        speedBytes = o.optLong("speedBytes", 0),
                        filePath = o.optString("filePath", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        error = o.optString("error", "")
                    )
                )
                idCounter.set(maxOf(idCounter.get(), o.getLong("id") + 1))
            }
        }
    }

    @Synchronized
    fun save(context: Context) {
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(JSONObject().apply {
                put("id", i.id)
                put("url", i.url)
                put("fileName", i.fileName)
                put("totalBytes", i.totalBytes)
                put("downloadedBytes", i.downloadedBytes)
                put("status", i.status)
                put("speedBytes", i.speedBytes)
                put("filePath", i.filePath)
                put("createdAt", i.createdAt)
                put("error", i.error)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun all(): List<DownloadItem> =
        items.sortedByDescending { it.createdAt }.map { it.copy() }

    fun find(id: Long): DownloadItem? = items.find { it.id == id }

    @Synchronized
    fun add(context: Context, url: String, name: String? = null): DownloadItem {
        load(context)
        val fileName = sanitizeName(name ?: URLConnection.guessContentTypeFromName(url)?.let {
            "download_${System.currentTimeMillis()}"
        } ?: url.substringAfterLast('/').substringBefore('?').ifBlank {
            "download_${System.currentTimeMillis()}"
        })
        val item = DownloadItem(idCounter.getAndIncrement(), url, fileName)
        items.add(item)
        save(context)
        return item
    }

    @Synchronized
    fun update(context: Context, id: Long, block: (DownloadItem) -> Unit) {
        val item = items.find { it.id == id } ?: return
        block(item)
        save(context)
    }

    @Synchronized
    fun remove(context: Context, id: Long, deleteFile: Boolean) {
        val item = items.find { it.id == id } ?: return
        if (deleteFile && item.filePath.isNotBlank()) File(item.filePath).delete()
        items.removeIf { it.id == id }
        save(context)
    }

    fun outputFile(context: Context, item: DownloadItem): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, sanitizeName(item.fileName))
    }

    private fun sanitizeName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").take(180).ifBlank {
            "download_${System.currentTimeMillis()}"
        }
    }
}
