package com.downloads10.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadService : Service() {
    companion object {
        const val ACTION_START = "downloads10.START"
        const val ACTION_PAUSE = "downloads10.PAUSE"
        const val ACTION_RESUME = "downloads10.RESUME"
        const val ACTION_CANCEL = "downloads10.CANCEL"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "downloads10_downloads"
    }

    private val executor = Executors.newFixedThreadPool(10)
    private val active = mutableMapOf<Long, DownloadTask>()
    private var concurrency = 3

    override fun onCreate() {
        super.onCreate()
        DownloadRepository.load(this)
        createChannel()
        startForeground(1000, notification("Downloads10 جاهز"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> if (id >= 0) enqueue(id)
            ACTION_PAUSE -> active[id]?.pause()
            ACTION_CANCEL -> active[id]?.cancel()
        }
        return START_STICKY
    }

    fun setConcurrency(value: Int) {
        concurrency = value.coerceIn(1, 10)
        pumpQueue()
    }

    private fun enqueue(id: Long) {
        DownloadRepository.update(this, id) {
            if (it.status != DownloadItem.STATUS_COMPLETED) it.status = DownloadItem.STATUS_QUEUED
        }
        pumpQueue()
    }

    private fun pumpQueue() {
        synchronized(active) {
            val running = active.values.count { !it.done }
            val slots = (concurrency - running).coerceAtLeast(0)
            if (slots == 0) return
            DownloadRepository.all()
                .filter { it.status == DownloadItem.STATUS_QUEUED }
                .take(slots)
                .forEach { item ->
                    if (active.containsKey(item.id)) return@forEach
                    val task = DownloadTask(item.id)
                    active[item.id] = task
                    executor.execute(task)
                }
        }
    }

    private inner class DownloadTask(private val id: Long) : Runnable {
        @Volatile var done = false
        @Volatile private var paused = false
        @Volatile private var cancelled = false
        private var connection: HttpURLConnection? = null

        fun pause() {
            paused = true
            connection?.disconnect()
            DownloadRepository.update(this@DownloadService, id) {
                if (it.status != DownloadItem.STATUS_COMPLETED) it.status = DownloadItem.STATUS_PAUSED
            }
            done = true
            synchronized(active) { active.remove(id) }
            pumpQueue()
        }

        fun cancel() {
            cancelled = true
            connection?.disconnect()
            DownloadRepository.update(this@DownloadService, id) { it.status = DownloadItem.STATUS_CANCELLED }
            done = true
            synchronized(active) { active.remove(id) }
            pumpQueue()
        }

        override fun run() {
            val item = DownloadRepository.find(id) ?: return
            val file = DownloadRepository.outputFile(this@DownloadService, item)
            DownloadRepository.update(this@DownloadService, id) {
                it.filePath = file.absolutePath
                it.status = DownloadItem.STATUS_DOWNLOADING
            }

            var input: BufferedInputStream? = null
            var output: FileOutputStream? = null
            try {
                val existing = if (file.exists()) file.length() else 0L
                var offset = existing
                val url = URL(item.url)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Downloads10/1.0")
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                    connect()
                }

                val code = connection!!.responseCode
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    // Resume accepted.
                } else if (code == HttpURLConnection.HTTP_OK && offset > 0) {
                    offset = 0
                    file.delete()
                } else if (code !in 200..299) {
                    throw Exception("HTTP $code")
                }

                val length = connection!!.contentLengthLong
                val total = if (length >= 0) offset + length else -1L
                DownloadRepository.update(this@DownloadService, id) {
                    it.downloadedBytes = offset
                    it.totalBytes = total
                }

                input = BufferedInputStream(connection!!.inputStream, 64 * 1024)
                output = FileOutputStream(file, offset > 0)
                val buffer = ByteArray(64 * 1024)
                var lastTime = System.nanoTime()
                var lastBytes = offset

                while (!paused && !cancelled) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    offset += n
                    val now = System.nanoTime()
                    if (now - lastTime >= 500_000_000L) {
                        val speed = ((offset - lastBytes) * 1_000_000_000L) /
                                (now - lastTime).coerceAtLeast(1L)
                        DownloadRepository.update(this@DownloadService, id) {
                            it.downloadedBytes = offset
                            it.speedBytes = speed
                        }
                        lastTime = now
                        lastBytes = offset
                    }
                }
                output.flush()

                if (!paused && !cancelled) {
                    DownloadRepository.update(this@DownloadService, id) {
                        it.downloadedBytes = offset
                        it.status = DownloadItem.STATUS_COMPLETED
                        it.speedBytes = 0
                        it.error = ""
                    }
                }
            } catch (t: Throwable) {
                if (!paused && !cancelled) {
                    DownloadRepository.update(this@DownloadService, id) {
                        it.status = DownloadItem.STATUS_FAILED
                        it.error = t.message ?: "Download failed"
                    }
                }
            } finally {
                runCatching { input?.close() }
                runCatching { output?.close() }
                connection?.disconnect()
                connection = null
                done = true
                synchronized(active) { active.remove(id) }
                pumpQueue()
            }
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Downloads10")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Downloads10 downloads",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
