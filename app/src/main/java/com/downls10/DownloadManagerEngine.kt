package com.downls10

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLHandshakeException

/** خطأ نهائي لا فائدة من إعادة المحاولة معه. */
class NonRetryableDownloadException(message: String) : IOException(message)

/** معلومات الاستجابة الفعلية للرابط بعد اتباع التحويلات (تُستخدم لمعرفة اسم الملف ونوعه). */
data class ProbeInfo(
    val finalUrl: String,
    val contentDisposition: String?,
    val contentType: String?,
    val contentLength: Long,
    val responseCode: Int
)

/** مصدر البيانات بعد فتح الاتصال (HTTP أو FTP). */
private class DownloadSource(
    val input: InputStream,
    val totalSize: Long,
    val startOffset: Long,
    private val closer: () -> Unit
) {
    fun close() {
        try { closer() } catch (_: Exception) { }
    }
}

class DownloadManagerEngine {

    private val executor = Executors.newFixedThreadPool(8)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeTasks = ConcurrentHashMap<Long, Boolean>()
    private val generations = ConcurrentHashMap<Long, Int>()

    private val bufferSize = 256 * 1024
    private val maxConsecutiveFailures = 8

    private val defaultUserAgent =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    private fun isFtp(url: String) = url.startsWith("ftp://", ignoreCase = true)

