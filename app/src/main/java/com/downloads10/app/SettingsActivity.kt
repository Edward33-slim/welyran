package com.downloads10.app

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.*

class SettingsActivity : Activity() {
    private val folderRequest = 500
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(0xFF000000.toInt()) }
        val title = TextView(this).apply { text = "إعدادات Downloads10"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0,0,0,dp(18)) }
        root.addView(title)
        val folder = Button(this).apply { text = "مجلد التنزيل: ${folderLabel()}"; setTextColor(0xFFFFFFFF.toInt()) }
        folder.setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), folderRequest) }
        root.addView(folder)
        val speed = CheckBox(this).apply { text = "إظهار سرعة التنزيل"; isChecked = DownloadStore.showSpeed(this@SettingsActivity); setTextColor(0xFFFFFFFF.toInt()) }
        speed.setOnCheckedChangeListener { _, v -> DownloadStore.setShowSpeed(this, v) }
        root.addView(speed)
        val retry = CheckBox(this).apply { text = "إعادة المحاولة تلقائياً عند فشل الشبكة"; isChecked = DownloadStore.retry(this@SettingsActivity); setTextColor(0xFFFFFFFF.toInt()) }
        retry.setOnCheckedChangeListener { _, v -> DownloadStore.setRetry(this, v) }
        root.addView(retry)
        val countLabel = TextView(this).apply { text = "عدد التنزيلات المتزامنة: ${DownloadStore.concurrent(this@SettingsActivity)}"; textSize=17f; setTextColor(0xFFFFFFFF.toInt()); gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(18),0,dp(8)) }
        root.addView(countLabel)
        val seek = SeekBar(this).apply { max=9; progress=DownloadStore.concurrent(this@SettingsActivity)-1 }
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { val n=p+1; countLabel.text="عدد التنزيلات المتزامنة: $n"; DownloadStore.setConcurrent(this@SettingsActivity,n) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(seek)
        val info = TextView(this).apply { text="Downloads10\nNative Kotlin • Android 7+\nHTTP/HTTPS • Range Resume • حتى 10 تنزيلات متزامنة"; textSize=13f; setTextColor(0xFFAAAAAA.toInt()); setPadding(0,dp(24),0,0) }
        root.addView(info)
        setContentView(root)
    }
    private fun folderLabel(): String = DownloadStore.folder(this)?.let { uri -> runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfterLast(':') }.getOrDefault("محدد") } ?: "غير محدد"
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==folderRequest && resultCode==RESULT_OK){ val uri=data?.data ?: return; val flags=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); runCatching { contentResolver.takePersistableUriPermission(uri,flags) }; DownloadStore.setFolder(this,uri.toString()); recreate() } }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
