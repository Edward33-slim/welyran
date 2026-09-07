package com.downloads10.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.net.URLEncoder

class BrowserActivity : AppCompatActivity() {
    private data class Tab(val web: WebView, var title: String = "صفحة جديدة", var url: String = HOME)
    private data class Shortcut(var title: String, var url: String)

    private lateinit var container: FrameLayout
    private lateinit var address: EditText
    private lateinit var tabsButton: TextView
    private var tabs = mutableListOf<Tab>()
    private var current = 0
    private var darkWeb = false
    private var blockImages = false
    private var desktop = false
    private var fontScale = 100
    private val bookmarks = mutableListOf<Shortcut>()
    private val shortcuts = mutableListOf<Shortcut>()

    companion object {
        const val HOME = "https://www.google.com/"
        private const val PREF = "browser10"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadData()
        buildUi()
        addTab(HOME)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.settings.loadsImagesAutomatically = !blockImages
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
        w.settings.setSupportMultipleWindows(true)
        w.settings.javaScriptCanOpenWindowsAutomatically = true
        w.settings.cacheMode = WebSettings.LOAD_DEFAULT
        w.settings.textZoom = fontScale
        if (desktop) {
            w.settings.userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
        } else {
            w.settings.userAgentString = WebSettings.getDefaultUserAgent(this)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                w.settings,
                if (darkWeb) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (isBlocked(u)) return true
                return false
            }
            override fun onPageFinished(view: WebView, url: String) {
                address.setText(url)
                tabs.getOrNull(current)?.apply {
                    this.url = url
                    title = view.title ?: url
                }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return if (isBlocked(request.url.toString())) {
                    WebResourceResponse("text/plain", "utf-8", null)
                } else super.shouldInterceptRequest(view, request)
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                tabs.getOrNull(current)?.title = title
                tabsButton.text = "☰"
            }
        }

