package com.downls10

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView

class DownloadAdapter(
    private val context: Context,
    private val items: List<DownloadItem>,
    private var showSpeed: Boolean,
    private val onPauseResumeClick: (DownloadItem) -> Unit,
    private val onDeleteClick: (DownloadItem) -> Unit
) : BaseAdapter() {

    fun setShowSpeed(visible: Boolean) {
        showSpeed = visible
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_download, parent, false)
        val item = items[position]

        val tvFileIcon = view.findViewById<TextView>(R.id.tvFileIcon)
        val tvFileName = view.findViewById<TextView>(R.id.tvFileName)
        val tvFileSize = view.findViewById<TextView>(R.id.tvFileSize)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        val tvSpeed = view.findViewById<TextView>(R.id.tvSpeed)
        val btnPauseResume = view.findViewById<Button>(R.id.btnPauseResume)
        val btnDelete = view.findViewById<Button>(R.id.btnDelete)

        tvFileName.text = item.fileName
        tvFileIcon.text = getIconForExtension(item.extension)
        tvStatus.text = item.status

        val totalText = if (item.totalBytes > 0) formatBytes(item.totalBytes) else "؟"
        tvFileSize.text = formatBytes(item.downloadedBytes) + " / " + totalText

        if (showSpeed) {
            tvSpeed.visibility = View.VISIBLE
            tvSpeed.text = item.speed
        } else {
            tvSpeed.visibility = View.GONE
        }

        btnPauseResume.visibility = View.VISIBLE
        when (item.state) {
            DownloadState.DOWNLOADING -> btnPauseResume.text = "إيقاف"
            DownloadState.PAUSED -> btnPauseResume.text = "استئناف"
            DownloadState.COMPLETED -> btnPauseResume.visibility = View.GONE
            DownloadState.ERROR -> btnPauseResume.text = "إعادة"
        }

        btnPauseResume.setOnClickListener { onPauseResumeClick(item) }
        btnDelete.setOnClickListener { onDeleteClick(item) }

        view.setOnLongClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("رابط التنزيل", item.url))
            Toast.makeText(context, "تم نسخ رابط التنزيل", Toast.LENGTH_SHORT).show()
            true
        }

        return view
    }

    /** يعرض الحجم بوحدة مناسبة (KB / MB / GB) حتى للملفات الكبيرة جداً. */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L * 1024L) return (bytes / 1024L).toString() + " KB"
        val mb = bytes / (1024.0 * 1024.0)
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun getIconForExtension(ext: String): String {
        return when (ext.lowercase()) {
            "mp4", "mkv", "avi" -> "🎬"
            "mp3", "wav" -> "🎵"
            "apk" -> "📱"
            "pdf", "doc", "txt" -> "📄"
            "zip", "rar" -> "📦"
            "jpg", "png" -> "🖼️"
            else -> "📁"
        }
    }
}