    /** يفتح الرابط ويقرأ الترويسات فقط (دون تنزيل المحتوى). يرجع null عند الفشل أو لروابط FTP. */
    fun probe(url: String, userAgent: String?, referer: String?): ProbeInfo? {
        if (isFtp(url)) return null
        val temp = DownloadItem(
            id = 0L, url = url, fileName = "", extension = "",
            userAgent = userAgent, referer = referer
        )
        return try {
            val conn = openWithRedirects(temp, 0L)
            try {
                ProbeInfo(
                    finalUrl = conn.url.toString(),
                    contentDisposition = conn.getHeaderField("Content-Disposition"),
                    contentType = conn.contentType,
                    contentLength = conn.contentLengthLong,
                    responseCode = conn.responseCode
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun downloadFile(
        item: DownloadItem,
        saveDir: File,
        onProgress: (Int, String, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        downloadFileInternal(item, saveDir, null, onProgress, onComplete, onError)
    }

    fun downloadFileToUri(
        item: DownloadItem,
        contentResolver: ContentResolver,
        uri: Uri,
        onProgress: (Int, String, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        downloadFileInternal(item, null, Pair(contentResolver, uri), onProgress, onComplete, onError)
    }

    /**
     * يفتح الاتصال مع كوكيز المتصفح (WebView) والـ User-Agent والـ Referer.
     * التحويلات (redirects) تُتبع يدويًا، ولكل خطوة تُرسل كوكيز موقعها فقط،
     * حتى لا تصل كوكيز موقع إلى موقع آخر.
     * ولصفحات الوسيط (تحذير الملفات الكبيرة في Drive، صفحة MediaFire) يستخرج الرابط الحقيقي.
     */
    private fun openWithRedirects(item: DownloadItem, existingLength: Long, urlOverride: String? = null): HttpURLConnection {
        var currentUrl = urlOverride ?: item.url
        var redirects = 0
        var htmlHops = 0
        var confirmedFile = false
        val ua = item.userAgent
        val ref = item.referer
        while (true) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            // بدون ضغط gzip حتى يكون حجم الملف وتقدّم التنزيل ومواضع الاستئناف دقيقة
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("User-Agent", if (!ua.isNullOrBlank()) ua else defaultUserAgent)
            val cookies = try { CookieManager.getInstance().getCookie(currentUrl) } catch (e: Exception) { null }
            if (!cookies.isNullOrBlank()) conn.setRequestProperty("Cookie", cookies)
            if (!ref.isNullOrBlank()) conn.setRequestProperty("Referer", ref)

            val needsHtml = LinkResolver.needsHtmlResolution(currentUrl)
            // صفحات الوسيط تُطلب بدون Range أولاً، ثم نعيد الطلب مع Range عندما نتأكد أنه الملف نفسه
            if (existingLength > 0 && (!needsHtml || confirmedFile)) {
                conn.setRequestProperty("Range", "bytes=$existingLength-")
            }
            conn.connect()

            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                redirects++
                if (location.isNullOrBlank() || redirects > 10) {
                    throw NonRetryableDownloadException("تحويلات كثيرة")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                continue
            }

            if (needsHtml && !confirmedFile && code == 200) {
                val contentType = conn.contentType ?: ""
                val isHtml = contentType.startsWith("text/html", ignoreCase = true) &&
                    conn.getHeaderField("Content-Disposition") == null
                if (isHtml) {
                    val html = try {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        ""
                    }
                    conn.disconnect()
                    htmlHops++
                    val next = if (htmlHops <= 3) LinkResolver.resolveFromHtml(currentUrl, html.take(400_000)) else null
                    if (next == null) {
                        throw NonRetryableDownloadException("تعذّر الحصول على رابط التنزيل المباشر (قد يكون الملف خاصاً أو غير متاح)")
                    }
                    currentUrl = next
                    continue
                }
                // هذا هو الملف نفسه: إن كنا نستأنف نعيد الطلب مع Range
                if (existingLength > 0) {
                    conn.disconnect()
                    confirmedFile = true
                    continue
                }
            }
            return conn
        }
    }

    /** مصدر HTTP: يعالج رفض الاستئناف (416) وأكواد الأخطاء. */
    private fun openHttpSource(item: DownloadItem, existingLength: Long, urlOverride: String? = null): DownloadSource {
        var conn: HttpURLConnection = openWithRedirects(item, existingLength, urlOverride)
        var effectiveExisting = existingLength
        if (conn.responseCode == 416 && existingLength > 0) {
            // الخادم يرفض نقطة الاستئناف (الملف تغيّر أو اكتمل): نبدأ من الصفر
            conn.disconnect()
            effectiveExisting = 0L
            conn = openWithRedirects(item, 0L, urlOverride)
        }
        val finalConn = conn
        val code = finalConn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            finalConn.disconnect()
            // أخطاء الخادم المؤقتة نعيد فيها المحاولة، وبقية الأخطاء (404, 403...) نهائية
            if (code == 509) throw NonRetryableDownloadException("تجاوزت حد النقل المسموح لدى هذا الموقع، حاول لاحقاً")
            if (code == 429 || code >= 500) throw IOException("خطأ: $code")
            throw NonRetryableDownloadException("خطأ: $code")
        }
        var startOffset = effectiveExisting
        if (effectiveExisting > 0 && code == HttpURLConnection.HTTP_OK) {
            // الخادم تجاهل Range: نعيد الملف من البداية بدل إضافة بايتات مكررة
            startOffset = 0L
        }
        val contentLength = finalConn.contentLengthLong.coerceAtLeast(0L)
        val totalSize = if (code == HttpURLConnection.HTTP_PARTIAL) startOffset + contentLength else contentLength
        return DownloadSource(finalConn.inputStream, totalSize, startOffset) { finalConn.disconnect() }
    }

    /**
     * روابط تحتاج طلب واجهة لمعرفة الرابط المباشر (Mega، Yandex Disk، pCloud).
     * يُطلب الرابط من جديد عند كل محاولة لأن الروابط المباشرة مؤقتة.
     * ملفات Mega تُفك تشفيرها أثناء القراءة.
     */
    private fun openRemoteSource(item: DownloadItem, existingLength: Long): DownloadSource {
        val link = LinkResolver.resolveRemote(item.url)
        val src = openHttpSource(item, existingLength, link.directUrl)
        val key = link.megaKey ?: return src
        val decrypted = MegaDecryptInputStream(src.input, key, src.startOffset)
        return DownloadSource(decrypted, src.totalSize, src.startOffset) { src.close() }
    }

    /** مصدر FTP (بدون تشفير): يدعم مستخدم:كلمة_مرور في الرابط، وإلا دخول مجهول. */
    private fun openFtpSource(item: DownloadItem, existingLength: Long): DownloadSource {
        val uri = Uri.parse(item.url)
        val host = uri.host ?: throw NonRetryableDownloadException("رابط FTP غير صالح")
        val port = if (uri.port > 0) uri.port else 21
        val path = uri.path
        if (path.isNullOrEmpty() || path == "/") throw NonRetryableDownloadException("رابط FTP لا يحدد ملفاً")

        var user = "anonymous"
        var password = "anonymous@example.com"
        val userInfo = uri.userInfo
        if (!userInfo.isNullOrEmpty()) {
            user = Uri.decode(userInfo.substringBefore(':'))
            password = if (userInfo.contains(':')) Uri.decode(userInfo.substringAfter(':')) else ""
        }

        val client = FtpClient(host, port)
        try {
            client.connect(user, password)
            val size = client.size(path)
            val (stream, actualOffset) = client.retrieve(path, existingLength)
            val total = if (size > 0) size else 0L
            return DownloadSource(stream, total, actualOffset) { client.close() }
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }

    /** الحجم الفعلي للملف المكتوب (المصدر الوحيد الموثوق لنقطة الاستئناف). */
    private fun uriLength(resolver: ContentResolver, uri: Uri): Long {
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun checkDiskSpace(totalSize: Long, startOffset: Long) {
        if (totalSize <= 0L) return
        val needed = totalSize - startOffset
        val available = try {
            StatFs(Environment.getExternalStoragePublicDirectory(DownloadsRepository.DOWNLOAD_DIRECTORY_NAME).path).availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
        if (needed > available) throw NonRetryableDownloadException("لا توجد مساحة كافية على الجهاز")
    }

    private fun downloadFileInternal(
        item: DownloadItem,
        saveDir: File?,
        uriTarget: Pair<ContentResolver, Uri>?,
        onProgress: (Int, String, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        activeTasks[item.id] = true
        item.state = DownloadState.DOWNLOADING
        // رقم لهذه المحاولة: إذا بدأت محاولة أحدث لنفس التنزيل تتوقف هذه تلقائياً (لا يكتب خيطان في ملف واحد)
        val generation = (generations[item.id] ?: 0) + 1
        generations[item.id] = generation
        val stopped = { activeTasks[item.id] == false || generations[item.id] != generation }

        executor.execute {
            var failures = 0
            while (true) {
                var source: DownloadSource? = null
                var output: OutputStream? = null
                var fileOutput: RandomAccessFile? = null
                var completedOk = false
                var retry = false
                try {
                    val existingLength = if (saveDir != null) {
                        val outputFile = File(saveDir, item.fileName)
                        if (outputFile.exists()) outputFile.length() else 0L
                    } else {
                        // حجم الملف الفعلي وليس الرقم المحفوظ (قد يكون أقدم إذا أُغلق التطبيق فجأة)
                        uriLength(uriTarget!!.first, uriTarget.second)
                    }

                    val src = when {
                        isFtp(item.url) -> openFtpSource(item, existingLength)
                        LinkResolver.needsRemoteResolution(item.url) -> openRemoteSource(item, existingLength)
                        else -> openHttpSource(item, existingLength)
                    }
                    source = src
                    val startOffset = src.startOffset
                    val totalSize = src.totalSize
                    item.totalBytes = totalSize
                    item.downloadedBytes = startOffset
                    checkDiskSpace(totalSize, startOffset)

                    if (saveDir != null) {
                        val outputFile = File(saveDir, item.fileName)
                        fileOutput = RandomAccessFile(outputFile, "rw")
                        if (startOffset == 0L) fileOutput.setLength(0L) else fileOutput.seek(startOffset)
                    } else {
                        val mode = if (startOffset > 0L) "wa" else "w"
                        output = uriTarget!!.first.openOutputStream(uriTarget.second, mode)
                            ?: throw NonRetryableDownloadException("تعذّر فتح ملف التنزيل للكتابة")
                    }

                    val input = src.input
                    val data = ByteArray(bufferSize)
                    var totalRead = startOffset
                    var lastTime = System.currentTimeMillis()
                    var bytesSinceLast = 0L
                    var madeProgress = false
                    while (true) {
                        if (stopped()) return@execute
                        val count = input.read(data)
                        if (count == -1) break
                        if (count == 0) continue

                        if (fileOutput != null) fileOutput.write(data, 0, count)
                        else output!!.write(data, 0, count)

                        totalRead += count
                        bytesSinceLast += count
                        item.downloadedBytes = totalRead
                        if (!madeProgress) {
                            madeProgress = true
                            failures = 0   // وصلت بيانات جديدة: نبدأ عدّ الإخفاقات من جديد
                        }

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastTime
                        if (elapsed >= 500) {
                            val speedKb = (bytesSinceLast * 1000L / elapsed) / 1024L
                            val speedText = if (speedKb >= 1024) "${speedKb / 1024} MB/s" else "$speedKb KB/s"
                            val progress = if (totalSize > 0) ((totalRead * 100) / totalSize).toInt().coerceIn(0, 100) else 0
                            mainHandler.post { onProgress(progress, speedText, totalRead, totalSize) }
                            lastTime = now
                            bytesSinceLast = 0L
                        }
                    }

                    // إذا انقطع الاتصال قبل اكتمال الحجم المعلن فهذا ليس نجاحًا
                    if (totalSize > 0 && totalRead < totalSize) {
                        throw java.io.EOFException("انقطع الاتصال قبل اكتمال الملف")
                    }

                    item.downloadedBytes = totalRead
                    item.state = DownloadState.COMPLETED
                    completedOk = true   // نستدعي onComplete بعد إغلاق الملف في finally

                } catch (e: Exception) {
                    if (stopped()) return@execute
                    // أخطاء الشبكة المؤقتة فقط (انقطاع، مهلة، إعادة ضبط الاتصال...) نعيد فيها المحاولة
                    val retryable = e is IOException &&
                        e !is NonRetryableDownloadException &&
                        e !is MalformedURLException &&
                        e !is SSLHandshakeException
                    if (retryable && failures < maxConsecutiveFailures) {
                        retry = true
                    } else {
                        item.state = DownloadState.ERROR
                        mainHandler.post { onError(e.message ?: "فشل التحميل") }
                    }
                } finally {
                    try { fileOutput?.close() } catch (_: Exception) { }
                    try { output?.close() } catch (_: Exception) { }
                    source?.close()
                    if (completedOk) mainHandler.post { onComplete() }
                }

                if (retry) {
                    failures++
                    val waitMs = minOf(30000L, 2000L * (1L shl minOf(failures, 4)))
                    try { Thread.sleep(waitMs) } catch (_: InterruptedException) { return@execute }
                    if (stopped()) return@execute
                    continue
                }
                return@execute
            }
        }
    }

    fun pauseDownload(id: Long) { activeTasks[id] = false }

    fun cancelDownload(id: Long, saveDir: File, fileName: String, deleteFileOnDisk: Boolean) {
        pauseDownload(id)
        if (deleteFileOnDisk) {
            val file = File(saveDir, fileName)
            if (file.exists()) file.delete()
        }
    }
}