        w.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val suggested = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val item = DownloadRepository.add(this, url, suggested)
            DownloadServiceStart.start(this, item.id)
            Toast.makeText(this, "أضيف إلى Downloads10", Toast.LENGTH_SHORT).show()
        }
        return w
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 5, 4, 5)
            setBackgroundColor(Color.rgb(45, 45, 45))
        }

        tabsButton = topButton("☰") { menu() }
        bar.addView(tabsButton, LinearLayout.LayoutParams(48, 48))

        bar.addView(topButton("+") { tabsDialog() }, LinearLayout.LayoutParams(44, 48))

        address = EditText(this).apply {
            setSingleLine(true)
            hint = "عنوان أو بحث"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(12, 0, 12, 0)
            setBackgroundColor(Color.rgb(65, 65, 65))
            setOnEditorActionListener { _, _, _ ->
                navigate(text.toString())
                true
            }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, 48, 1f).apply {
            setMargins(4, 0, 4, 0)
        })

        bar.addView(topButton("★") { addBookmark() }, LinearLayout.LayoutParams(44, 48))
        bar.addView(topButton("⌂") { currentWeb().loadUrl(HOME) }, LinearLayout.LayoutParams(44, 48))
        root.addView(bar)

        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun topButton(text: String, action: () -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setOnClickListener { action() }
        }

    private fun addTab(url: String) {
        val w = newWebView()
        val tab = Tab(w, "صفحة جديدة", url)
        tabs.add(tab)
        current = tabs.lastIndex
        container.removeAllViews()
        container.addView(w, FrameLayout.LayoutParams(-1, -1))
        tabsButton.text = "☰"
        w.loadUrl(url)
    }

    private fun currentWeb(): WebView = tabs[current].web

    private fun navigate(raw: String) {
        var q = raw.trim()
        if (q.isBlank()) return
        val url = if (q.startsWith("http://") || q.startsWith("https://")) q
        else if (q.contains(".") && !q.contains(" ")) "https://$q"
        else "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        currentWeb().loadUrl(url)
        address.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun tabsDialog() {
        val names = tabs.mapIndexed { i, t -> "${i + 1}. ${t.title}" }.toMutableList()
        names.add("صفحة جديدة")
        names.add("إغلاق كل الصفحات")
        AlertDialog.Builder(this)
            .setTitle("الصفحات المفتوحة")
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which < tabs.size -> selectTab(which)
                    which == tabs.size -> addTab(HOME)
                    else -> {
                        tabs.forEach { it.web.destroy() }
                        tabs.clear()
                        addTab(HOME)
                    }
                }
            }.show()
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        current = index
        container.removeAllViews()
        container.addView(tabs[current].web, FrameLayout.LayoutParams(-1, -1))
        address.setText(tabs[current].web.url ?: tabs[current].url)
        tabsButton.text = "☰"
    }

    private fun addBookmark() {
        val t = tabs.getOrNull(current) ?: return
        val url = t.web.url ?: return
        val title = t.web.title?.ifBlank { url } ?: url
        if (bookmarks.none { it.url == url }) {
            bookmarks.add(Shortcut(title, url))
            saveData()
            Toast.makeText(this, "تمت إضافة المفضلة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun menu() {
        val options = arrayOf(
            "المفضلة",
            "مانع الإعلانات",
            "الوضع الداكن للمواقع",
            "تكبير/تصغير الخط",
            "ترجمة إلى العربية",
            "وضع سطح المكتب",
            "حجب الصور والفيديو",
            "فتح Downloads10"
        )
        AlertDialog.Builder(this)
            .setTitle("☰ المتصفح")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> bookmarksDialog()
                    1 -> adBlockDialog()
                    2 -> { darkWeb = !darkWeb; applySettings() }
                    3 -> fontDialog()
                    4 -> translateCurrent()
                    5 -> { desktop = !desktop; applySettings() }
                    6 -> { blockImages = !blockImages; applySettings() }
                    7 -> finish()
                }
            }.show()
    }

    private fun bookmarksDialog() {
        val names = bookmarks.map { it.title }.toMutableList()
        names.add("تصدير HTML")
        names.add("استيراد HTML")
        AlertDialog.Builder(this)
            .setTitle("المفضلة")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < bookmarks.size) currentWeb().loadUrl(bookmarks[which].url)
                else if (which == bookmarks.size) exportBookmarks()
                else importBookmarks()
            }.show()
    }

    private fun adBlockDialog() {
        val state = if (adBlockEnabled()) "مفعّل" else "متوقف"
        AlertDialog.Builder(this)
            .setTitle("مانع الإعلانات — $state")
            .setMessage("يستخدم قائمة نطاقات وأنماط شائعة داخل WebView. لا يمكن ضمان حجب كل الإعلانات لأن المواقع تغيّر مصادر الإعلانات باستمرار.")
            .setPositiveButton(if (adBlockEnabled()) "إيقاف" else "تفعيل") { _, _ ->
                getSharedPreferences(PREF, 0).edit()
                    .putBoolean("adblock", !adBlockEnabled()).apply()
                currentWeb().reload()
            }
            .setNeutralButton("تحديث القائمة") { _, _ ->
                Toast.makeText(this, "قائمة الحجب المحلية محدثة داخل التطبيق", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun adBlockEnabled() = getSharedPreferences(PREF, 0).getBoolean("adblock", true)

    private fun isBlocked(url: String): Boolean {
        if (!adBlockEnabled()) return false
        val u = url.lowercase()
        val patterns = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adsystem.com", "adnxs.com", "taboola.com",
            "outbrain.com", "popads.", "popcash.", "/ads/", "/advertising/"
        )
        return patterns.any { u.contains(it) }
    }

    private fun fontDialog() {
        val values = arrayOf("80%", "90%", "100%", "110%", "125%", "150%", "175%", "200%")
        AlertDialog.Builder(this)
            .setTitle("حجم الخط")
            .setItems(values) { _, which ->
                fontScale = values[which].removeSuffix("%").toInt()
                applySettings()
            }.show()
    }

    private fun translateCurrent() {
        val url = currentWeb().url ?: return
        val target = "https://translate.google.com/translate?sl=auto&tl=ar&u=" +
                URLEncoder.encode(url, "UTF-8")
        currentWeb().loadUrl(target)
    }

    private fun applySettings() {
        tabs.forEach { tab ->
            val s = tab.web.settings
            s.loadsImagesAutomatically = !blockImages
            s.textZoom = fontScale
            s.userAgentString = if (desktop)
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
            else WebSettings.getDefaultUserAgent(this)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    s,
                    if (darkWeb) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
            tab.web.reload()
        }
    }

    private fun loadData() {
        val p = getSharedPreferences(PREF, 0)
        darkWeb = p.getBoolean("dark", false)
        blockImages = p.getBoolean("media", false)
        desktop = p.getBoolean("desktop", false)
        fontScale = p.getInt("font", 100)
        p.getStringSet("bookmarks", emptySet())?.forEach { line ->
            val parts = line.split("\u0001", limit = 2)
            if (parts.size == 2) bookmarks.add(Shortcut(parts[0], parts[1]))
        }
    }

    private fun saveData() {
        val set = bookmarks.map { it.title + "\u0001" + it.url }.toSet()
        getSharedPreferences(PREF, 0).edit()
            .putStringSet("bookmarks", set)
            .putBoolean("dark", darkWeb)
            .putBoolean("media", blockImages)
            .putBoolean("desktop", desktop)
            .putInt("font", fontScale)
            .apply()
    }

    private fun exportBookmarks() {
        val html = buildString {
            append("<!doctype html><html><body>\n")
            bookmarks.forEach {
                append("<a href=\"").append(it.url).append("\">")
                    .append(it.title.replace("&", "&amp;")).append("</a><br>\n")
            }
            append("</body></html>")
        }
        val name = "Downloads10-bookmarks.html"
        openFileText(name, html)
    }

    private fun importBookmarks() {
        Toast.makeText(this, "استيراد HTML يحتاج اختيار ملف. استخدم إضافة ملف عبر مدير الملفات في النسخة التالية.", Toast.LENGTH_LONG).show()
    }

    private fun openFileText(name: String, text: String) {
        val file = java.io.File(cacheDir, name)
        file.writeText(text)
        Toast.makeText(this, "تم إنشاء $name داخل ذاكرة التطبيق", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (currentWeb().canGoBack()) currentWeb().goBack() else super.onBackPressed()
    }
}
