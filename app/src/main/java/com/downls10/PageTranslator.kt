package com.downls10

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * يترجم نص الصفحة المعروضة داخل الـ WebView مباشرة (بدون تحميل صفحة جديدة).
 * لا يفرض اتجاه الصفحة (RTL) حتى لا تنكسر تصاميم بعض المواقع (شاشة بيضاء).
 * يرسل دفعات الترجمة بالتوازي لتسريع النتيجة.
 */
object PageTranslator {

    private val executor = Executors.newFixedThreadPool(5)
    private val mainHandler = Handler(Looper.getMainLooper())

    private const val EXTRACT_JS = """
        (function() {
            var texts = [];
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                acceptNode: function(node) {
                    var p = node.parentNode;
                    if (!p) return NodeFilter.FILTER_REJECT;
                    var tag = p.tagName ? p.tagName.toLowerCase() : '';
                    if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'textarea') {
                        return NodeFilter.FILTER_REJECT;
                    }
                    if (!node.nodeValue || !node.nodeValue.trim()) {
                        return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                }
            });
            var n;
            while (n = walker.nextNode()) { texts.push(n.nodeValue); }
            return JSON.stringify(texts);
        })();
    """

    fun translatePage(webView: WebView, targetLang: String = "ar", onDone: (Boolean, String) -> Unit) {
        if (webView.url.isNullOrBlank()) {
            onDone(false, "لا توجد صفحة مفتوحة للترجمة")
            return
        }
        webView.evaluateJavascript(EXTRACT_JS) { rawResult ->
            try {
                val jsonString = unquoteJsResult(rawResult)
                val arr = JSONArray(jsonString)
                val originalTexts = (0 until arr.length()).map { arr.getString(it) }
                if (originalTexts.isEmpty()) {
                    onDone(false, "تعذّر استخراج نص من الصفحة (قد تكون فارغة أو لم تكتمل تحميلها)")
                    return@evaluateJavascript
                }

                val chunks = splitIntoChunks(originalTexts)
                val resultsByChunk = ConcurrentHashMap<Int, List<String>?>()
                val errorHolder = ConcurrentHashMap<Int, String>()
                val latch = CountDownLatch(chunks.size)

                chunks.forEachIndexed { index, chunkTexts ->
                    executor.execute {
                        var lastError = "فشل الاتصال بخدمة الترجمة"
                        val translated = translateChunk(chunkTexts, targetLang) { err -> lastError = err }
                        resultsByChunk[index] = translated
                        if (translated == null) errorHolder[index] = lastError
                        latch.countDown()
                    }
                }

                Thread {
                    latch.await(40, TimeUnit.SECONDS)
                    mainHandler.post {
                        val allTranslated = mutableListOf<String>()
                        var failed = false
                        var firstError = "فشل بالترجمة"
                        for (i in chunks.indices) {
                            val chunkResult = resultsByChunk[i]
                            if (chunkResult == null) {
                                failed = true
                                firstError = errorHolder[i] ?: firstError
                                allTranslated.addAll(chunks[i])
                            } else {
                                allTranslated.addAll(chunkResult)
                            }
                        }
                        if (failed && allTranslated.size != originalTexts.size) {
                            onDone(false, firstError)
                        } else if (allTranslated.size != originalTexts.size) {
                            onDone(false, "اختلاف عدد النصوص بعد الترجمة، حاول مجدداً")
                        } else {
                            applyTranslations(webView, allTranslated, onDone)
                        }
                    }
                }.start()
            } catch (e: Exception) {
                onDone(false, "تعذّر تحليل محتوى الصفحة: ${e.message}")
            }
        }
    }

    private fun unquoteJsResult(raw: String?): String {
        if (raw == null || raw == "null") return "[]"
        val s = raw.trim()
        return if (s.startsWith("\"") && s.endsWith("\"")) {
            JSONTokener(s).nextValue() as String
        } else s
    }

    /** يقسم النصوص إلى دفعات لا تتجاوز حد الطول، كل دفعة تُترجم بطلب منفصل بالتوازي */
    private fun splitIntoChunks(texts: List<String>): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var len = 0
        for (t in texts) {
            if (len + t.length + 5 > 1800) {
                if (current.isNotEmpty()) chunks.add(current)
                current = mutableListOf()
                len = 0
            }
            current.add(t)
            len += t.length + 5
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private fun translateChunk(texts: List<String>, targetLang: String, onError: (String) -> Unit): List<String>? {
        val separator = "\n@@@\n"
        val joined = texts.joinToString(separator)
        val translatedJoined = translateWithFallback(joined, targetLang, onError) ?: return null
        val parts = translatedJoined.split("\n@@@\n", "@@@")
        return if (parts.size == texts.size) parts else translatedJoined.split("\n").let {
            if (it.size == texts.size) it else texts
        }
    }

    private fun translateWithFallback(text: String, targetLang: String, onError: (String) -> Unit): String? {
        val primary = translateRaw(text, targetLang, "translate.googleapis.com", onError)
        if (primary != null) return primary
        return translateRaw(text, targetLang, "translate.google.com", onError)
    }

    private fun translateRaw(text: String, targetLang: String, host: String, onError: (String) -> Unit): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlStr = "https://$host/translate_a/single" +
                    "?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                onError("رفضت خدمة الترجمة الطلب (رمز الخطأ: $code) - قد تكون محجوبة من شبكتك")
                return null
            }

            val sb = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
            }

            val root = JSONArray(sb.toString())
            val segments = root.getJSONArray(0)
            val out = StringBuilder()
            for (i in 0 until segments.length()) {
                out.append(segments.getJSONArray(i).getString(0))
            }
            out.toString()
        } catch (e: java.net.UnknownHostException) {
            onError("تعذّر الوصول لخدمة الترجمة - تأكد من اتصال الإنترنت")
            null
        } catch (e: java.net.SocketTimeoutException) {
            onError("انتهت مهلة الاتصال بخدمة الترجمة")
            null
        } catch (e: Exception) {
            onError("خطأ بالاتصال: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun applyTranslations(webView: WebView, translated: List<String>, onDone: (Boolean, String) -> Unit) {
        val jsonArray = JSONArray(translated).toString()
        val applyJs = """
            (function() {
                var translated = $jsonArray;
                var idx = 0;
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(node) {
                        var p = node.parentNode;
                        if (!p) return NodeFilter.FILTER_REJECT;
                        var tag = p.tagName ? p.tagName.toLowerCase() : '';
                        if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'textarea') {
                            return NodeFilter.FILTER_REJECT;
                        }
                        if (!node.nodeValue || !node.nodeValue.trim()) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        return NodeFilter.FILTER_ACCEPT;
                    }
                });
                var n;
                while (n = walker.nextNode()) {
                    if (idx < translated.length) {
                        n.nodeValue = translated[idx];
                    }
                    idx++;
                }
            })();
        """
        webView.evaluateJavascript(applyJs) { onDone(true, "تمت الترجمة بنجاح") }
    }
}
