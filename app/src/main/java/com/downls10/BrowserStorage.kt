package com.downls10

import android.content.Context
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** علامة مرجعية مع مسار المجلد. folderPath فارغ للعلامات الموجودة في الجذر. */
data class SavedLink(
    val title: String,
    val url: String,
    val folderPath: String = ""
)

/** حفظ العلامات المرجعية وسجل التصفح محلياً باستخدام SharedPreferences + JSON. */
object BrowserStorage {

    private const val PREFS = "browser_prefs"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 200

    const val BOOKMARKS_EXPORT_FILENAME = "downls10_bookmarks.html"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readList(context: Context, key: String): MutableList<SavedLink> {
        val raw = prefs(context).getString(key, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SavedLink>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SavedLink(
                        obj.optString("title"),
                        obj.optString("url"),
                        obj.optString("folderPath")
                    )
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeList(context: Context, key: String, list: List<SavedLink>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("title", it.title)
                put("url", it.url)
                put("folderPath", it.folderPath)
            })
        }
        prefs(context).edit().putString(key, arr.toString()).apply()
    }

    // ---------- العلامات المرجعية ----------

    fun getBookmarks(context: Context): List<SavedLink> = readList(context, KEY_BOOKMARKS)

    fun addBookmark(context: Context, title: String, url: String, folderPath: String = "") {
        val list = readList(context, KEY_BOOKMARKS)
        if (list.any { it.url == url }) return
        list.add(0, SavedLink(title.ifBlank { url }, url, folderPath))
        writeList(context, KEY_BOOKMARKS, list)
    }

    fun removeBookmark(context: Context, url: String) {
        val list = readList(context, KEY_BOOKMARKS).filterNot { it.url == url }
        writeList(context, KEY_BOOKMARKS, list)
    }

    fun isBookmarked(context: Context, url: String): Boolean =
        readList(context, KEY_BOOKMARKS).any { it.url == url }

    /** يدمج قائمة علامات مستوردة مع الموجودة حالياً، مع الاحتفاظ بالمجلدات. */
    fun importBookmarks(context: Context, imported: List<SavedLink>): Int {
        val list = readList(context, KEY_BOOKMARKS)
        var added = 0
        for (item in imported) {
            if (list.none { it.url == item.url }) {
                list.add(item)
                added++
            }
        }
        writeList(context, KEY_BOOKMARKS, list)
        return added
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** يبني Netscape Bookmark HTML مع المجلدات المتداخلة بصيغة متوافقة مع Chrome. */
    fun buildBookmarksHtml(context: Context): String {
        val bookmarks = getBookmarks(context)
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n")
        sb.append("<H1>Bookmarks</H1>\n")
        sb.append("<DL><p>\n")

        val groups = bookmarks.groupBy { it.folderPath.trim('/').split('/').filter { p -> p.isNotBlank() } }
        val folders = groups.keys
            .filter { it.isNotEmpty() }
            .sortedWith(compareBy({ it.size }, { it.joinToString("/") }))

        // إنشاء شجرة مجلدات من المسارات الموجودة.
        val tree = linkedMapOf<String, MutableList<SavedLink>>()
        bookmarks.filter { it.folderPath.isBlank() }.forEach { tree.getOrPut("") { mutableListOf() }.add(it) }
        for (b in bookmarks.filter { it.folderPath.isNotBlank() }) {
            val parts = b.folderPath.trim('/').split('/').filter { it.isNotBlank() }
            for (i in 1..parts.size) {
                val path = parts.take(i).joinToString("/")
                tree.getOrPut(path) { mutableListOf() }
            }
            tree.getOrPut(parts.joinToString("/")) { mutableListOf() }.add(b)
        }

        fun writeFolder(path: String, depth: Int) {
            val indent = "    ".repeat(depth)
            val direct = tree[path].orEmpty().filter { it.folderPath.trim('/').split('/').filter(String::isNotBlank).joinToString("/") == path }
            direct.forEach { b ->
                sb.append(indent).append("<DT><A HREF=\"")
                    .append(escapeHtml(b.url))
                    .append("\">")
                    .append(escapeHtml(b.title.ifBlank { b.url }))
                    .append("</A>\n")
            }

            val prefix = if (path.isEmpty()) "" else "$path/"
            val children = tree.keys
                .filter { it.startsWith(prefix) && it.removePrefix(prefix).isNotEmpty() && !it.removePrefix(prefix).contains("/") }
                .sorted()
            for (child in children) {
                val name = child.removePrefix(prefix)
                sb.append(indent).append("<DT><H3>")
                    .append(escapeHtml(name))
                    .append("</H3>\n")
                sb.append(indent).append("<DL><p>\n")
                writeFolder(child, depth + 1)
                sb.append(indent).append("</DL><p>\n")
            }
        }

        writeFolder("", 1)
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    /**
     * يقرأ Netscape/Chrome Bookmark HTML، بما فيها المجلدات المتداخلة.
     * يتعامل مع HREF باقتباس مفرد أو مزدوج وكيانات HTML.
     */
    fun parseBookmarksHtml(html: String): List<SavedLink> {
        val results = mutableListOf<SavedLink>()
        val tokenRegex = Regex(
            """(?is)<DT>\s*<H3\b[^>]*>(.*?)</H3>|<A\b[^>]*\bHREF\s*=\s*(["'])(.*?)\2[^>]*>(.*?)</A>|</DL\s*>"""
        )
        val folders = mutableListOf<String>()

        tokenRegex.findAll(html).forEach { match ->
            val whole = match.value
            when {
                whole.trimStart().startsWith("<DT", ignoreCase = true) &&
                    whole.contains("<H3", ignoreCase = true) -> {
                    val titleRaw = match.groupValues[1].replace(Regex("(?is)<[^>]*>"), "")
                    val title = Html.fromHtml(titleRaw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    if (title.isNotEmpty()) folders.add(title)
                }
                whole.trimStart().startsWith("</DL", ignoreCase = true) -> {
                    if (folders.isNotEmpty()) folders.removeAt(folders.lastIndex)
                }
                else -> {
                    val rawUrl = match.groupValues[3].trim()
                    val rawTitle = match.groupValues[4].replace(Regex("(?is)<[^>]*>"), "").trim()
                    val url = Html.fromHtml(rawUrl, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    val title = Html.fromHtml(rawTitle, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        results.add(SavedLink(title.ifBlank { url }, url, folders.joinToString("/")))
                    }
                }
            }
        }
        return results
    }

    // ---------- السجل ----------

    fun getHistory(context: Context): List<SavedLink> = readList(context, KEY_HISTORY)

    fun addHistory(context: Context, title: String, url: String) {
        val list = readList(context, KEY_HISTORY)
        list.removeAll { it.url == url }
        list.add(0, SavedLink(title.ifBlank { url }, url))
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        writeList(context, KEY_HISTORY, list)
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }
}
