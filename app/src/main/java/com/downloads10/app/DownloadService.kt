package com.downloads10.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

class DownloadService : Service() {
    companion object {
        const val ACTION_START = "com.downloads10.app.START"
        const val ACTION_PAUSE = "com.downloads10.app.PAUSE"
        const val ACTION_RESUME = "com.downloads10.app.RESUME"
        const val ACTION_CANCEL = "com.downloads10.app.CANCEL"
        const val ACTION_CHANGED = "com.downloads10.app.CHANGED"
        const val EXTRA_ID = "id"
        private const val CHANNEL_ID = "downloads10"
        private const val NOTIFICATION_ID = 10
    }

    private val lock = Any()
    private val futures = ConcurrentHashMap<Long, java.util.concurrent.Future<*>>()
    private var executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Downloads10", "جاري تجهيز التنزيلات"))
        reloadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> enqueuePending()
            ACTION_PAUSE -> intent.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 }?.let { pause(it) }
            ACTION_RESUME -> intent.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 }?.let { resume(it) }
            ACTION_CANCEL -> intent.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 }?.let { cancel(it) }
        }
        enqueuePending()
        return START_STICKY
    }

    private fun reloadExecutor() {
        synchronized(lock) {
            val wanted = DownloadStore.concurrent(this)
            executor.shutdownNow()
            executor = Executors.newFixedThreadPool(wanted)
        }
    }

    private fun enqueuePending() {
        synchronized(lock) {
            val items = DownloadStore.all(this)
            items.filter { it.status == DownloadItem.Status.QUEUED }.forEach { item ->
                if (!futures.containsKey(item.id) && futures.size < DownloadStore.concurrent(this)) {
                    futures[item.id] = executor.submit { runDownload(item.id) }
                }
            }
            updateNotification(items)
        }
    }

    private fun pause(id: Long) {
        futures.remove(id)?.cancel(true)
        val items = DownloadStore.all(this)
        items.firstOrNull { it.id == id }?.let { it.status = DownloadItem.Status.PAUSED; it.speed = 0; DownloadStore.save(this, items) }
        broadcastChanged()
    }

    private fun resume(id: Long) {
        val items = DownloadStore.all(this)
        items.firstOrNull { it.id == id }?.let {
            if (it.status == DownloadItem.Status.PAUSED || it.status == DownloadItem.Status.FAILED || it.status == DownloadItem.Status.CANCELLED) {
                it.status = DownloadItem.Status.QUEUED; it.error = ""; DownloadStore.save(this, items)
            }
        }
        broadcastChanged()
    }

    private fun cancel(id: Long) {
        futures.remove(id)?.cancel(true)
        val items = DownloadStore.all(this)
        items.firstOrNull { it.id == id }?.let { it.status = DownloadItem.Status.CANCELLED; it.speed = 0; DownloadStore.save(this, items) }
        broadcastChanged()
    }

    private fun runDownload(id: Long) {
        try {
            var attempt = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val item = DownloadStore.all(this).firstOrNull { it.id == id } ?: return
                if (item.status == DownloadItem.Status.PAUSED || item.status == DownloadItem.Status.CANCELLED || item.status == DownloadItem.Status.COMPLETED) return
                setStatus(id, DownloadItem.Status.DOWNLOADING, "")
                try {
                    performDownload(item)
                    return
                } catch (e: InterruptedException) {
                    setStatus(id, DownloadItem.Status.PAUSED, "")
                    return
                } catch (e: Exception) {
                    attempt++
                    if (!DownloadStore.retry(this) || attempt >= 5) {
                        setStatus(id, DownloadItem.Status.FAILED, e.message ?: "فشل التنزيل")
                        return
                    }
                    setStatus(id, DownloadItem.Status.QUEUED, e.message ?: "إعادة المحاولة")
                    try { Thread.sleep((attempt * 1500L).coerceAtMost(8000L)) } catch (_: InterruptedException) {
                        setStatus(id, DownloadItem.Status.PAUSED, "")
                        return
                    }
                }
            }
        } finally {
            futures.remove(id)
            broadcastChanged()
            mainHandler.post { enqueuePending() }
        }
    }

    private fun performDownload(snapshot: DownloadItem) {
        var url = snapshot.url
        var redirectCount = 0
        var connection: HttpURLConnection? = null
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 30000
                useCaches = false
                setRequestProperty("User-Agent", "Downloads10/1.0 (Android)")
                setRequestProperty("Accept", "*/*")
                val local = currentLocalSize(snapshot.id)
                if (local > 0) setRequestProperty("Range", "bytes=$local-")
                connect()
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                url = URL(URL(url), location).toString()
                connection.disconnect()
                redirectCount++
                if (redirectCount > 10) throw IOException("Too many redirects")
                continue
            }
            break
        }
        val conn = connection ?: throw IOException("Connection failed")
        try {
            val local = currentLocalSize(snapshot.id)
            if (conn.responseCode == HttpURLConnection.HTTP_REQUESTED_RANGE_NOT_SATISFIABLE && local > 0) {
                val total = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                if (total <= 0 || local >= total) {
                    updateItem(snapshot.id) { it.downloaded = local; it.size = if (total > 0) total else it.size; it.speed = 0; it.status = DownloadItem.Status.COMPLETED }
                    return
                }
                throw IOException("Server rejected resume")
            }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")

            val resumeAccepted = local > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            val base = if (resumeAccepted) local else 0L
            val serverLength = conn.contentLengthLong
            val total = if (serverLength >= 0) base + serverLength else parseContentRangeTotal(conn.getHeaderField("Content-Range"))
            if (total > 0) updateItem(snapshot.id) { it.size = total; it.downloaded = base }
            if (!resumeAccepted && local > 0) resetDestination(snapshot.id)

            val uri = ensureDestination(snapshot.id, chooseName(snapshot, conn))
            updateItem(snapshot.id) { it.localUri = uri.toString(); it.downloaded = if (resumeAccepted) base else 0L; if (it.fileName.isBlank()) it.fileName = chooseName(snapshot, conn) }

            val start = if (resumeAccepted) base else 0L
            var done = start
            var lastBytes = done
            var lastTime = System.nanoTime()
            val input = conn.inputStream.buffered()
            val mode = if (resumeAccepted) "wa" else "w"
            val pfd = contentResolver.openFileDescriptor(uri, mode) ?: throw IOException("Cannot open destination")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                input.use { ins ->
                    val buffer = ByteArray(1024 * 128)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val n = ins.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastTime >= 500_000_000L) {
                            val elapsed = (now - lastTime) / 1_000_000_000.0
                            val speed = ((done - lastBytes) / elapsed).toLong().coerceAtLeast(0)
                            lastBytes = done; lastTime = now
                            updateItem(snapshot.id) { it.downloaded = done; it.speed = speed; it.status = DownloadItem.Status.DOWNLOADING }
                            broadcastChanged()
                        }
                    }
                }
            }
            val finalSize = querySize(uri)
            updateItem(snapshot.id) {
                it.downloaded = if (finalSize >= 0) finalSize else done
                if (it.size <= 0) it.size = if (serverLength >= 0) done else it.downloaded
                it.speed = 0
                it.status = if (it.size <= 0 || it.downloaded >= it.size) DownloadItem.Status.COMPLETED else DownloadItem.Status.FAILED
                if (it.status == DownloadItem.Status.FAILED) it.error = "حجم الملف غير مكتمل"
            }
            broadcastChanged()
        } finally {
            conn.disconnect()
        }
    }

    private fun chooseName(item: DownloadItem, c: HttpURLConnection): String {
        if (item.fileName.isNotBlank()) return sanitize(item.fileName)
        val cd = c.getHeaderField("Content-Disposition") ?: ""
        val utf = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
        val plain = Regex("filename=\\\"?([^\\\";]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
        val raw = utf ?: plain ?: URL(item.url).path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "download_${item.id}"
        return sanitize(runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw))
    }

    private fun sanitize(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().ifBlank { "download" }.take(180)

    private fun currentLocalSize(id: Long): Long {
        val item = DownloadStore.all(this).firstOrNull { it.id == id } ?: return 0
        return if (item.localUri.isBlank()) 0 else querySize(Uri.parse(item.localUri)).coerceAtLeast(0)
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0).takeIf { it >= 0 } ?: -1
        }
        return -1
    }

    private fun ensureDestination(id: Long, name: String): Uri {
        val item = DownloadStore.all(this).firstOrNull { it.id == id } ?: throw IOException("Download not found")
        if (item.localUri.isNotBlank()) return Uri.parse(item.localUri)
        val tree = DownloadStore.folder(this)?.let(Uri::parse) ?: throw IOException("اختر مجلد التنزيل أولاً")
        val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val existing = findChild(tree, name)
        if (existing != null) return existing
        val uri = DocumentsContract.createDocument(contentResolver, treeDoc, "application/octet-stream", name)
            ?: throw IOException("Cannot create destination")
        return uri
    }

    private fun findChild(tree: Uri, name: String): Uri? {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) if (c.getString(nameCol) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idCol))
        }
        return null
    }

    private fun resetDestination(id: Long) {
        val item = DownloadStore.all(this).firstOrNull { it.id == id } ?: return
        if (item.localUri.isBlank()) return
        runCatching { DocumentsContract.deleteDocument(contentResolver, Uri.parse(item.localUri)) }
        updateItem(id) { it.localUri = ""; it.downloaded = 0 }
    }

    private fun updateItem(id: Long, change: (DownloadItem) -> Unit) {
        val items = DownloadStore.all(this)
        items.firstOrNull { it.id == id }?.let { change(it); DownloadStore.save(this, items) }
    }

    private fun setStatus(id: Long, status: DownloadItem.Status, error: String) = updateItem(id) { it.status = status; it.error = error; if (status != DownloadItem.Status.DOWNLOADING) it.speed = 0 }

    private fun parseContentRangeTotal(value: String?): Long = Regex("/([0-9]+)").find(value ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: -1L

    private fun broadcastChanged() = sendBroadcast(Intent(ACTION_CHANGED).setPackage(packageName))

    private fun updateNotification(items: List<DownloadItem>) {
        val active = items.count { it.status == DownloadItem.Status.DOWNLOADING }
        val queued = items.count { it.status == DownloadItem.Status.QUEUED }
        startForeground(NOTIFICATION_ID, notification("Downloads10", "تنزيل نشط: $active • في الانتظار: $queued"))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads10", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(title: String, text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title).setContentText(text).setSmallIcon(com.downloads10.app.R.mipmap.ic_launcher).setContentIntent(intent).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title).setContentText(text).setSmallIcon(com.downloads10.app.R.mipmap.ic_launcher).setContentIntent(intent).setOngoing(true).build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
