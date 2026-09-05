package com.downloads10.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.text.InputType
import android.view.*
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private val selected = mutableSetOf<Long>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == DownloadService.ACTION_CHANGED) refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        findViewById<Button>(R.id.addButton).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<CheckBox>(R.id.selectAll).setOnCheckedChangeListener { _, checked ->
            if (checked) selected.addAll(DownloadStore.all(this).map { it.id }) else selected.clear()
            refresh()
        }
        findViewById<Button>(R.id.deleteSelected).setOnClickListener { deleteSelected() }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 21)
        refresh()
        startService(Intent(this, DownloadService::class.java).apply { action = DownloadService.ACTION_START })
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(DownloadService.ACTION_CHANGED), if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
        refresh()
    }
    override fun onPause() { unregisterReceiver(receiver); super.onPause() }

    private fun showAddDialog() {
        val input = EditText(this).apply { hint = "https://example.com/file.zip"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(false) }
        AlertDialog.Builder(this).setTitle("إضافة تنزيل").setView(input).setNegativeButton("إلغاء", null).setPositiveButton("تنزيل") { _, _ ->
            val url = input.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) { toast("الرابط يجب أن يبدأ بـ http:// أو https://"); return@setPositiveButton }
            val folder = DownloadStore.folder(this)
            if (folder.isNullOrBlank()) { toast("اختر مجلد التنزيل أولاً من الإعدادات"); return@setPositiveButton }
            val item = DownloadItem(System.currentTimeMillis(), url, "", createdAt = System.currentTimeMillis())
            val items = DownloadStore.all(this); items.add(0, item); DownloadStore.save(this, items)
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_START))
            refresh()
        }.show()
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        DownloadStore.all(this).forEach { item -> list.addView(card(item)) }
        findViewById<CheckBox>(R.id.selectAll).setOnCheckedChangeListener(null)
        findViewById<CheckBox>(R.id.selectAll).isChecked = selected.isNotEmpty() && selected.size == DownloadStore.all(this).size
        findViewById<CheckBox>(R.id.selectAll).setOnCheckedChangeListener { _, checked ->
            if (checked) selected.addAll(DownloadStore.all(this).map { it.id }) else selected.clear(); refresh()
        }
    }

    private fun card(item: DownloadItem): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        box.background = getDrawable(when (item.status) { DownloadItem.Status.DOWNLOADING -> R.drawable.bg_downloading; DownloadItem.Status.COMPLETED -> R.drawable.bg_completed; else -> R.drawable.bg_normal })
        val lp = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
        box.layoutParams = lp
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val check = CheckBox(this).apply { isChecked = selected.contains(item.id) }
        check.setOnCheckedChangeListener { _, checked -> if (checked) selected.add(item.id) else selected.remove(item.id) }
        head.addView(check)
        val name = TextView(this).apply { text = if (item.fileName.isBlank()) item.url.substringAfterLast('/').substringBefore('?').ifBlank { "تنزيل ${item.id}" } else item.fileName; textSize = 17f; setTextColor(if (item.status == DownloadItem.Status.DOWNLOADING) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) }
        head.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(head)

        val status = TextView(this).apply { text = statusText(item); textSize = 13f; setTextColor(if (item.status == DownloadItem.Status.DOWNLOADING) 0xFF111111.toInt() else 0xFFDDDDDD.toInt()) }
        box.addView(status)
        if (item.size > 0) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = ((item.downloaded.toDouble() / item.size) * 1000).toInt().coerceIn(0, 1000) }
            box.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { setMargins(0, dp(8), 0, dp(4)) })
        }
        if (DownloadStore.showSpeed(this) && item.status == DownloadItem.Status.DOWNLOADING) {
            box.addView(TextView(this).apply { text = "↓ ${formatSpeed(item.speed)}"; textSize = 14f; setTextColor(0xFF111111.toInt()) })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
        val toggle = Button(this).apply { text = if (item.status == DownloadItem.Status.DOWNLOADING) "إيقاف مؤقت" else "استئناف"; isEnabled = item.status != DownloadItem.Status.COMPLETED }
        toggle.setOnClickListener { sendAction(if (item.status == DownloadItem.Status.DOWNLOADING) DownloadService.ACTION_PAUSE else DownloadService.ACTION_RESUME, item.id) }
        actions.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f))
        val copy = Button(this).apply { text = "نسخ الرابط" }; copy.setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Downloads10", item.url)); toast("تم نسخ الرابط") }; actions.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val more = Button(this).apply { text = "⋮" }; more.setOnClickListener { showItemMenu(item) }; actions.addView(more, LinearLayout.LayoutParams(0, -2, .55f))
        box.addView(actions)
        return box
    }

    private fun showItemMenu(item: DownloadItem) {
        val choices = arrayOf("تحديث الرابط", "حذف من التطبيق فقط", "حذف من التطبيق ومن الملفات")
        AlertDialog.Builder(this).setTitle(item.fileName.ifBlank { "التنزيل" }).setItems(choices) { _, which ->
            when (which) { 0 -> updateUrl(item); 1 -> removeItem(item, false); 2 -> removeItem(item, true) }
        }.show()
    }

    private fun updateUrl(item: DownloadItem) {
        val input = EditText(this).apply { setText(item.url); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle("تحديث رابط التنزيل").setView(input).setNegativeButton("إلغاء", null).setPositiveButton("تحديث") { _, _ ->
            val u = input.text.toString().trim(); if (!u.startsWith("http://") && !u.startsWith("https://")) { toast("رابط غير صالح"); return@setPositiveButton }
            val items = DownloadStore.all(this); items.firstOrNull { it.id == item.id }?.apply { url = u; status = DownloadItem.Status.QUEUED; error = ""; speed = 0 }; DownloadStore.save(this, items); sendAction(DownloadService.ACTION_START, item.id); refresh()
        }.show()
    }

    private fun removeItem(item: DownloadItem, deleteFile: Boolean) {
        if (deleteFile && item.localUri.isNotBlank()) runCatching { DocumentsContract.deleteDocument(contentResolver, Uri.parse(item.localUri)) }
        val items = DownloadStore.all(this).filterNot { it.id == item.id }; DownloadStore.save(this, items); sendAction(DownloadService.ACTION_CANCEL, item.id); selected.remove(item.id); refresh()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) return
        val ids = selected.toSet()
        AlertDialog.Builder(this).setTitle("حذف التنزيلات المحددة").setItems(arrayOf("حذف من التطبيق فقط", "حذف من التطبيق ومن الملفات", "إلغاء")) { _, which ->
            if (which == 2) return@setItems
            val delFile = which == 1
            DownloadStore.all(this).filter { ids.contains(it.id) }.forEach { if (delFile && it.localUri.isNotBlank()) runCatching { DocumentsContract.deleteDocument(contentResolver, Uri.parse(it.localUri)) }; sendAction(DownloadService.ACTION_CANCEL, it.id) }
            DownloadStore.save(this, DownloadStore.all(this).filterNot { ids.contains(it.id) }); selected.clear(); refresh()
        }.show()
    }

    private fun sendAction(action: String, id: Long? = null) { startService(Intent(this, DownloadService::class.java).apply { this.action = action; if (id != null) putExtra(DownloadService.EXTRA_ID, id) }) }
    private fun statusText(i: DownloadItem): String = when (i.status) {
        DownloadItem.Status.DOWNLOADING -> "${formatBytes(i.downloaded)} / ${if (i.size > 0) formatBytes(i.size) else "حجم غير معروف"} • تنزيل الآن"
        DownloadItem.Status.COMPLETED -> "مكتمل • ${formatBytes(i.downloaded)}"
        DownloadItem.Status.PAUSED -> "متوقف مؤقتاً • ${formatBytes(i.downloaded)}${if (i.size > 0) " / ${formatBytes(i.size)}" else ""}"
        DownloadItem.Status.QUEUED -> "في الانتظار"
        DownloadItem.Status.FAILED -> "فشل • ${i.error}"
        DownloadItem.Status.CANCELLED -> "ملغى"
    }
    private fun formatBytes(v: Long): String { if (v < 0) return "—"; val units = arrayOf("B","KB","MB","GB","TB"); var n=v.toDouble(); var k=0; while(n>=1024 && k<units.lastIndex){n/=1024;k++}; return if(k==0) "${n.toLong()} ${units[k]}" else String.format(Locale.US,"%.2f %s",n,units[k]) }
    private fun formatSpeed(v: Long) = "${formatBytes(v)}/s"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
