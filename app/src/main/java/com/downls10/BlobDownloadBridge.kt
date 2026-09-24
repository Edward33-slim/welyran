package com.downls10

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ينزّل روابط blob: (ملفات تُنشأ داخل الصفحة نفسها ولا يمكن طلبها من الشبكة).
 * تُقرأ داخل الصفحة عبر JavaScript وتُرسل للتطبيق على دفعات صغيرة.
 * كل عملية لها رمز عشوائي (token) ينشئه التطبيق، ولا تُقبل أي بيانات بدونه.
 */
class BlobDownloadBridge(context: Context) {

    private class Transfer(val suggestedName: String?, val mimeHint: String?) {
        @Volatile var sink: DownloadsRepository.LocalSink? = null
    }

    private val appContext = context.applicationContext
    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(webView: WebView, blobUrl: String, suggestedName: String?, mimeHint: String?) {
        val token = UUID.randomUUID().toString()
        transfers[token] = Transfer(suggestedName, mimeHint)

        val js = """
            (function() {
                var token = ${JSONObject.quote(token)};
                var url = ${JSONObject.quote(blobUrl)};
                var bridge = window.DownLS10Blob;
                if (!bridge) return;
                var xhr = new XMLHttpRequest();
                xhr.open('GET', url, true);
                xhr.responseType = 'blob';
                xhr.onload = function() {
                    var blob = xhr.response;
                    if (!blob) { bridge.fail(token, 'empty'); return; }
                    bridge.begin(token, blob.type || '', String(blob.size));
                    var CHUNK = 512 * 1024;
                    var offset = 0;
                    function next() {
                        if (offset >= blob.size) { bridge.end(token); return; }
                        var reader = new FileReader();
                        reader.onload = function() {
                            var r = String(reader.result);
                            bridge.chunk(token, r.substring(r.indexOf(',') + 1));
                            offset += CHUNK;
                            next();
                        };
                        reader.onerror = function() { bridge.fail(token, 'read error'); };
                        reader.readAsDataURL(blob.slice(offset, offset + CHUNK));
                    }
                    next();
                };
                xhr.onerror = function() { bridge.fail(token, 'network error'); };
                xhr.send();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        // تنظيف: إذا لم تبدأ العملية خلال دقيقتين نلغيها
        mainHandler.postDelayed({
            val t = transfers[token]
            if (t != null && t.sink == null) transfers.remove(token)
        }, 120_000L)
    }

    @JavascriptInterface
    fun begin(token: String, mime: String?, size: String?) {
        val t = transfers[token] ?: return
        if (t.sink != null) return
        val total = size?.toLongOrNull() ?: 0L
        val effectiveMime = if (!mime.isNullOrBlank()) mime else t.mimeHint
        val sink = DownloadsRepository.openLocalSink(appContext, t.suggestedName, effectiveMime, total, "blob:")
        if (sink == null) transfers.remove(token) else t.sink = sink
    }

    @JavascriptInterface
    fun chunk(token: String, base64: String?) {
        val t = transfers[token] ?: return
        val sink = t.sink ?: return
        if (base64.isNullOrEmpty()) return
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            DownloadsRepository.writeLocalSink(appContext, sink, bytes, bytes.size)
        } catch (e: Exception) {
            transfers.remove(token)
            DownloadsRepository.failLocalSink(appContext, sink, e.message)
        }
    }

    @JavascriptInterface
    fun end(token: String) {
        val t = transfers.remove(token) ?: return
        t.sink?.let { DownloadsRepository.finishLocalSink(appContext, it) }
    }

    @JavascriptInterface
    fun fail(token: String, message: String?) {
        val t = transfers.remove(token) ?: return
        val sink = t.sink
        if (sink != null) {
            DownloadsRepository.failLocalSink(appContext, sink, message)
        } else {
            val name = t.suggestedName
            DownloadsRepository.showMessage(
                appContext,
                "فشل تنزيل الملف" + if (name.isNullOrBlank()) "" else ": $name"
            )
        }
    }
}
