package com.downls10

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * تصدير/استيراد العلامات عبر Storage Access Framework مثل المتصفحات الحديثة.
 * يدعم ملفات Chrome القياسية بصيغة Netscape Bookmark HTML.
 */
object BookmarksFileManager {
    const val EXPORT_REQUEST_CODE = 5101
    const val IMPORT_REQUEST_CODE = 5102

    fun startExport(activity: Activity, content: String): Pair<Boolean, String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "bookmarks.html")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DownloadsRepository.DOWNLOAD_DIRECTORY_NAME + "/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = activity.contentResolver.insert(collection, values)
                    ?: return Pair(false, "تعذّر إنشاء ملف العلامات داخل DownloadLS10")
                try {
                    activity.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        output.write(content.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("تعذّر فتح الملف للكتابة")
                    activity.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                } catch (e: Exception) {
                    runCatching { activity.contentResolver.delete(uri, null, null) }
                    throw e
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(DownloadsRepository.DOWNLOAD_DIRECTORY_NAME)
                if (!dir.exists() && !dir.mkdirs()) {
                    return Pair(false, "تعذّر إنشاء مجلد DownloadLS10")
                }
                File(dir, "bookmarks.html").writeText(content, Charsets.UTF_8)
            }
            Pair(true, "")
        } catch (e: Exception) {
            Pair(false, e.message ?: "خطأ غير معروف")
        }
    }

    fun startImport(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/xhtml+xml", "text/plain"))
        }
        activity.startActivityForResult(intent, IMPORT_REQUEST_CODE)
    }

    fun writeExport(context: android.content.Context, uri: Uri, content: String): Pair<Boolean, String> {
        return try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: return Pair(false, "تعذّر فتح الملف للكتابة")
            Pair(true, "")
        } catch (e: Exception) {
            Pair(false, e.message ?: "خطأ غير معروف")
        }
    }

    fun readImport(context: android.content.Context, uri: Uri): Pair<String?, String> {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return Pair(null, "تعذّر فتح الملف")
            Pair(text, "")
        } catch (e: Exception) {
            Pair(null, e.message ?: "خطأ غير معروف")
        }
    }
}
