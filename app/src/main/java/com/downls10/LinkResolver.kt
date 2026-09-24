package com.downls10

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** رابط تنزيل مباشر (مؤقتاً غالباً) تم الحصول عليه من واجهة الموقع. megaKey للملفات المشفّرة في Mega. */
class RemoteLink(
    val directUrl: String,
    val fileName: String?,
    val megaKey: ByteArray? = null
)

/**
 * يحوّل روابط المشاركة المعروفة (Google Drive وDropbox وOneDrive وGitHub وMega وغيرها) إلى روابط تنزيل مباشرة،
 * ويستخرج رابط التنزيل الحقيقي من صفحات الوسيط (تحذير الملفات الكبيرة في Drive، وصفحات MediaFire).
 */
object LinkResolver {

    private val idPattern = Regex("[A-Za-z0-9_-]+")

    private fun hostOf(url: String): String? =
        try { Uri.parse(url).host?.lowercase() } catch (e: Exception) { null }

    // ---------------- تحويلات فورية بدون شبكة ----------------

    /** يرجع رابط تنزيل مباشر إن كان الرابط معروفاً، وإلا يرجع الرابط كما هو. */
    fun resolve(url: String): String {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return url }
        val scheme = uri.scheme?.lowercase() ?: return url
        if (scheme != "http" && scheme != "https") return url
        val host = uri.host?.lowercase() ?: return url
        val path = uri.path ?: ""

        // Google Drive و Google Docs
        if (host == "drive.google.com" || host == "docs.google.com" || host == "drive.usercontent.google.com") {
            if (host == "docs.google.com") {
                val doc = Regex("^/(document|spreadsheets|presentation)/d/([A-Za-z0-9_-]+)").find(path)
                if (doc != null) {
                    val kind = doc.groupValues[1]
                    val format = when (kind) {
                        "document" -> "docx"
                        "spreadsheets" -> "xlsx"
                        else -> "pptx"
                    }
                    return "https://docs.google.com/$kind/d/${doc.groupValues[2]}/export?format=$format"
                }
            }
            if (!path.startsWith("/drive/folders")) {
                val id = Regex("^/(?:u/\\d+/)?file/d/([A-Za-z0-9_-]+)").find(path)?.groupValues?.get(1)
                    ?: uri.getQueryParameter("id")?.takeIf { idPattern.matches(it) }
                if (id != null) {
                    return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
                }
            }
            return url
        }

        // Dropbox: dl=1 يعطي الملف مباشرة بدل صفحة المعاينة
        if ((host == "dropbox.com" || host.endsWith(".dropbox.com")) &&
            (path.startsWith("/s/") || path.startsWith("/scl/") || path.startsWith("/sh/"))
        ) {
            return withQuery(uri, "dl", "1")
        }

        // OneDrive
        if (host == "1drv.ms" || host == "onedrive.live.com") {
            if (path.startsWith("/download")) return url
            // روابط redir/embed تتحول إلى download مباشرة
            if (path.startsWith("/redir") || path.startsWith("/embed")) {
                return uri.buildUpon().path("/download").build().toString()
            }
            // بقية الروابط (بما فيها 1drv.ms): واجهة المشاركة الرسمية تعطي الملف مباشرة
            val encoded = Base64.encodeToString(
                url.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            return "https://api.onedrive.com/v1.0/shares/u!" + encoded + "/root/content"
        }
        // SharePoint / OneDrive للأعمال
        if (host.endsWith(".sharepoint.com") && uri.getQueryParameter("download") == null) {
            return withQuery(uri, "download", "1")
        }

        // GitHub
        if (host == "github.com") {
            Regex("^/([^/]+)/([^/]+)/blob/(.+)").find(path)?.let { m ->
                return "https://raw.githubusercontent.com/" + m.groupValues[1] + "/" + m.groupValues[2] + "/" + m.groupValues[3]
            }
            Regex("^/([^/]+)/([^/]+)/tree/([^/]+)").find(path)?.let { m ->
                return "https://github.com/" + m.groupValues[1] + "/" + m.groupValues[2] + "/archive/refs/heads/" + m.groupValues[3] + ".zip"
            }
            // صفحة المستودع نفسها: أرشيف zip للفرع الافتراضي
            Regex("^/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/?$").find(path)?.let { m ->
                val reserved = setOf("orgs", "settings", "topics", "marketplace", "sponsors", "features", "about", "login", "join")
                if (m.groupValues[1] !in reserved) {
                    return "https://github.com/" + m.groupValues[1] + "/" + m.groupValues[2] + "/archive/HEAD.zip"
                }
            }
            return url
        }
        // GitLab و Bitbucket
        if (host == "gitlab.com" && path.contains("/-/blob/")) {
            return uri.buildUpon().path(path.replace("/-/blob/", "/-/raw/")).build().toString()
        }
        if (host == "bitbucket.org") {
            Regex("^/([^/]+)/([^/]+)/src/(.+)").find(path)?.let { m ->
                return "https://bitbucket.org/" + m.groupValues[1] + "/" + m.groupValues[2] + "/raw/" + m.groupValues[3]
            }
        }

        // Pixeldrain
        if (host == "pixeldrain.com" || host == "pixeldrain.net") {
            Regex("^/u/([A-Za-z0-9]+)").find(path)?.let { m ->
                return "https://pixeldrain.com/api/file/" + m.groupValues[1] + "?download"
            }
        }
        return url
    }

