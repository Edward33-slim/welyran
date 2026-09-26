package com.downls10

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * يدير التنزيلات وسجل روابطها بشكل دائم.
 * Android 10+: يحفظ مباشرة في مجلد Download عبر MediaStore.
 * Android 9 وأقدم: يحفظ في مجلد Download العام.
 */
object DownloadsRepository {

    val downloadList = mutableListOf<DownloadItem>()
    private val downloadEngine = DownloadManagerEngine()
    private val listeners = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var initialized = false
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 1500L

    fun ensureLoaded(context: Context) {
        if (initialized) return
        initialized = true
        downloadList.clear()
        downloadList.addAll(DownloadPersistence.load(context.applicationContext))
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private val persistenceExecutor = Executors.newSingleThreadExecutor()

    private fun notifyChanged(context: Context, forceSave: Boolean = false) {
        // تحديث الواجهة فوراً إذا كان التغيير ناتجاً عن نقرة من المستخدم.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.toList().forEach { it() }
        } else {
            mainHandler.post { listeners.toList().forEach { it() } }
        }

        val appContext = context.applicationContext
        mainHandler.post { runCatching { DownloadNotifier.sync(appContext) } }

        val now = System.currentTimeMillis()
        if (forceSave || now - lastSaveTime > SAVE_THROTTLE_MS) {
            lastSaveTime = now
            // لا نحفظ JSON على UI thread حتى لا تتأخر نقرات المتصفح والإيقاف والاستئناف.
            val snapshot = downloadList.toList()
            persistenceExecutor.execute {
                runCatching { DownloadPersistence.save(appContext, snapshot) }
            }
        }
    }

    private val probeExecutor = Executors.newCachedThreadPool()

    /**
     * يبدأ أو يستأنف رابطاً. يدعم http/https وdata:، وعند غموض اسم الملف أو نوعه
     * يستكشف استجابة الخادم أولاً (Content-Disposition + Content-Type + التحويلات).
     */
    fun startNewDownload(
        context: Context,
        url: String,
        showToast: Boolean = true,
        suggestedFileName: String? = null,
        userAgent: String? = null,
        referer: String? = null,
        mimeType: String? = null
    ) {
        val appContext = context.applicationContext
        trackActivities(context)
        ensureLoaded(appContext)
        requestNotificationPermissionOnce(context)
        var typed = url.trim()
        if (typed.isEmpty()) return

        if (typed.startsWith("data:", ignoreCase = true)) {
            saveDataUrl(appContext, typed, suggestedFileName, mimeType)
            return
        }
        if (typed.startsWith("magnet:", ignoreCase = true)) {
            openMagnet(appContext, typed)
            return
        }
        if (typed.startsWith("blob:", ignoreCase = true)) {
            toast(appContext, "رابط blob: يُنزَّل من داخل المتصفح فقط، من الصفحة التي أنشأته")
            return
        }
        // رابط بدون بروتوكول (مثل example.com/file.zip): نفترض https
        if (!typed.contains("://") && !typed.startsWith("blob:", ignoreCase = true)) {
            typed = "https://$typed"
        }
        val scheme = typed.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "ftp") {
            toast(appContext, "بروتوكول الرابط غير مدعوم (المدعوم: http وhttps وftp)")
            return
        }
        // Terabox: صفحة المشاركة تحتاج تسجيل دخول، ولا يوجد رابط مباشر بدونه
        if (scheme != "ftp" && LinkResolver.isTeraboxSharePage(typed)) {
            toast(appContext, "Terabox: افتح الرابط في المتصفح وسجّل الدخول ثم اضغط زر التنزيل في الصفحة")
            return
        }
        // روابط المشاركة (Google Drive, Dropbox, OneDrive, GitHub...) تتحول إلى روابط تنزيل مباشرة
        val cleanUrl = if (scheme == "ftp") typed else LinkResolver.resolve(typed)

