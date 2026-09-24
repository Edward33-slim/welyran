package com.downls10

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * تصدير/استيراد العلامات عبر Storage Access Framework مثل المتصفحات الحديثة.
 * يدعم ملفات Chrome القياسية بصيغة Netscape Bookmark HTML.
 */
object BookmarksFileManager {
    const val EXPORT_REQUEST_CODE = 5101
    const val IMPORT_REQUEST_CODE = 5102

    fun startExport(activity: Activity, content: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_TITLE, "bookmarks.html")
        }
        activity.startActivityForResult(intent, EXPORT_REQUEST_CODE)
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
