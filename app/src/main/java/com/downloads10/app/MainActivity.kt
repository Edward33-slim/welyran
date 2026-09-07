package com.downloads10.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var countText: TextView
    private var concurrency = 3
    private var showSpeed = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadRepository.load(this)
        requestNotifications()
        buildUi()
        startServiceCompat()
    }

    override fun onResume() {
        super.onResume()
        DownloadRepository.load(this)
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(45, 45, 45))
        }

        bar.addView(circleButton("🌐") { startActivity(Intent(this, BrowserActivity::class.java)) },
            LinearLayout.LayoutParams(48, 48))

        bar.addView(circleButton("+") { addDownloadDialog() },
            LinearLayout.LayoutParams(48, 48).apply { marginStart = 6 })

        val title = TextView(this).apply {
            text = "Downloads10"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        bar.addView(title, LinearLayout.LayoutParams(0, 48, 1f))

        bar.addView(circleButton("⋮") { settingsDialog() },
            LinearLayout.LayoutParams(48, 48))

        root.addView(bar)

        countText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(14, 10, 14, 6)
        }
        root.addView(countText)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 4, 10, 20)
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun circleButton(text: String, action: () -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = if (text == "+") 28f else 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(100, 100, 100))
            setOnClickListener { action() }
        }

    private fun addDownloadDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/file.zip"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة تنزيل")
            .setView(input)
            .setPositiveButton("تنزيل") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    val item = DownloadRepository.add(this, url)
                    DownloadServiceStart.start(this, item.id)
                    refresh()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun settingsDialog() {
        val choices = arrayOf(
            "عدد التنزيلات المتزامنة: $concurrency",
            "إظهار سرعة التنزيل: ${if (showSpeed) "نعم" else "لا"}"
        )
        AlertDialog.Builder(this)
            .setTitle("إعدادات Downloads10")
            .setItems(choices) { _, which ->
                if (which == 0) {
                    val nums = (1..10).map { it.toString() }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("التنزيلات المتزامنة")
                        .setItems(nums) { _, i ->
                            concurrency = i + 1
                            getSharedPreferences("settings", 0).edit()
                                .putInt("concurrency", concurrency).apply()
                            startServiceCompat()
                        }.show()
                } else {
                    showSpeed = !showSpeed
                    getSharedPreferences("settings", 0).edit()
                        .putBoolean("showSpeed", showSpeed).apply()
                    refresh()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun refresh() {
        concurrency = getSharedPreferences("settings", 0).getInt("concurrency", 3)
        showSpeed = getSharedPreferences("settings", 0).getBoolean("showSpeed", true)
        list.removeAllViews()
        val all = DownloadRepository.all()
        countText.text = "التنزيلات: ${all.size}  •  متزامن: $concurrency"
        all.forEach { item -> list.addView(card(item)) }
    }

    private fun card(item: DownloadItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(Color.rgb(28, 28, 28)) // always dark
        }

        val name = TextView(this).apply {
            text = item.fileName
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        card.addView(name)

        val status = TextView(this).apply {
            text = statusText(item)
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 6, 0, 4)
        }
        card.addView(status)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (item.totalBytes > 0)
                ((item.downloadedBytes * 1000) / item.totalBytes).toInt().coerceIn(0, 1000)
            else 0
        }
        card.addView(progress, LinearLayout.LayoutParams(-1, 6))

        val info = TextView(this).apply {
            text = buildString {
                append(formatBytes(item.downloadedBytes))
                if (item.totalBytes > 0) append(" / ${formatBytes(item.totalBytes)}")
                if (showSpeed && item.speedBytes > 0) append("  •  ${formatBytes(item.speedBytes)}/s")
            }
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 5, 0, 5)
        }
        card.addView(info)

        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        if (item.status == DownloadItem.STATUS_DOWNLOADING) {
            buttons.addView(actionButton("إيقاف") {
                send(DownloadService.ACTION_PAUSE, item.id)
                refresh()
            })
        } else if (item.status == DownloadItem.STATUS_PAUSED ||
            item.status == DownloadItem.STATUS_FAILED ||
            item.status == DownloadItem.STATUS_QUEUED) {
            buttons.addView(actionButton("استئناف") {
                send(DownloadService.ACTION_RESUME, item.id)
                refresh()
            })
        }
        buttons.addView(actionButton("حذف") {
            AlertDialog.Builder(this)
                .setTitle("حذف التنزيل")
                .setItems(arrayOf("حذف السجل فقط", "حذف السجل والملف", "إلغاء")) { _, which ->
                    if (which < 2) {
                        DownloadRepository.remove(this, item.id, which == 1)
                        refresh()
                    }
                }.show()
        })
        card.addView(buttons)

        val params = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, 10)
        }
        card.layoutParams = params
        return card
    }

    private fun actionButton(text: String, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(60, 60, 60))
            setOnClickListener { action() }
        }

    private fun statusText(i: DownloadItem): String = when (i.status) {
        DownloadItem.STATUS_DOWNLOADING -> "جارٍ التنزيل"
        DownloadItem.STATUS_PAUSED -> "متوقف مؤقتاً"
        DownloadItem.STATUS_COMPLETED -> "مكتمل"
        DownloadItem.STATUS_FAILED -> "فشل: ${i.error}"
        DownloadItem.STATUS_CANCELLED -> "ملغى"
        else -> "بالانتظار"
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble()
        var u = -1
        while (v >= 1024 && u < units.lastIndex) {
            v /= 1024.0
            u++
        }
        return String.format(Locale.US, "%.1f %s", v, units[u])
    }

    private fun send(action: String, id: Long) {
        val i = Intent(this, DownloadService::class.java).setAction(action)
            .putExtra(DownloadService.EXTRA_ID, id)
        ContextCompat.startForegroundService(this, i)
    }

    private fun startServiceCompat() {
        send(DownloadService.ACTION_START, -1L)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 55)
        }
    }
}

object DownloadServiceStart {
    fun start(context: android.content.Context, id: Long) {
        val i = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_ID, id)
        ContextCompat.startForegroundService(context, i)
    }
}