        // يُسجّل رابط الملف الحقيقي سواء أضيف من المتصفح أو من مدير التنزيل +.
        DownloadHistory.add(appContext, cleanUrl)

        val existing = downloadList.find { it.url == cleanUrl }
        if (existing != null) {
            if (existing.state == DownloadState.COMPLETED) {
                if (isFilePresent(appContext, existing)) {
                    toast(appContext, "الملف موجود: ${existing.fileName}")
                    return
                }
                // السجل موجود لكن الملف حُذف من الجهاز: اعتبره تنزيلًا جديدًا.
                downloadList.remove(existing)
            } else {
                if (existing.state == DownloadState.DOWNLOADING) {
                    toast(appContext, "التنزيل قيد التنفيذ")
                    return
                }
                if (!userAgent.isNullOrBlank()) existing.userAgent = userAgent
                if (!referer.isNullOrBlank()) existing.referer = referer
                existing.state = DownloadState.DOWNLOADING
                existing.status = "في الانتظار..."
                existing.speed = "0 KB/s"
                notifyChanged(appContext, forceSave = true)
                executeDownload(appContext, existing)
                return
            }
        }

        if (LinkResolver.needsRemoteResolution(cleanUrl)) {
            // Mega / Yandex Disk / pCloud: نطلب من الموقع الاسم والرابط المباشر أولاً
            probeExecutor.execute {
                try {
                    val link = LinkResolver.resolveRemote(cleanUrl)
                    val remoteName = resolveFileName(
                        suggestedFileName?.takeIf { it.isNotBlank() } ?: link.fileName, null, link.directUrl, mimeType
                    )
                    mainHandler.post { beginNewDownload(appContext, cleanUrl, remoteName, userAgent, referer) }
                } catch (e: Exception) {
                    toast(appContext, "فشل تنزيل الملف: " + (e.message ?: "تعذّر قراءة الرابط"))
                }
            }
            return
        }

        if (scheme == "ftp") {
            // FTP لا توجد فيه ترويسات: الاسم من مسار الرابط
            val ftpName = resolveFileName(suggestedFileName?.takeIf { it.isNotBlank() }, null, cleanUrl, mimeType)
            beginNewDownload(appContext, cleanUrl, ftpName, userAgent, referer)
            return
        }

        // اسم الملف: من Content-Disposition، وإلا من الرابط إذا كان يشبه اسم ملف حقيقي
        val hint = suggestedFileName?.takeIf { it.isNotBlank() } ?: nameFromUrlIfReliable(cleanUrl)
        if (hint != null && hasUsableExtension(hint)) {
            beginNewDownload(appContext, cleanUrl, sanitizeFileName(hint), userAgent, referer)
            return
        }

