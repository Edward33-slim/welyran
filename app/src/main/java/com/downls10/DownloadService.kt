package com.downls10

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/**
 * خدمة تعمل في المقدمة (مع إشعار) لتبقى التنزيلات شغّالة عند إغلاق التطبيق أو تحويله للخلفية.
 * التنزيل نفسه يجري داخل DownloadManagerEngine، والخدمة تُبقي العملية حيّة وتعرض الإشعار.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_PAUSE_ALL = "com.downls10.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.downls10.action.RESUME_ALL"

        @Volatile
        var instance: DownloadService? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DownLS10:downloads").apply {
                setReferenceCounted(false)
            }
            renewWakeLock()
        } catch (e: Exception) { }
    }

    /** يُبقي المعالج مستيقظاً أثناء التنزيل (يُجدَّد مع كل تحديث للإشعار). */
    fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
                it.acquire(30 * 60 * 1000L)
            }
        } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadsRepository.ensureLoaded(this)
        when (intent?.action) {
            ACTION_PAUSE_ALL -> DownloadsRepository.pauseAll(this)
            ACTION_RESUME_ALL -> DownloadsRepository.resumeAll(this)
            // النظام أعاد تشغيل الخدمة بعد إنهاء العملية: نكمل التنزيلات التي كانت جارية
            null -> DownloadsRepository.resumeInterrupted(this)
        }

        val notification = DownloadNotifier.build(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(DownloadNotifier.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(DownloadNotifier.NOTIF_ID, notification)
        }
        // يقرّر هل نبقى شغّالين (تنزيل جارٍ) أم نتوقف (لا شيء جارٍ)
        DownloadNotifier.sync(this, force = true)
        return START_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        instance = null
        super.onDestroy()
    }
}

/**
 * يبني إشعار التنزيل ويقرّر متى تعمل الخدمة:
 * - تنزيل جارٍ: سهم أبيض (أيقونة التنزيل القياسية) + تقدّم + زر «إيقاف مؤقت».
 * - كل التنزيلات متوقفة مؤقتاً: علامة إيقاف مؤقت + زر «استئناف».
 * - لا شيء: لا إشعار.
 */
object DownloadNotifier {

    private const val CHANNEL_ID = "downloads"
    const val NOTIF_ID = 4001

    private var lastSync = 0L
    private var lastCategory = -1   // 0 لا شيء، 1 تنزيل جارٍ، 2 متوقف مؤقتاً

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "التنزيلات", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    /** علامة الإيقاف المؤقت (خطّان أبيضان) لشريط الإشعارات. */
    private fun pauseIcon(): Icon {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(24f, 18f, 42f, 78f), 4f, 4f, paint)
        canvas.drawRoundRect(RectF(54f, 18f, 72f, 78f), 4f, 4f, paint)
        return Icon.createWithBitmap(bitmap)
    }

    fun build(context: Context): Notification {
        ensureChannel(context)
        val items = DownloadsRepository.downloadList
        val active = items.filter { it.state == DownloadState.DOWNLOADING }
        val paused = items.filter { it.state == DownloadState.PAUSED && DownloadsRepository.isResumable(it) }
        val downloading = active.isNotEmpty()

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        builder.setContentIntent(openApp)
        builder.setOnlyAlertOnce(true)
        builder.setShowWhen(false)
        builder.setCategory(Notification.CATEGORY_PROGRESS)
        builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (downloading) {
            builder.setSmallIcon(android.R.drawable.stat_sys_download)   // سهم التنزيل القياسي
            builder.setColor(0xFF1B5E20.toInt())                        // أخضر داكن (لون السهم في قائمة الإشعارات)
            builder.setOngoing(true)
            if (active.size == 1) {
                val item = active[0]
                builder.setContentTitle(item.fileName)
                val speed = item.speed
                builder.setContentText(if (item.totalBytes > 0) "${item.progress}%  •  $speed" else speed)
                if (item.totalBytes > 0) builder.setProgress(100, item.progress, false)
                else builder.setProgress(0, 0, true)
            } else {
                builder.setContentTitle("جاري تنزيل ${active.size} ملفات")
                builder.setContentText(active[0].fileName + "  +" + (active.size - 1))
                builder.setProgress(0, 0, true)
            }
            val pauseIntent = PendingIntent.getService(
                context, 1,
                Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
                flags
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_media_pause), "إيقاف مؤقت", pauseIntent
                ).build()
            )
        } else {
            builder.setSmallIcon(pauseIcon())   // علامة الإيقاف المؤقت
            builder.setOngoing(false)
            builder.setContentTitle("التنزيل متوقف مؤقتاً")
            if (paused.size == 1) {
                builder.setContentText(paused[0].fileName)
                if (paused[0].totalBytes > 0) builder.setProgress(100, paused[0].progress, false)
            } else {
                builder.setContentText("${paused.size} ملفات متوقفة مؤقتاً")
            }
            val resumeIntent = if (Build.VERSION.SDK_INT >= 26) {
                PendingIntent.getForegroundService(
                    context, 2,
                    Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_RESUME_ALL),
                    flags
                )
            } else {
                PendingIntent.getService(
                    context, 2,
                    Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_RESUME_ALL),
                    flags
                )
            }
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_media_play), "استئناف", resumeIntent
                ).build()
            )
        }
        return builder.build()
    }

    /**
     * يزامن الخدمة والإشعار مع حالة التنزيلات. يُستدعى من الخيط الرئيسي.
     * تغيّر الحالة (جارٍ/متوقف/لا شيء) يُطبَّق فوراً، أما تحديث التقدّم فيُخفَّف إلى مرة كل ثانية.
     */
    fun sync(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        var active = 0
        var paused = 0
        for (item in DownloadsRepository.downloadList) {
            if (item.state == DownloadState.DOWNLOADING) active++
            else if (item.state == DownloadState.PAUSED && DownloadsRepository.isResumable(item)) paused++
        }
        val category = if (active > 0) 1 else if (paused > 0) 2 else 0
        val now = SystemClock.elapsedRealtime()
        if (!force && category == lastCategory && now - lastSync < 1000L) return
        lastCategory = category
        lastSync = now

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val service = DownloadService.instance

        if (category == 1) {
            if (service == null) {
                startService(app, nm)
            } else {
                service.renewWakeLock()
                nm.notify(NOTIF_ID, build(app))
            }
        } else {
            if (service != null) {
                service.stopForeground(Service.STOP_FOREGROUND_DETACH)
                service.stopSelf()
            }
            if (category == 2) nm.notify(NOTIF_ID, build(app)) else nm.cancel(NOTIF_ID)
        }
    }

    private fun startService(app: Context, nm: NotificationManager) {
        val intent = Intent(app, DownloadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        } catch (e: Exception) {
            // النظام لا يسمح ببدء الخدمة والتطبيق في الخلفية: نعرض الإشعار فقط والتنزيل يستمر ما دامت العملية حيّة
            try { nm.notify(NOTIF_ID, build(app)) } catch (_: Exception) { }
        }
    }
}