    private fun withQuery(uri: Uri, key: String, value: String): String {
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name == key) continue
            for (v in uri.getQueryParameters(name)) builder.appendQueryParameter(name, v)
        }
        builder.appendQueryParameter(key, value)
        return builder.build().toString()
    }

    // ---------------- مواقع تحتاج طلب واجهة (API) لمعرفة الرابط المباشر ----------------

    private fun isYandexHost(host: String) =
        host == "yadi.sk" || host.startsWith("disk.yandex.") || host == "disk.yandex.com"

    private fun isPcloudUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val path = try { Uri.parse(url).path ?: "" } catch (e: Exception) { "" }
        return (host.endsWith("pcloud.link") || host.endsWith("pcloud.com")) && path.contains("publink")
    }

    /** هل يحتاج الرابط طلباً على الشبكة لمعرفة رابط التنزيل الحقيقي (Mega، Yandex Disk، pCloud)؟ */
    fun needsRemoteResolution(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return MegaSupport.isMegaHost(host) || isYandexHost(host) || isPcloudUrl(url)
    }

    /** يُستدعى من خيط الخلفية. يرمي NonRetryableDownloadException برسالة واضحة عند الفشل. */
    fun resolveRemote(url: String): RemoteLink {
        val host = hostOf(url) ?: throw NonRetryableDownloadException("رابط غير صالح")
        return when {
            MegaSupport.isMegaHost(host) -> MegaSupport.resolve(url)
            isYandexHost(host) -> resolveYandex(url)
            isPcloudUrl(url) -> resolvePcloud(url)
            else -> throw NonRetryableDownloadException("رابط غير مدعوم")
        }
    }

    private fun httpGetText(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveYandex(url: String): RemoteLink {
        val api = "https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key=" +
            URLEncoder.encode(url, "UTF-8")
        val (_, text) = httpGetText(api)
        val obj = try { JSONObject(text) } catch (e: Exception) { throw IOException("رد Yandex Disk غير مفهوم") }
        val href = obj.optString("href")
        if (href.isBlank()) {
            throw NonRetryableDownloadException(
                obj.optString("description").ifBlank { "تعذّر الحصول على رابط التنزيل من Yandex Disk" }
            )
        }
        val name = try { Uri.parse(href).getQueryParameter("filename") } catch (e: Exception) { null }
        return RemoteLink(href, name)
    }

    private fun resolvePcloud(url: String): RemoteLink {
        val code = Uri.parse(url).getQueryParameter("code")
            ?: throw NonRetryableDownloadException("رابط pCloud غير صالح (لا يحتوي code)")
        val encoded = URLEncoder.encode(code, "UTF-8")
        // للحساب الأمريكي ثم الأوروبي
        for (api in listOf("https://api.pcloud.com", "https://eapi.pcloud.com")) {
            val (_, text) = httpGetText("$api/getpublinkdownload?code=$encoded")
            val obj = try { JSONObject(text) } catch (e: Exception) { continue }
            if (obj.optInt("result", -1) != 0) continue
            val hosts = obj.optJSONArray("hosts")
            val path = obj.optString("path")
            if (hosts != null && hosts.length() > 0 && path.isNotBlank()) {
                return RemoteLink("https://" + hosts.getString(0) + path, Uri.decode(path.substringAfterLast('/')))
            }
        }
        throw NonRetryableDownloadException("تعذّر الحصول على رابط pCloud (قد يكون الرابط غير عام)")
    }

    // ---------------- Terabox ----------------

    /** صفحة مشاركة Terabox (وليس رابط الملف النهائي): تحتاج تسجيل دخول من المتصفح. */
    fun isTeraboxSharePage(url: String): Boolean {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        val family = host.contains("terabox") || host.contains("4funbox") || host.contains("mirrobox") ||
            host.contains("nephobox") || host.contains("momerybox") || host.contains("tibibox")
        if (!family) return false
        val path = uri.path ?: ""
        return path.startsWith("/s/") || path.contains("/share") || uri.getQueryParameter("surl") != null
    }

    // ---------------- صفحات HTML الوسيطة ----------------

    /** هل هذا الموقع يُرجع أحياناً صفحة HTML وسيطة بدل الملف (فنحتاج تحليلها)؟ */
    fun needsHtmlResolution(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "drive.google.com" || host == "docs.google.com" ||
            host == "drive.usercontent.google.com" || host.endsWith("mediafire.com")
    }

    /** يستخرج رابط التنزيل الحقيقي من صفحة HTML الوسيطة، أو null. */
    fun resolveFromHtml(pageUrl: String, html: String): String? {
        val host = hostOf(pageUrl) ?: return null
        return when {
            host.endsWith("mediafire.com") -> mediafireUrl(html)
            host.endsWith("google.com") -> driveConfirmUrl(html)
            else -> null
        }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&")

    /** صفحة «لا يمكن فحص هذا الملف بحثاً عن الفيروسات» في Google Drive للملفات الكبيرة. */
    private fun driveConfirmUrl(html: String): String? {
        val form = Regex("""<form[^>]*id="download-form"[^>]*action="([^"]+)"""").find(html)
        if (form != null) {
            val action = unescape(form.groupValues[1])
            val fields = Regex("""<input[^>]*type="hidden"[^>]*name="([^"]+)"[^>]*value="([^"]*)"""")
                .findAll(html)
                .joinToString("&") { m ->
                    URLEncoder.encode(m.groupValues[1], "UTF-8") + "=" +
                        URLEncoder.encode(unescape(m.groupValues[2]), "UTF-8")
                }
            val separator = if (action.contains('?')) "&" else "?"
            return if (fields.isEmpty()) action else action + separator + fields
        }
        val old = Regex("""href="(/uc\?export=download[^"]+)"""").find(html)
        if (old != null) return "https://drive.google.com" + unescape(old.groupValues[1])
        return null
    }

    /** صفحة تحميل MediaFire: الرابط المباشر يكون مشفّراً بـ base64 أو ظاهراً في زر التنزيل. */
    private fun mediafireUrl(html: String): String? {
        val scrambled = Regex("""data-scrambled-url="([^"]+)"""").find(html)?.groupValues?.get(1)
        if (scrambled != null) {
            val decoded = try {
                String(Base64.decode(scrambled, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) { null }
            if (!decoded.isNullOrBlank() && decoded.startsWith("http")) return decoded
        }
        val patterns = listOf(
            Regex("""id="downloadButton"[^>]*href="([^"]+)""""),
            Regex("""href="([^"]+)"[^>]*id="downloadButton""""),
            Regex("""aria-label="Download file"[^>]*href="([^"]+)""""),
            Regex("""href="(https?://download[0-9]*\.mediafire\.com/[^"]+)"""")
        )
        for (pattern in patterns) {
            val found = pattern.find(html)?.groupValues?.get(1)?.let { unescape(it) }
            if (found != null && found.startsWith("http")) return found
        }
        return null
    }
}