        // الاسم غير معروف أو بلا امتداد: نسأل الخادم أولاً عن الاسم والنوع الحقيقيين
        probeExecutor.execute {
            val info = downloadEngine.probe(cleanUrl, userAgent, referer)
            val name = resolveFileName(hint, info, cleanUrl, mimeType)
            mainHandler.post { beginNewDownload(appContext, cleanUrl, name, userAgent, referer) }
        }
    }

    /** روابط magnet تُسلَّم لتطبيق تورنت مثبّت على الجهاز (المدير الحالي لا يحتوي محرك BitTorrent). */
    private fun openMagnet(appContext: Context, magnet: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(magnet)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            toast(appContext, "تم إرسال رابط magnet إلى تطبيق التورنت")
        } catch (e: Exception) {
            toast(appContext, "لا يوجد تطبيق تورنت على الجهاز لفتح رابط magnet")
        }
    }
    private var askedNotificationPermission = false

    /** من أندرويد 13 يلزم إذن الإشعارات ليظهر إشعار التنزيل: نطلبه مرة واحدة. */
    private fun requestNotificationPermissionOnce(context: Context) {
        if (askedNotificationPermission || Build.VERSION.SDK_INT < 33 || context !is Activity) return
        askedNotificationPermission = true
        try {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                context.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7301)
            }
        } catch (e: Exception) { }
    }

    /** هل يمكن استئناف هذا التنزيل من الشبكة؟ (ملفات blob/data المحلية لا يمكن استئنافها) */
    fun isResumable(item: DownloadItem): Boolean {
        val u = item.url.lowercase()
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("ftp://")
    }

    /** إيقاف مؤقت لكل التنزيلات الجارية (زر الإشعار). */
    fun pauseAll(context: Context) {
        ensureLoaded(context.applicationContext)
        downloadList.filter { it.state == DownloadState.DOWNLOADING }.forEach { pauseDownload(context, it) }
    }

    /** استئناف كل التنزيلات المتوقفة مؤقتاً (زر الإشعار). */
    fun resumeAll(context: Context) {
        ensureLoaded(context.applicationContext)
        downloadList.filter { it.state == DownloadState.PAUSED && isResumable(it) }.forEach { resumeDownload(context, it) }
    }

    /** بعد إنهاء النظام للعملية: يكمل التنزيلات التي كانت جارية وقتها. */
    fun resumeInterrupted(context: Context) {
        ensureLoaded(context.applicationContext)
        val ids = DownloadPersistence.interruptedIds
        DownloadPersistence.interruptedIds = emptySet()
        downloadList.filter { it.id in ids && it.state == DownloadState.PAUSED && isResumable(it) }
            .forEach { resumeDownload(context, it) }
    }

    /** ينشئ عنصر التنزيل ويبدأه بعد أن أصبح اسم الملف معروفاً. يعمل على الخيط الرئيسي. */
    private fun beginNewDownload(
        appContext: Context,
        cleanUrl: String,
        fileName: String,
        userAgent: String?,
        referer: String?
    ) {
        // ضغطتَ على الرابط مرتين أثناء الاستكشاف
        if (downloadList.any { it.url == cleanUrl && it.state == DownloadState.DOWNLOADING }) {
            toast(appContext, "التنزيل قيد التنفيذ")
            return
        }

        // إذا كان نفس اسم الملف موجوداً فعلياً في Download، لا ننشئ نسخة ثانية.
        val knownInList = downloadList.any { it.fileName == fileName && isFilePresent(appContext, it) }
        if (knownInList || isFileNamePresentInDownload(appContext, fileName)) {
            toast(appContext, "الملف موجود: $fileName")
            return
        }

        val ext = fileName.substringAfterLast(".", "bin")
        val item = DownloadItem(
            id = System.currentTimeMillis(),
            url = cleanUrl,
            fileName = fileName,
            extension = ext,
            userAgent = userAgent,
            referer = referer
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = createPendingDownload(appContext, item.fileName)
            if (uri == null) {
                toast(appContext, "تعذّر إنشاء ملف التنزيل داخل Download")
                return
            }
            // إذا كان في Download ملف بنفس الاسم لا يستطيع التطبيق رؤيته، يعيد النظام تسمية الملف الجديد تلقائيًا
            // إلى «الاسم (1).امتداد». نكتشف ذلك ونلغي التنزيل بدل إنشاء نسخة مكررة.
            val actualName = queryDisplayName(appContext.contentResolver, uri)
            if (actualName != null && isAutoRenamedCopy(fileName, actualName)) {
                deleteUriQuietly(appContext.contentResolver, uri)
                toast(appContext, "الملف موجود: $fileName")
                return
            }
            item.localUri = uri.toString()
        }

        downloadList.add(0, item)
        notifyChanged(appContext, forceSave = true)
        toast(appContext, "بدأ تنزيل الملف: ${item.fileName}")
        executeDownload(appContext, item)
    }

    // ---------------- تحديد اسم الملف ونوعه ----------------

    private val dynamicExtensions = setOf("php", "asp", "aspx", "jsp", "cgi", "do", "action", "ashx", "axd", "pl")

    /** هل للاسم امتداد حقيقي (حرفان/أرقام حتى 8 خانات)؟ */
    private fun hasUsableExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        val ext = name.substring(dot + 1)
        return ext.length in 1..8 && ext.all { it.isLetterOrDigit() }
    }

    private fun extensionOf(name: String): String =
        if (hasUsableExtension(name)) name.substringAfterLast('.').lowercase() else ""

    /** آخر جزء من مسار الرابط كاسم محتمل (بعد فك الترميز)، أو null. */
    private fun lastSegmentName(url: String): String? {
        return try {
            val seg = Uri.parse(url).lastPathSegment ?: return null
            val decoded = Uri.decode(seg).trim()
            if (decoded.isEmpty()) null else decoded
        } catch (e: Exception) {
            null
        }
    }

    /** اسم من الرابط فقط إذا كان يشبه ملفاً حقيقياً (له امتداد وليس امتداد صفحة ديناميكية). */
    private fun nameFromUrlIfReliable(url: String): String? {
        val name = lastSegmentName(url) ?: return null
        if (!hasUsableExtension(name)) return null
        if (extensionOf(name) in dynamicExtensions) return null
        return name
    }

    private val mimeExtensionOverrides = mapOf(
        "application/x-zip-compressed" to "zip",
        "application/x-rar-compressed" to "rar",
        "application/vnd.rar" to "rar",
        "application/x-7z-compressed" to "7z",
        "application/x-msdownload" to "exe",
        "application/x-bittorrent" to "torrent",
        "application/x-gzip" to "gz",
        "application/x-tar" to "tar",
        "text/plain" to "txt",
        "application/vnd.android.package-archive" to "apk"
    )

    private fun extensionForMime(mime: String?): String? {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        if (m.isNullOrEmpty() || m == "application/octet-stream" || m == "binary/octet-stream") return null
        return mimeExtensionOverrides[m] ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(m)
    }

    /** يجمع اسم الملف النهائي من: الاسم المقترح، Content-Disposition، الرابط النهائي، ونوع المحتوى. */
    private fun resolveFileName(hint: String?, info: ProbeInfo?, originalUrl: String, mimeHint: String?): String {
        var name: String? = hint?.takeIf { it.isNotBlank() }
        if (name == null && info != null) name = fileNameFromContentDispositionOrNull(info.contentDisposition)
        if (name == null) name = lastSegmentName(info?.finalUrl ?: originalUrl) ?: lastSegmentName(originalUrl)
        if (name == null) name = "file_${System.currentTimeMillis()}"
        name = sanitizeFileName(name)

        val mime = (info?.contentType ?: mimeHint)?.substringBefore(';')?.trim()?.lowercase()
        val mimeExt = extensionForMime(mime)
        val currentExt = extensionOf(name)
        if (currentExt.isEmpty()) {
            name = "$name.${mimeExt ?: "bin"}"
        } else if (currentExt in dynamicExtensions && mimeExt != null && mime != "text/html") {
            // مثل get.php يرجع ملف zip فعلياً
            name = name.substringBeforeLast('.') + "." + mimeExt
        }
        return name
    }

    // ---------------- ملفات محلية: data: و blob: ----------------

    /** مقبض كتابة لملف يُبنى محلياً (روابط blob: وdata:). */
    class LocalSink internal constructor(
        internal val item: DownloadItem,
        internal val output: OutputStream,
        internal val uri: Uri?,
        internal val file: File?
    ) {
        internal var written = 0L
        internal var lastUiUpdate = 0L
    }

    /** يشغّل الكتلة على الخيط الرئيسي وينتظر نتيجتها (للاستدعاء من خيوط أخرى). */
    private fun <T> runOnMainBlocking(block: () -> T?): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>(null)
        mainHandler.post {
            try { result.set(block()) } catch (e: Exception) { } finally { latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        return result.get()
    }

    /** يفتح ملفاً محلياً في Download ويسجّله في قائمة التنزيلات. يمكن استدعاؤه من أي خيط. */
    fun openLocalSink(context: Context, rawName: String?, mime: String?, totalBytes: Long, label: String): LocalSink? {
        val appContext = context.applicationContext
        return runOnMainBlocking { openLocalSinkOnMain(appContext, rawName, mime, totalBytes, label) }
    }

    private fun openLocalSinkOnMain(        appContext: Context, rawName: String?, mime: String?, totalBytes: Long, label: String
    ): LocalSink? {
        ensureLoaded(appContext)
        var name = sanitizeFileName(rawName?.takeIf { it.isNotBlank() } ?: "download_${System.currentTimeMillis()}")
        if (!hasUsableExtension(name)) name = "$name.${extensionForMime(mime) ?: "bin"}"

        val knownInList = downloadList.any { it.fileName == name && isFilePresent(appContext, it) }
        if (knownInList || isFileNamePresentInDownload(appContext, name)) {
            toast(appContext, "الملف موجود: $name")
            return null
        }

        val item = DownloadItem(
            id = System.currentTimeMillis(),
            url = label,
            fileName = name,
            extension = name.substringAfterLast('.', "bin"),
            status = "جاري التحميل: 0%"
        )
        var uri: Uri? = null
        var file: File? = null
        var output: OutputStream? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val created = createPendingDownload(appContext, name)
                    ?: throw IllegalStateException("تعذّر إنشاء الملف")
                uri = created
                val actualName = queryDisplayName(appContext.contentResolver, created)
                if (actualName != null && isAutoRenamedCopy(name, actualName)) {
                    deleteUriQuietly(appContext.contentResolver, created)
                    toast(appContext, "الملف موجود: $name")
                    return null
                }
                item.localUri = created.toString()
                output = appContext.contentResolver.openOutputStream(created, "w")
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, name)
                file = target
                output = FileOutputStream(target)
            }
        } catch (e: Exception) {
            uri?.let { deleteUriQuietly(appContext.contentResolver, it) }
            toast(appContext, "فشل تنزيل الملف: $name")
            return null
        }
        val out = output
        if (out == null) {
            uri?.let { deleteUriQuietly(appContext.contentResolver, it) }
            toast(appContext, "فشل تنزيل الملف: $name")
            return null
        }

        item.totalBytes = totalBytes
        downloadList.add(0, item)
        notifyChanged(appContext, forceSave = true)
        toast(appContext, "بدأ تنزيل الملف: $name")
        return LocalSink(item, out, uri, file)
    }

    /** يكتب دفعة بيانات في الملف المحلي (يُستدعى من خيط الكتابة، ويحدّث الواجهة على الخيط الرئيسي). */
    fun writeLocalSink(context: Context, sink: LocalSink, data: ByteArray, length: Int) {
        sink.output.write(data, 0, length)
        sink.written += length
        val now = System.currentTimeMillis()
        if (now - sink.lastUiUpdate >= 400) {
            sink.lastUiUpdate = now
            val written = sink.written
            val appContext = context.applicationContext
            mainHandler.post {
                sink.item.downloadedBytes = written
                if (sink.item.totalBytes > 0) {
                    sink.item.progress = ((written * 100) / sink.item.totalBytes).toInt().coerceIn(0, 100)
                }
                sink.item.status = "جاري التحميل: ${sink.item.progress}%"
                notifyChanged(appContext)
            }
        }
    }

    fun finishLocalSink(context: Context, sink: LocalSink) {
        val appContext = context.applicationContext
        try { sink.output.flush(); sink.output.close() } catch (e: Exception) { }
        mainHandler.post {
            sink.uri?.let { publishDownload(appContext, it) }
            sink.item.downloadedBytes = sink.written
            sink.item.totalBytes = sink.written
            sink.item.progress = 100
            sink.item.speed = "0 KB/s"
            sink.item.status = "مكتمل"
            sink.item.state = DownloadState.COMPLETED
            toast(appContext, "اكتمل تنزيل الملف: ${sink.item.fileName}")
            runCatching { notifyChanged(appContext, forceSave = true) }
        }
    }

    fun failLocalSink(context: Context, sink: LocalSink, message: String?) {
        val appContext = context.applicationContext
        try { sink.output.close() } catch (e: Exception) { }
        sink.uri?.let { deleteUriQuietly(appContext.contentResolver, it) }
        sink.file?.let { runCatching { it.delete() } }
        mainHandler.post {
            sink.item.localUri = null
            sink.item.speed = "0 KB/s"
            sink.item.status = "فشل: ${message ?: "تعذّر التنزيل"}"
            sink.item.state = DownloadState.ERROR
            toast(appContext, "فشل تنزيل الملف: ${sink.item.fileName}")
            runCatching { notifyChanged(appContext, forceSave = true) }
        }
    }

    /** رسالة تظهر 3 ثوانٍ (للاستخدام من الملفات الأخرى). */
    fun showMessage(context: Context, message: String) = toast(context, message)

    /** ينزّل رابط data: (نص أو base64) ويحفظه في Download. */
    fun saveDataUrl(context: Context, dataUrl: String, suggestedName: String?, mimeHint: String?) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        Thread {
            try {
                val comma = dataUrl.indexOf(',')
                if (comma < 0) throw IllegalArgumentException("رابط data غير صالح")
                val header = dataUrl.substring(5, comma)
                val isBase64 = header.contains(";base64", ignoreCase = true)
                val mime = header.substringBefore(';').ifBlank { mimeHint ?: "application/octet-stream" }
                val payload = Uri.decode(dataUrl.substring(comma + 1))
                val bytes = if (isBase64) {
                    android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                } else {
                    payload.toByteArray(Charsets.UTF_8)
                }
                val sink = openLocalSink(appContext, suggestedName, mime, bytes.size.toLong(), "data:$mime")
                if (sink != null) {
                    try {
                        writeLocalSink(appContext, sink, bytes, bytes.size)
                        finishLocalSink(appContext, sink)
                    } catch (e: Exception) {
                        failLocalSink(appContext, sink, e.message)
                    }
                }
            } catch (e: Exception) {
                toast(appContext, "فشل تنزيل الملف${if (suggestedName.isNullOrBlank()) "" else ": $suggestedName"}")
            }
        }.start()
    }

    fun executeDownload(context: Context, item: DownloadItem) {
        val appContext = context.applicationContext
        trackActivities(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = item.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: createPendingDownload(appContext, item.fileName)?.also { item.localUri = it.toString() }
            if (uri == null) {
                item.state = DownloadState.ERROR
                item.status = "فشل: تعذّر إنشاء ملف Download"
                notifyChanged(appContext, forceSave = true)
                return
            }
            downloadEngine.downloadFileToUri(
                item = item,
                contentResolver = appContext.contentResolver,
                uri = uri,
                onProgress = { progress, speed, downloaded, total ->
                    item.progress = progress
                    item.speed = speed
                    item.downloadedBytes = downloaded
                    item.status = "جاري التحميل: $progress%"
                    notifyChanged(appContext)
                },
                onComplete = {
                    toast(appContext, "اكتمل تنزيل الملف: ${item.fileName}")
                    publishDownload(appContext, uri)
                    item.progress = 100
                    item.speed = "0 KB/s"
                    item.status = "مكتمل"
                    runCatching { notifyChanged(appContext, forceSave = true) }
                },
                onError = { error ->
                    // إن كان فيه بيانات محمّلة نُبقي الملف الجزئي ليكمل من مكانه عند الاستئناف
                    if (item.downloadedBytes <= 0L) {
                        deleteUriQuietly(appContext.contentResolver, uri)
                        item.localUri = null
                    }
                    item.speed = "0 KB/s"
                    item.status = "فشل: $error"
                    item.state = DownloadState.ERROR
                    toast(appContext, "فشل تنزيل الملف: ${item.fileName}")
                    runCatching { notifyChanged(appContext, forceSave = true) }
                }
            )
        } else {
            val saveDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!saveDir.exists()) saveDir.mkdirs()
            downloadEngine.downloadFile(
                item = item,
                saveDir = saveDir,
                onProgress = { progress, speed, downloaded, total ->
                    item.progress = progress
                    item.speed = speed
                    item.downloadedBytes = downloaded
                    item.status = "جاري التحميل: $progress%"
                    notifyChanged(appContext)
                },
                onComplete = {
                    toast(appContext, "اكتمل تنزيل الملف: ${item.fileName}")
                    item.progress = 100
                    item.speed = "0 KB/s"
                    item.status = "مكتمل"
                    runCatching { notifyChanged(appContext, forceSave = true) }
                },
                onError = { error ->
                    item.speed = "0 KB/s"
                    item.status = "فشل: $error"
                    item.state = DownloadState.ERROR
                    toast(appContext, "فشل تنزيل الملف: ${item.fileName}")
                    runCatching { notifyChanged(appContext, forceSave = true) }
                }
            )
        }
    }

    fun pauseDownload(context: Context, item: DownloadItem) {
        downloadEngine.pauseDownload(item.id)
        item.state = DownloadState.PAUSED
        item.status = "متوقف مؤقتاً"
        item.speed = "0 KB/s"
        notifyChanged(context, forceSave = true)
    }

    fun resumeDownload(context: Context, item: DownloadItem) {
        item.state = DownloadState.DOWNLOADING
        executeDownload(context, item)
    }

    fun deleteDownload(context: Context, item: DownloadItem, deleteFileOnDisk: Boolean) {
        val appContext = context.applicationContext
        downloadEngine.pauseDownload(item.id)
        if (deleteFileOnDisk || item.state != DownloadState.COMPLETED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                item.localUri?.let { uriString ->
                    runCatching { appContext.contentResolver.delete(Uri.parse(uriString), null, null) }
                }
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), item.fileName)
                if (file.exists()) file.delete()
            }
        }
        downloadList.remove(item)
        notifyChanged(appContext, forceSave = true)
    }

    /** يتحقق من وجود الملف فعلياً في Download، وليس فقط وجود السجل. */
    fun isFilePresent(context: Context, item: DownloadItem): Boolean {
        if (item.state != DownloadState.COMPLETED) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = item.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (uri != null) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
                }.getOrDefault(false)
            } else {
                findDownloadUriByName(context.contentResolver, item.fileName) != null
            }
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), item.fileName).exists()
        }
    }

    private fun createPendingDownload(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return runCatching {
            context.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull()
    }

    private fun publishDownload(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    private fun deleteUriQuietly(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun findDownloadUriByName(resolver: ContentResolver, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(fileName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)))
            } else null
        }
    }

    private fun mimeTypeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[\\\\/:*?"<>|\\r\\n]"""), "_").trim()
        return if (cleaned.isNotEmpty()) cleaned.take(180) else "file_${System.currentTimeMillis()}.bin"
    }

    /** يستخرج الاسم المقترح من Content-Disposition أو يعود لاسم الرابط. */
    fun fileNameFromContentDisposition(contentDisposition: String?, url: String): String {
        return fileNameFromContentDispositionOrNull(contentDisposition) ?: extractFileName(url)
    }

    /** الاسم من ترويسة Content-Disposition فقط، أو null إن لم توجد. */
    fun fileNameFromContentDispositionOrNull(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val star = Regex("""filename\*\s*=\s*([^'";]*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)
        if (star != null) {
            val charset = star.groupValues[1].ifBlank { "UTF-8" }
            val raw = star.groupValues[2].trim().trim('"')
            val decoded = runCatching {
                java.net.URLDecoder.decode(raw.replace("+", "%2B"), charset)
            }.getOrDefault(raw)
            if (decoded.isNotBlank()) return sanitizeFileName(decoded)
        }
        val quoted = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return sanitizeFileName(quoted)
        val plain = Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)?.trim()
        if (!plain.isNullOrBlank()) return sanitizeFileName(plain)
        return null
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    /** هل actualName هو نفس requestedName بعد أن أضاف النظام رقمًا مثل: file (1).zip */
    private fun isAutoRenamedCopy(requestedName: String, actualName: String): Boolean {
        if (actualName == requestedName) return false
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val ext = if (dot > 0) requestedName.substring(dot) else ""
        return Regex(Regex.escape(base) + " \\(\\d+\\)" + Regex.escape(ext)).matches(actualName)
    }

    private fun isFileNamePresentInDownload(context: Context, fileName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findDownloadUriByName(context.contentResolver, fileName) != null ||
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName).exists()
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            ).exists()
        }
    }

    private fun extractFileName(url: String): String {
        return try {
            val pathName = Uri.parse(url).lastPathSegment ?: ""
            val decoded = Uri.decode(pathName).substringBefore("?").trim()
            val cleaned = decoded.replace(Regex("[\\/:*?\"<>|]"), "_")
            if (cleaned.isNotEmpty() && cleaned.contains('.')) cleaned.take(180)
            else "file_${System.currentTimeMillis()}.bin"
        } catch (_: Exception) {
            "file_${System.currentTimeMillis()}.bin"
        }
    }

    private const val TOAST_DURATION_MS = 3000L
    private var currentToast: Toast? = null
    private var bannerView: TextView? = null
    private var topActivity: WeakReference<Activity>? = null
    private var lifecycleRegistered = false
    private val hideMessageRunnable = Runnable { hideMessage() }

    /** نتتبع الشاشة المفتوحة حاليًا لنعرض الرسائل فوقها بشكل مضمون. */
    private fun trackActivities(context: Context) {
        if (context is Activity) topActivity = WeakReference(context)
        if (lifecycleRegistered) return
        val app = context.applicationContext as? Application ?: return
        lifecycleRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {            override fun onActivityResumed(activity: Activity) { topActivity = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) {
                if (topActivity?.get() === activity) {
                    hideMessage()
                    topActivity = null
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun hideMessage() {
        mainHandler.removeCallbacks(hideMessageRunnable)
        currentToast?.cancel()
        currentToast = null
        bannerView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        bannerView = null
    }

    /** شريط رسالة داخل التطبيق فوق كل الواجهة (يظهر دائمًا حتى لو كانت رسائل Toast معطّلة في الجهاز). */
    private fun showBanner(activity: Activity, message: String): Boolean {
        return try {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
            val dp = activity.resources.displayMetrics.density
            val tv = TextView(activity).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(0xEE323232.toInt())
                    cornerRadius = 24 * dp
                }
                elevation = 24 * dp
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = (90 * dp).toInt()
                leftMargin = (24 * dp).toInt()
                rightMargin = (24 * dp).toInt()
            }
            root.addView(tv, lp)
            bannerView = tv
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * كل الرسائل تظهر 3 ثوانٍ بالضبط وتحلّ محل أي رسالة سابقة.
     * إن كان التطبيق مفتوحًا تظهر كشريط داخل الشاشة، وإلا تظهر كرسالة Toast.
     */
    private fun toast(context: Context, message: String) {
        mainHandler.post {
            hideMessage()
            val activity = topActivity?.get()
            val shown = activity != null && !activity.isFinishing && showBanner(activity, message)
            if (!shown) {
                val t = Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG)
                currentToast = t
                t.show()
            }
            mainHandler.postDelayed(hideMessageRunnable, TOAST_DURATION_MS)
        }
    }
}