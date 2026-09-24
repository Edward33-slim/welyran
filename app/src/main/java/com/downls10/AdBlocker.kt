package com.downls10

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

enum class ListFormat { HOSTS, ABP, DOMAIN_PLAIN, URL_LIST }
enum class ListCategory { AD, PORN, MALWARE, PHISHING, RISK }

data class BlockListDef(
    val id: String,
    val displayName: String,
    val url: String,
    val format: ListFormat,
    val category: ListCategory,
    val fallbackUrl: String? = null
)

/**
 * محرك عام لإدارة عدة قوائم حجب مستقلة (إعلانات + طرف أول + أمان تصفح + محتوى إباحي).
 * كل قائمة: تشغيل/إيقاف مستقل تماماً + تحديث مستقل + تخزين محلي مستقل.
 * قائمة معطّلة = صفر تأثير إطلاقاً على الفحص. قائمة مفعّلة = تُفحص فوراً حسب وظيفتها.
 * لا يعتمد على أي مكتبة خارجية (HttpURLConnection فقط).
 */
object AdBlocker {

    val LISTS = listOf(
        BlockListDef("stevenblack", "StevenBlack", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", ListFormat.HOSTS, ListCategory.AD),
        BlockListDef("ubo_filters", "uBlock Origin - Filters", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt", ListFormat.ABP, ListCategory.AD),
        BlockListDef("ubo_badware", "uBlock Origin - Badware", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt", ListFormat.ABP, ListCategory.AD),
        BlockListDef("ubo_privacy", "uBlock Origin - Privacy", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt", ListFormat.ABP, ListCategory.AD),
        BlockListDef("ubo_resource_abuse", "uBlock Origin - Resource Abuse", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/resource-abuse.txt", ListFormat.ABP, ListCategory.AD),
        BlockListDef("ubo_unbreak", "uBlock Origin - Unbreak", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt", ListFormat.ABP, ListCategory.AD),
        BlockListDef("adguard_base", "AdGuard Base Filter", "https://filters.adtidy.org/extension/chromium/filters/2.txt", ListFormat.ABP, ListCategory.AD),
        BlockListDef("pgl", "PGL (Peter Lowe)", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", ListFormat.HOSTS, ListCategory.AD),
        BlockListDef("nocoin", "NoCoin", "https://raw.githubusercontent.com/hoshsadiq/adblock-nocoin-list/master/hosts.txt", ListFormat.HOSTS, ListCategory.AD),
        BlockListDef("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", ListFormat.ABP, ListCategory.AD),
        BlockListDef(
            "adguard_cname", "AdGuard - متتبعات الطرف الأول (CNAME)",
            "https://raw.githubusercontent.com/AdguardTeam/cname-trackers/master/data/combined_disguised_trackers_justdomains.txt",
            ListFormat.DOMAIN_PLAIN, ListCategory.AD,
            fallbackUrl = "https://cdn.jsdelivr.net/gh/AdguardTeam/cname-trackers@master/data/combined_disguised_trackers_justdomains.txt"
        ),
        BlockListDef(
            "nextdns_cname", "NextDNS - حجب تمويه الطرف الأول",
            "https://raw.githubusercontent.com/nextdns/cname-cloaking-blocklist/master/domains",
            ListFormat.DOMAIN_PLAIN, ListCategory.AD,
            fallbackUrl = "https://cdn.jsdelivr.net/gh/nextdns/cname-cloaking-blocklist@master/domains"
        ),
        BlockListDef(
            "frogeye_firstparty", "Frogeye - تتبعات الطرف الأول",
            "https://hostfiles.frogeye.fr/firstparty-trackers-hosts.txt",
            ListFormat.HOSTS, ListCategory.AD
        ),
        BlockListDef("porn", "حجب المواقع الإباحية", "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts", ListFormat.HOSTS, ListCategory.PORN),
        BlockListDef("malware", "برمجيات خبيثة (URLhaus)", "https://urlhaus.abuse.ch/downloads/hostfile/", ListFormat.HOSTS, ListCategory.MALWARE),
        BlockListDef("phishing", "تصيّد احتيالي (OpenPhish)", "https://openphish.com/feed.txt", ListFormat.URL_LIST, ListCategory.PHISHING),
        BlockListDef("risk", "مواقع مشبوهة (Risk)", "https://raw.githubusercontent.com/FadeMind/hosts.extras/master/add.Risk/hosts", ListFormat.HOSTS, ListCategory.RISK)
    )

    private const val PREFS = "adblock_prefs"
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache = mutableMapOf<String, MutableSet<String>>()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** قائمة معطّلة = صفر تأثير، تُتجاهل تماماً بكل الفحوصات */
    fun isEnabled(context: Context, id: String): Boolean =
        prefs(context).getBoolean("enabled_$id", true)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled_$id", enabled).apply()
    }

    fun lastUpdateText(context: Context, id: String): String {
        val ts = prefs(context).getLong("updated_$id", 0L)
        if (ts == 0L) return "لم يتم التحديث بعد"
        val diffMin = (System.currentTimeMillis() - ts) / 60000
        return when {
            diffMin < 1 -> "آخر تحديث: الآن"
            diffMin < 60 -> "آخر تحديث: قبل $diffMin دقيقة"
            else -> "آخر تحديث: قبل ${diffMin / 60} ساعة"
        }
    }

    fun ruleCount(context: Context, id: String): Int = domainsFor(context, id).size

    private fun domainsFor(context: Context, id: String): MutableSet<String> {
        cache[id]?.let { return it }
        synchronized(this) {
            cache[id]?.let { return it }
            val cached = prefs(context).getStringSet("domains_$id", null)
            val set = if (cached.isNullOrEmpty()) mutableSetOf() else cached.toMutableSet()
            cache[id] = set
            return set
        }
    }

    /** onResult(success, count, errorMessage) - errorMessage فارغ عند النجاح */
    fun updateList(context: Context, id: String, onResult: (Boolean, Int, String) -> Unit) {
        val def = LISTS.find { it.id == id } ?: return
        val appContext = context.applicationContext
        executor.execute {
            var parsed: Set<String>? = null
            var errorMsg = ""

            val primaryResult = fetchAndParse(def.url, def.format)
            if (primaryResult.first != null) {
                parsed = primaryResult.first
            } else {
                errorMsg = primaryResult.second
                if (def.fallbackUrl != null) {
                    val fallbackResult = fetchAndParse(def.fallbackUrl, def.format)
                    if (fallbackResult.first != null) {
                        parsed = fallbackResult.first
                        errorMsg = ""
                    } else {
                        errorMsg = "فشل المصدر الأساسي والاحتياطي: ${fallbackResult.second}"
                    }
                }
            }

            mainHandler.post {
                val result = parsed
                if (result != null && result.isNotEmpty()) {
                    val set = domainsFor(appContext, id)
                    set.clear()
                    set.addAll(result)
                    prefs(appContext).edit()
                        .putStringSet("domains_$id", set)
                        .putLong("updated_$id", System.currentTimeMillis())
                        .apply()
                    onResult(true, set.size, "")
                } else {
                    onResult(false, domainsFor(appContext, id).size, errorMsg.ifBlank { "فشل التحديث لسبب غير معروف" })
                }
            }
        }
    }

    /** يرجع Pair(النطاقات أو null عند الفشل، رسالة الخطأ) */
    private fun fetchAndParse(urlStr: String, format: ListFormat): Pair<Set<String>?, String> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return Pair(null, "رمز استجابة غير متوقع: $code")
            }

            val sb = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
            }
            val parsed = parseByFormat(sb.toString(), format)
            if (parsed.isEmpty()) Pair(null, "الملف تم تحميله لكن لم يُستخرج منه أي نطاقات (صيغة غير متوقعة)")
            else Pair(parsed, "")
        } catch (e: java.net.UnknownHostException) {
            Pair(null, "تعذّر الوصول للمصدر - تأكد من اتصال الإنترنت")
        } catch (e: java.net.SocketTimeoutException) {
            Pair(null, "انتهت مهلة الاتصال بالمصدر")
        } catch (e: Exception) {
            Pair(null, "خطأ: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseByFormat(content: String, format: ListFormat): Set<String> {
        val out = mutableSetOf<String>()
        when (format) {
            ListFormat.HOSTS -> content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                    val domain = parts[1].trim().lowercase()
                    if (domain.isNotEmpty() && domain != "localhost" && domain.contains(".")) {
                        out.add(domain)
                    }
                }
            }
            ListFormat.DOMAIN_PLAIN -> content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim().lowercase()
                if (line.isNotEmpty() && !line.startsWith("#") && line.contains(".") && !line.contains(" ")) {
                    out.add(line)
                }
            }
            ListFormat.ABP -> content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") ||
                    line.startsWith("@@") || line.contains("##") || line.contains("#@#")
                ) return@forEach
                if (line.startsWith("||")) {
                    var domainPart = line.removePrefix("||")
                    val endIdx = domainPart.indexOfFirst { it == '^' || it == '/' || it == '$' || it == '*' }
                    if (endIdx != -1) domainPart = domainPart.substring(0, endIdx)
                    domainPart = domainPart.trim().lowercase()
                    if (domainPart.isNotEmpty() && domainPart.contains(".") && !domainPart.contains(" ")) {
                        out.add(domainPart)
                    }
                }
            }
            ListFormat.URL_LIST -> content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                try {
                    val host = URI(line).host
                    if (!host.isNullOrEmpty()) out.add(host.lowercase())
                } catch (e: Exception) { /* تجاهل السطر */ }
            }
        }
        return out
    }

    private fun isHostInDomainSet(host: String, domains: Set<String>): Boolean {
        var h = host.lowercase()
        if (h.startsWith("www.")) h = h.substring(4)
        if (domains.contains(h)) return true
        var idx = h.indexOf('.')
        while (idx != -1) {
            if (domains.contains(h.substring(idx + 1))) return true
            idx = h.indexOf('.', idx + 1)
        }
        return false
    }

    /**
     * يفحص إذا نطاق محجوب بأي قائمة إعلانات. قائمة معطّلة تُتجاهل كلياً (كأنها غير موجودة)،
     * قائمة مفعّلة تُفحص فوراً حسب نطاقاتها المحدّثة.
     */
    fun isAdBlocked(context: Context, host: String): Boolean {
        for (def in LISTS) {
            if (def.category != ListCategory.AD) continue
            if (!isEnabled(context, def.id)) continue
            if (isHostInDomainSet(host, domainsFor(context, def.id))) return true
        }
        return false
    }

    /** يفحص تصنيف نطاق الصفحة كاملة (إباحي/ضار/احتيالي/مشبوه) - أول قائمة مطابقة مفعّلة فقط */
    fun matchSafetyCategory(context: Context, host: String): BlockListDef? {
        for (def in LISTS) {
            if (def.category == ListCategory.AD) continue
            if (!isEnabled(context, def.id)) continue
            if (isHostInDomainSet(host, domainsFor(context, def.id))) return def
        }
        return null
    }

    private val blockedResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** يستخدم داخل WebViewClient.shouldInterceptRequest لحجب موارد الإعلانات الفرعية */
    fun maybeBlock(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val host = request.url.host ?: return null
        return if (isAdBlocked(context, host)) blockedResponse else null
    }
}
