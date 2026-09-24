package com.downls10

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * يسجّل المواقع التي فتحها المستخدم.
 * التخزين دائم عبر SharedPreferences، لذلك يبقى الموقع مزوراً حتى بعد إغلاق التطبيق وإعادة فتحه.
 */
object VisitedSites {
    private const val PREFS = "visited_sites_prefs"
    private const val KEY_MAP = "visits"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun readMap(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_MAP, null) ?: return JSONObject()
        return JSONObject(raw)
    }

    private fun normalize(host: String): String = host.trim().lowercase(Locale.US).removePrefix("www.").removeSuffix(".")

    fun isVisited(context: Context, host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return readMap(context).has(normalize(host))
    }

    fun recordVisit(context: Context, host: String?) {
        if (host.isNullOrBlank()) return
        val domain = normalize(host)
        if (domain.isBlank()) return

        val map = readMap(context)
        if (!map.has(domain)) {
            map.put(domain, dateFormat.format(Date()))
            // commit() هنا مقصود: نريد ضمان كتابة حالة الزيارة على القرص
            // قبل أن يغلق المستخدم التطبيق مباشرة.
            prefs(context).edit().putString(KEY_MAP, map.toString()).commit()
        }
    }

    // ---- الروابط الكاملة (تُستخدم للروابط داخل نفس الموقع) ----
    private const val KEY_URLS = "visited_urls"
    private const val MAX_URLS_PER_HOST = 500

    private fun readUrlsMap(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_URLS, null) ?: return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    /** نفس تطبيع الرابط المستخدم في JavaScript: بدون # وبدون / في النهاية */
    private fun normalizeUrl(url: String): String = url.substringBefore('#').removeSuffix("/")

    /** يسجّل الرابط الكامل كمزار (لتلوين الروابط داخل نفس الموقع) */
    fun recordUrl(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        val uri = try { android.net.Uri.parse(url) } catch (e: Exception) { return }
        if (uri.scheme != "http" && uri.scheme != "https") return
        val host = uri.host?.let { normalize(it) } ?: return
        val normalized = normalizeUrl(url)
        val root = readUrlsMap(context)
        val arr = root.optJSONArray(host) ?: JSONArray().also { root.put(host, it) }
        for (i in 0 until arr.length()) if (arr.optString(i) == normalized) return
        arr.put(normalized)
        while (arr.length() > MAX_URLS_PER_HOST) arr.remove(0)
        prefs(context).edit().putString(KEY_URLS, root.toString()).commit()
    }

    /** الروابط الكاملة المزارة لموقع معيّن، بصيغة JSON Array للحقن بالـ JavaScript */
    fun urlsForHostJson(context: Context, host: String?): String {
        if (host.isNullOrBlank()) return "[]"
        return (readUrlsMap(context).optJSONArray(normalize(host)) ?: JSONArray()).toString()
    }

    /** يرجع كل النطاقات المزارة مع تاريخ أول زيارة، بصيغة JSON جاهزة للحقن بالـ JavaScript */
    fun asJsonForInjection(context: Context): String = readMap(context).toString()
}
