package com.downls10

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Patterns
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import androidx.browser.customtabs.CustomTabsIntent
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

private data class Tab(
    val id: Long,
    val webView: WebView,
    var isHome: Boolean = true,
    var title: String = "صفحة جديدة",
    var desktopMode: Boolean = false,
    val backStack: MutableList<String> = mutableListOf(),
    val forwardStack: MutableList<String> = mutableListOf(),
    // --- حفظ واستعادة موضع التمرير عند الرجوع/التقدم ---
    /** آخر موضع تمرير (بالبكسل) لكل رابط زاره هذا التبويب */
    val scrollPositions: MutableMap<String, Int> = mutableMapOf(),
    /** true أثناء تحميل صفحة جديدة (لا نسجّل التمرير حينها لأن الصفحة تبدأ من الصفر) */
    var pageLoading: Boolean = false,
    /** الموضع المطلوب استعادته الآن، أو -1 إذا لا توجد استعادة جارية */
    var pendingRestoreY: Int = -1,
    /** رقم يزيد عند بدء/إلغاء كل استعادة لإيقاف المحاولات القديمة */
    var restoreGeneration: Int = 0
)

class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_OPEN_URL = "extra_open_url"
        const val EXTRA_DIRECT_BROWSER = "extra_direct_browser"
    }

    private lateinit var webViewContainer: FrameLayout
    private lateinit var editUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTabs: Button
    private lateinit var downloadBar: View
    private lateinit var downloadBarProgress: ProgressBar
    private lateinit var downloadBarFileName: TextView
    private lateinit var downloadBarStatus: TextView

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0
    private var customTabMode = false
    private var customTabToolbar: View? = null

    private var hideMedia = false
    private var nightMode = false
    private var mobileUA: String = ""
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

    private var pendingStorageAction: (() -> Unit)? = null
    private val STORAGE_PERM_CODE = 4102
    private val ANDROID_PERM_CODE = 4103
    private val FILE_CHOOSER_CODE = 4104

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingBookmarkExportContent: String? = null
    private var lastConfirmedSafetyUrl: String? = null
    private val bgExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val PREFS = "browser_settings"
    private fun settingsPrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cameraAllowed() = settingsPrefs().getBoolean("camera", false)
    private fun micAllowed() = settingsPrefs().getBoolean("mic", false)
    private fun locationAllowed() = settingsPrefs().getBoolean("location", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customTabMode = isCustomTabIntent(intent)
        setContentView(R.layout.activity_browser)

        webViewContainer = findViewById(R.id.webViewContainer)
        editUrl = findViewById(R.id.editUrl)
        progressBar = findViewById(R.id.progressBar)
        downloadBar = findViewById(R.id.downloadBar)
        downloadBarProgress = findViewById(R.id.downloadBarProgress)
        downloadBarFileName = findViewById(R.id.downloadBarFileName)
        downloadBarStatus = findViewById(R.id.downloadBarStatus)
        findViewById<Button>(R.id.downloadBarOpen).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        }
        findViewById<Button>(R.id.downloadBarClose).setOnClickListener {
            downloadBar.visibility = View.GONE
        }
        val btnBookmarkStar = findViewById<Button>(R.id.btnBookmarkStar)
        val btnForward = findViewById<Button>(R.id.btnForward)
        btnTabs = findViewById(R.id.btnTabs)
        val btnBrowserMenu = findViewById<Button>(R.id.btnBrowserMenu)

        CookieManager.getInstance().setAcceptCookie(true)
        DownloadsRepository.ensureLoaded(this)
        DownloadsRepository.addListener(downloadBarListener)
        updateDownloadBar()
        hideMedia = settingsPrefs().getBoolean("hideMedia", false)
        nightMode = settingsPrefs().getBoolean("nightMode", false)

        editUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                loadFromAddressBar()
                hideKeyboard()
                true
            } else false
        }

        btnBookmarkStar.setOnClickListener { addCurrentPageToBookmarks() }
        btnForward.setOnClickListener {
            val tab = currentTab()
            if (!tab.isHome && tab.webView.canGoForward()) {
                tab.webView.goForward()
            } else {
                Toast.makeText(this, "لا توجد صفحة للتقدّم إليها", Toast.LENGTH_SHORT).show()
            }
        }
        btnTabs.setOnClickListener { showTabsDialog() }
        btnBrowserMenu.setOnClickListener { showBrowserMenu() }

        if (customTabMode) setupCustomTabUi()

        if (savedInstanceState != null && savedInstanceState.getInt("tabCount", -1) >= 0) {
            restoreTabsFromInstanceState(savedInstanceState)
        } else {
            restoreTabsOrCreateHome()
        }
        handleOpenUrlIntent(intent)
        requestBrowserRoleIfNeeded()
    }

    private fun isCustomTabIntent(intent: Intent?): Boolean {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return false
        return intent.hasExtra(CustomTabsIntent.EXTRA_SESSION) ||
            intent.hasExtra("androidx.browser.customtabs.extra.SESSION") ||
            intent.hasExtra("android.support.customtabs.extra.SESSION_ID") ||
            intent.hasExtra("androidx.browser.customtabs.extra.SESSION_ID")
    }

    private fun setupCustomTabUi() {
        val addressBar = findViewById<View>(R.id.addressBar)
        addressBar.visibility = View.GONE

        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? ViewGroup ?: return
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(6, 0, 6, 0)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val menuButton = Button(this).apply {
            text = "⋮"
            textSize = 25f
            setTextColor(Color.WHITE)
            background = null
            setPadding(4, 0, 4, 0)
            setOnClickListener { showCustomTabMenu(this) }
        }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(48, 52))

        val domain = TextView(this).apply {
            text = customTabDomain(intent?.dataString)
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(8, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(0, 52, 1f)
            tag = "downls10_cct_domain"
        }
        toolbar.addView(domain)

        val closeButton = Button(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            background = null
            setPadding(4, 0, 4, 0)
            setOnClickListener { finish() }
        }
        toolbar.addView(closeButton, LinearLayout.LayoutParams(48, 52))

        val toolbarId = View.generateViewId()
        toolbar.id = toolbarId
        root.addView(toolbar, RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 52
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) })
        customTabToolbar = toolbar

        val webParams = webViewContainer.layoutParams as? RelativeLayout.LayoutParams
        webParams?.let {
            it.removeRule(RelativeLayout.BELOW)
            it.addRule(RelativeLayout.BELOW, toolbarId)
            webViewContainer.layoutParams = it
        }
    }

    private fun customTabDomain(url: String?): String {
        return try {
            Uri.parse(url ?: "").host?.takeIf { it.isNotBlank() } ?: "DownLS10"
        } catch (_: Exception) {
            "DownLS10"
        }
    }

    private fun updateCustomTabDomain(url: String?) {
        if (!customTabMode) return
        val root = customTabToolbar as? ViewGroup ?: return
        val domain = root.findViewWithTag<TextView>("downls10_cct_domain") ?: return
        domain.text = customTabDomain(url)
    }

    private fun showCustomTabMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.START)
        popup.menu.add("Open in DownLS10")
        popup.menu.add("نسخ الرابط")
        popup.menu.add("مشاركة")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Open in DownLS10" -> {
                    // لا ننشئ BrowserActivity ثانية هنا، لأن Android قد يعيد
                    // استخدام نفس الـ Activity عبر onNewIntent ثم يؤدي finish() إلى إغلاق
                    // المتصفح كله. نحول نفس الواجهة من Custom Tab إلى المتصفح الكامل
                    // ونفتح الرابط مباشرة بدون فحص HTTP البطيء.
                    val url = currentWebView().url.orEmpty()
                    if (url.isBlank()) {
                        exitCustomTabMode()
                    } else {
                        exitCustomTabMode()
                        createBrowsingTab(url)
                        persistTabs()
                    }
                    true
                }
                "نسخ الرابط" -> {
                    val url = currentWebView().url.orEmpty()
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                    Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
                    true
                }
                "مشاركة" -> {
                    val url = currentWebView().url.orEmpty()
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }, "مشاركة الرابط"))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun exitCustomTabMode() {
        if (!customTabMode) return
        customTabMode = false
        customTabToolbar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        customTabToolbar = null
        findViewById<View>(R.id.addressBar).visibility = View.VISIBLE
        val webParams = webViewContainer.layoutParams as? RelativeLayout.LayoutParams
        webParams?.let {
            it.removeRule(RelativeLayout.BELOW)
            it.addRule(RelativeLayout.BELOW, R.id.progressBar)
            webViewContainer.layoutParams = it
        }
        editUrl.setText(currentWebView().url.orEmpty())
    }

    /**
     * Android 10+ controls the browser role. This asks the user for consent
     * to make DownLS10 the default browser.
     */
    private fun requestBrowserRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) return
        if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) return

        try {
            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER),
                9201
            )
        } catch (_: Exception) {
            // If this Android build does not expose the role dialog,
            // the manifest filters still let the user select DownLS10
            // from the system's default-app settings.
        }
    }

    /**
     * يحوّل حالة WebView إلى نص قابل للحفظ بشكل دائم في SharedPreferences.
     * saveState() يحفظ سجل الرجوع/التقدم داخل WebView، لذلك لا يكفي حفظ URL الحالي فقط.
     */
    private fun serializeWebViewState(webView: WebView): String? {
        return try {
            val state = Bundle()
            val history = webView.saveState(state) ?: return null
            if (history.size <= 0) return null

            val parcel = Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** يعيد حالة WebView المحفوظة، بما فيها سجل الرجوع/التقدم. */
    private fun restoreSerializedWebViewState(webView: WebView, encoded: String?): Boolean {
        if (encoded.isNullOrBlank()) return false
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val state = Bundle.CREATOR.createFromParcel(parcel)
                state.classLoader = WebView::class.java.classLoader
                webView.restoreState(state) != null
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tabCount", tabs.size)
        outState.putInt("currentTabIndex", currentTabIndex)
        tabs.forEachIndexed { i, tab ->
            outState.putBoolean("tab_${i}_isHome", tab.isHome)
            outState.putBoolean("tab_${i}_desktop", tab.desktopMode)
            outState.putStringArrayList("tab_${i}_back", ArrayList(tab.backStack))
            outState.putStringArrayList("tab_${i}_forward", ArrayList(tab.forwardStack))
            if (!tab.isHome) {
                val webState = Bundle()
                tab.webView.saveState(webState)
                outState.putBundle("tab_${i}_webstate", webState)
            }
        }
        // مهم: onSaveInstanceState وحده لا يكفي لإعادة تشغيل التطبيق لاحقًا.
        persistTabs()
    }

    /** يستعيد التبويبات من حالة أندرويد المحفوظة (تستخدم ذاكرة WebView المؤقتة بدل تحميل الصفحة من الإنترنت من جديد) */
    private fun restoreTabsFromInstanceState(state: Bundle) {
        val count = state.getInt("tabCount", 0)
        for (i in 0 until count) {
            val isHome = state.getBoolean("tab_${i}_isHome", true)
            val desktop = state.getBoolean("tab_${i}_desktop", false)
            val back = state.getStringArrayList("tab_${i}_back") ?: arrayListOf()
            val forward = state.getStringArrayList("tab_${i}_forward") ?: arrayListOf()
            val webState = state.getBundle("tab_${i}_webstate")

            val webView = WebView(this)
            setupWebView(webView)
            val cleanedUA = buildChromeLikeUserAgent(webView.settings.userAgentString)
            webView.settings.userAgentString = if (desktop) desktopUA else cleanedUA
            if (mobileUA.isEmpty()) mobileUA = cleanedUA

            val tab = Tab(id = System.currentTimeMillis() + i, webView = webView, isHome = isHome, desktopMode = desktop)
            tab.backStack.addAll(back)
            tab.forwardStack.addAll(forward)
            tabs.add(tab)

            if (!isHome && webState != null) {
                webView.restoreState(webState)
            }
        }
        val idx = state.getInt("currentTabIndex", 0).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        if (tabs.isEmpty()) {
            createHomeTab()
        } else {
            switchToTab(idx)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenUrlIntent(intent)
    }

    private fun handleOpenUrlIntent(intent: Intent?) {
        // روابط قادمة من تطبيقات أخرى (مثل ChatGPT) تصل عادةً كـ ACTION_VIEW + data URI.
        // كما ندعم ACTION_SEND للنص/الرابط المشترك من ChatGPT أو مدير الملفات أو المتصفح.
        // EXTRA_OPEN_URL يبقى مدعومًا للتنقل الداخلي في التطبيق.
        val extraUrl = intent?.getStringExtra(EXTRA_OPEN_URL)
        val viewUrl = intent?.dataString?.takeIf {
            intent.action == Intent.ACTION_VIEW &&
                (it.startsWith("http://", ignoreCase = true) ||
                 it.startsWith("https://", ignoreCase = true) ||
                 it.startsWith("ftp://", ignoreCase = true) ||
                 it.startsWith("magnet:", ignoreCase = true))
        }
        val sharedText = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        } else null

        val incomingUrl = extraUrl?.takeIf { it.isNotBlank() }
            ?: viewUrl
            ?: sharedText?.takeIf { isSupportedSharedUrl(it) }

        if (!incomingUrl.isNullOrBlank()) {
            if (intent?.getBooleanExtra(EXTRA_DIRECT_BROWSER, false) == true) {
                // من زر "Open in DownLS10": افتح الصفحة مباشرة في WebView
                // بدون فحص HTTP في الخلفية حتى يكون الانتقال فورياً.
                createBrowsingTab(incomingUrl)
                persistTabs()
            } else {
                openIncomingUrl(incomingUrl, intent?.type)
            }
        }
    }

    private fun isSupportedSharedUrl(text: String): Boolean {
        val value = text.trim()
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("ftp://", ignoreCase = true) ||
            value.startsWith("magnet:", ignoreCase = true)
    }

    /**
     * يعالج الرابط القادم من خارج المتصفح (مثل رابط من ChatGPT).
     *
     * الأولوية هنا للتنزيل:
     * 1) روابط الملفات الواضحة بالامتداد.
     * 2) روابط المشاركة التي يعرفها LinkResolver ويمكن تحويلها إلى رابط تنزيل.
     * 3) روابط Mega/Yandex/pCloud/MediaFire التي يستطيع محرك التنزيل حلّها.
     * 4) Content-Disposition / MIME من الخادم.
     *
     * أما صفحات الويب العادية فتُفتح في تبويب جديد.
     */
    private fun openIncomingUrl(rawUrl: String, incomingMimeType: String? = null) {
        val url = rawUrl.trim()
        if (url.isBlank()) return

        val lower = url.lowercase()
        val resolvedKnown = try { LinkResolver.resolve(url) } catch (_: Exception) { url }
        val knownDownloadShare =
            resolvedKnown != url ||
            LinkResolver.needsRemoteResolution(url) ||
            LinkResolver.needsHtmlResolution(url)

        val mimeLooksDownload = incomingMimeType?.lowercase()?.let { mime ->
            mime == "application/octet-stream" ||
                mime == "application/vnd.android.package-archive" ||
                mime == "application/zip" ||
                mime == "application/x-rar-compressed" ||
                mime == "application/x-7z-compressed" ||
                mime == "application/pdf" ||
                mime.startsWith("audio/") ||
                mime.startsWith("video/") ||
                mime.startsWith("application/")
        } ?: false

        if (isLikelyDownloadUrl(url) || knownDownloadShare || mimeLooksDownload ||
            lower.startsWith("ftp://") || lower.startsWith("magnet:")) {
            DownloadsRepository.startNewDownload(
                this,
                url,
                showToast = true,
                userAgent = currentWebView().settings.userAgentString,
                referer = null,
                mimeType = incomingMimeType
            )
            return
        }

        // نحصل على User-Agent من WebView على خيط الواجهة أولاً.
        // لا يجوز استدعاء أي WebView API من خيط الخلفية.
        val ua = currentWebView().settings.userAgentString

        // نفحص ترويسات الرابط في الخلفية حتى لا تتجمد واجهة المتصفح.
        bgExecutor.execute {
            val probe = try {
                DownloadManagerEngine().probe(url, ua, null)
            } catch (_: Exception) {
                null
            }

            val disposition = probe?.contentDisposition.orEmpty()
            val contentType = probe?.contentType.orEmpty().lowercase()
            val isAttachment = disposition.contains("attachment", ignoreCase = true)
            val isBinaryDownload =
                contentType.startsWith("application/octet-stream") ||
                contentType.contains("application/x-7z") ||
                contentType.contains("application/zip") ||
                contentType.contains("application/x-rar") ||
                contentType.contains("application/vnd.android.package-archive") ||
                contentType == "application/pdf"

            mainHandler.post {
                if (isAttachment || isBinaryDownload) {
                    DownloadsRepository.startNewDownload(
                        this,
                        url,
                        showToast = true,
                        userAgent = ua,
                        referer = null,
                        mimeType = probe?.contentType ?: incomingMimeType
                    )
                } else {
                    createBrowsingTab(url)
                }
            }
        }
    }

    private fun isLikelyDownloadUrl(url: String): Boolean {
        val path = try { Uri.parse(url).path.orEmpty().lowercase() } catch (_: Exception) { "" }
        val fileExtensions = listOf(
            ".apk", ".xapk", ".apks", ".zip", ".rar", ".7z", ".tar", ".gz",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".txt", ".csv", ".epub", ".mobi", ".iso", ".exe", ".msi",
            ".mp3", ".wav", ".flac", ".m4a", ".mp4", ".mkv", ".avi",
            ".jpg", ".jpeg", ".png", ".webp", ".gif"
        )
        return fileExtensions.any { path.endsWith(it) }
    }

    // ---------------- استعادة/حفظ التبويبات ----------------

    private fun restoreTabsOrCreateHome() {
        val (saved, currentIndex) = TabPersistence.load(this)
        if (saved.isEmpty()) {
            createHomeTab()
            return
        }
        saved.forEach { savedTab ->
            if (savedTab.isHome) {
                createTabInternal(isHome = true, url = null)
            } else {
                createTabInternal(
                    isHome = false,
                    url = savedTab.url,
                    restoredBack = savedTab.backStack,
                    restoredForward = savedTab.forwardStack,
                    restoredWebState = savedTab.webState
                )
            }
        }
        val idx = currentIndex.coerceIn(0, tabs.size - 1)
        switchToTab(idx)
    }

    private fun persistTabs() {
        val saved = tabs.map { tab ->
            SavedTab(
                tab.isHome,
                if (tab.isHome) "" else (tab.webView.url ?: ""),
                tab.title,
                tab.backStack.toList(),
                tab.forwardStack.toList(),
                if (tab.isHome) null else serializeWebViewState(tab.webView)
            )
        }
        TabPersistence.save(this, saved, currentTabIndex)
    }

    override fun onPause() {
        super.onPause()
        persistTabs()
    }

    // ---------------- إدارة التبويبات ----------------

    private fun createHomeTab() {
        createTabInternal(isHome = true, url = null)
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    private fun createBrowsingTab(url: String) {
        createTabInternal(isHome = false, url = url)
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    private fun createTabInternal(
        isHome: Boolean,
        url: String?,
        restoredBack: List<String> = emptyList(),
        restoredForward: List<String> = emptyList(),
        restoredWebState: String? = null
    ) {
        val webView = WebView(this)
        setupWebView(webView)
        val cleanedUA = buildChromeLikeUserAgent(webView.settings.userAgentString)
        webView.settings.userAgentString = cleanedUA
        if (mobileUA.isEmpty()) mobileUA = cleanedUA

        val tab = Tab(id = System.currentTimeMillis(), webView = webView, isHome = isHome)
        tab.backStack.addAll(restoredBack)
        tab.forwardStack.addAll(restoredForward)
        if (!isHome && !url.isNullOrBlank()) {
            tab.title = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
        }
        tabs.add(tab)

        if (!isHome) {
            // لا نعيد تحميل URL إذا كان لدينا WebView state محفوظ؛
            // restoreState يعيد سجل الرجوع/التقدم نفسه.
            val restored = restoreSerializedWebViewState(webView, restoredWebState)
            if (!restored && !url.isNullOrBlank()) {
                webView.loadUrl(url)
            }
        }
    }

    /** الرجوع والتقدم يعتمدان على سجل WebView الحقيقي مع استعادة موضع التمرير. */
    private fun goBackInTab(tab: Tab) {
        navigateHistory(tab, -1)
    }

    private fun goForwardInTab(tab: Tab) {
        navigateHistory(tab, 1)
    }

    // ---------------- حفظ واستعادة موضع التمرير (Scroll Position Restoration) ----------------

    private val blobBridge by lazy { BlobDownloadBridge(this) }

    private val SCROLL_RESTORE_INTERVAL_MS = 150L
    private val SCROLL_RESTORE_MAX_TRIES = 60          // ≈ 9 ثوانٍ كحد أقصى
    private val MAX_SAVED_SCROLL_ENTRIES = 100

    private fun rememberScroll(tab: Tab, url: String, y: Int) {
        if (url.isBlank() || url.startsWith("about:")) return
        tab.scrollPositions.remove(url)          // ليصبح الأحدث في آخر القائمة
        tab.scrollPositions[url] = y
        while (tab.scrollPositions.size > MAX_SAVED_SCROLL_ENTRIES) {
            val oldest = tab.scrollPositions.keys.firstOrNull() ?: break
            tab.scrollPositions.remove(oldest)
        }
    }

    /**
     * ينتقل في سجل التبويب خطوة للخلف (steps = -1) أو للأمام (steps = 1)،
     * ويعيد المستخدم إلى نفس مكان التمرير الذي كان فيه في تلك الصفحة.
     * يرجع false إذا لا توجد صفحة سابقة/تالية.
     */
    private fun navigateHistory(tab: Tab, steps: Int): Boolean {
        val wv = tab.webView
        val list = wv.copyBackForwardList()
        val targetIndex = list.currentIndex + steps
        if (targetIndex < 0 || targetIndex >= list.size) return false
        val targetUrl = list.getItemAtIndex(targetIndex)?.url

        // احفظ موضع الصفحة التي نغادرها (احتياطًا، المستمع يحفظه أصلًا أثناء التمرير)
        val leavingUrl = wv.url
        if (leavingUrl != null && !tab.pageLoading && tab.pendingRestoreY < 0) {
            rememberScroll(tab, leavingUrl, wv.scrollY)
        }

        cancelScrollRestore(tab)
        val savedY = targetUrl?.let { tab.scrollPositions[it] } ?: 0

        // عند الرجوع نفضّل النسخة المخزّنة مؤقتًا لتظهر نفس نتائج البحث التي رآها المستخدم
        wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        wv.goBackOrForward(steps)

        if (savedY > 0) startScrollRestore(tab, targetIndex, savedY)
        return true
    }

    private fun cancelScrollRestore(tab: Tab) {
        tab.restoreGeneration++
        tab.pendingRestoreY = -1
    }

    private fun finishScrollRestore(tab: Tab) {
        tab.pendingRestoreY = -1
        try { tab.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (e: Exception) { }
    }

    /**
     * يحاول كل 150ms تمرير الصفحة إلى الموضع المحفوظ حتى ينجح.
     * إذا كانت الصفحة أقصر من الموضع المطلوب (المحتوى لم يكتمل أو يُحمَّل بالتمرير اللانهائي مثل جوجل)،
     * يبقى التمرير عند آخر الصفحة المتاحة فيُحمَّل المزيد من المحتوى، ثم يكمل المحاولة.
     * تتوقف المحاولات إذا لمس المستخدم الشاشة.
     */
    private fun startScrollRestore(tab: Tab, targetIndex: Int, targetY: Int) {
        val generation = ++tab.restoreGeneration
        tab.pendingRestoreY = targetY
        var tries = 0
        val step = object : Runnable {
            override fun run() {
                if (tab.restoreGeneration != generation) return       // أُلغيت أو استُبدلت باستعادة أحدث
                if (!tabs.contains(tab)) return                        // التبويب أُغلق
                val wv = tab.webView
                tries++
                // لا نحرّك الصفحة القديمة: ننتظر حتى يصبح موضع السجل هو الصفحة الهدف
                val onTarget = try { wv.copyBackForwardList().currentIndex == targetIndex } catch (e: Exception) { false }
                if (onTarget) {
                    wv.scrollTo(wv.scrollX, targetY)
                    if (abs(wv.scrollY - targetY) <= 2 && !tab.pageLoading) {
                        finishScrollRestore(tab)
                        return
                    }
                }
                if (tries >= SCROLL_RESTORE_MAX_TRIES) {
                    finishScrollRestore(tab)
                    return
                }
                mainHandler.postDelayed(this, SCROLL_RESTORE_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(step, SCROLL_RESTORE_INTERVAL_MS)
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        webViewContainer.removeAllViews()
        currentTabIndex = index
        val tab = tabs[index]

        if (tab.isHome) {
            editUrl.setText("")
            progressBar.visibility = View.GONE
            webViewContainer.addView(buildSpeedDialView())
        } else {
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            webViewContainer.addView(
                tab.webView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            editUrl.setText(tab.webView.url ?: "")
        }
        updateTabCountUI()
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs[index]
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            createHomeTab()
        } else {
            val newIndex = if (currentTabIndex >= tabs.size) tabs.size - 1 else currentTabIndex
            switchToTab(newIndex)
        }
        persistTabs()
    }

    private fun closeAllTabs() {
        webViewContainer.removeAllViews()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        createHomeTab()
    }

    private fun updateTabCountUI() {
        btnTabs.text = tabs.size.toString()
    }

    private fun currentTab(): Tab = tabs[currentTabIndex]
    private fun currentWebView(): WebView = tabs[currentTabIndex].webView

    /** يزيل علامة "wv" (WebView) من هوية المتصفح الافتراضية لتقليل رفض بعض المواقع/إظهار تحقق زائد */
    private fun buildChromeLikeUserAgent(defaultUA: String): String {
        return defaultUA
            .replace("; wv)", ")")
            .replace(" wv;", "")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
        editUrl.clearFocus()
    }

    // ---------------- صفحة البداية (Speed Dial) ----------------

    private fun buildSpeedDialView(): View {
        val root = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }

        val columns = 6
        val horizontalPadding = 24
        val screenWidth = resources.displayMetrics.widthPixels
        val cellWidth = (screenWidth - horizontalPadding * 2) / columns
        val iconSize = (cellWidth * 0.68f).toInt()

        val grid = GridLayout(this).apply {
            columnCount = columns
            setPadding(horizontalPadding, 48, horizontalPadding, 48)
        }

        val items = SpeedDialStorage.getItems(this)
        items.forEach { item ->
            grid.addView(buildSpeedDialTile(title = item.title, item = item, cellWidth = cellWidth, iconSize = iconSize) {
                createBrowsingTab(item.url)
            })
        }

        // زر + إضافة
        val addTile = buildAddTile(cellWidth = cellWidth, iconSize = iconSize) { showAddSpeedDialDialog() }
        grid.addView(addTile)

        root.addView(grid)
        return root
    }

    private fun buildAddTile(cellWidth: Int, iconSize: Int, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellWidth
                setMargins(4, 12, 4, 12)
            }
            setOnClickListener { onClick() }
        }
        val plusBox = TextView(this).apply {
            text = "+"
            setTextColor(Color.WHITE)
            textSize = (iconSize / 22f).coerceAtLeast(20f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        val label = TextView(this).apply {
            text = "إضافة"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        container.addView(plusBox)
        container.addView(label)
        return container
    }

    private fun buildSpeedDialTile(title: String, item: SpeedDialItem, cellWidth: Int, iconSize: Int, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellWidth
                setMargins(4, 12, 4, 12)
            }
            setOnClickListener { onClick() }
            setOnLongClickListener {
                showSpeedDialItemMenu(item)
                true
            }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        loadFaviconInto(icon, item.url)

        val label = TextView(this).apply {
            text = title
            setTextColor(visitedSiteColor(item.url, Color.parseColor("#00FFFF")))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        container.addView(icon)
        container.addView(label)
        return container
    }

    private fun loadFaviconInto(imageView: ImageView, pageUrl: String) {
        val host = try { Uri.parse(pageUrl).host } catch (e: Exception) { null } ?: return
        bgExecutor.execute {
            var bitmap: Bitmap? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://www.google.com/s2/favicons?sz=128&domain=$host")
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                bitmap = BitmapFactory.decodeStream(connection.inputStream)
            } catch (e: Exception) {
                bitmap = null
            } finally {
                connection?.disconnect()
            }
            val result = bitmap
            mainHandler.post {
                if (result != null) imageView.setImageBitmap(result)
            }
        }
    }

    private fun showAddSpeedDialDialog() {
        val input = EditText(this).apply {
            hint = "الصق الرابط هنا"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة موقع")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                val host = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
                SpeedDialStorage.addItem(this, host, url)
                switchToTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSpeedDialItemMenu(item: SpeedDialItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("إعادة تسمية", "حذف")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(item)
                    1 -> {
                        SpeedDialStorage.removeItem(this, item.id)
                        switchToTab(currentTabIndex)
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(item: SpeedDialItem) {
        val input = EditText(this).apply {
            setText(item.title)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("إعادة تسمية")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                SpeedDialStorage.renameItem(this, item.id, input.text.toString().trim())
                switchToTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------- إعداد WebView ----------------

    private fun setupWebView(webView: WebView) {
        if (nightMode) webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // النوافذ الجديدة تصل إلى onCreateWindow (مع نافذة التأكيد) فقط عند ضغط المستخدم؛
        // ما تفتحه الصفحة من نفسها بدون ضغط يبقى محجوبًا.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowFileAccess = true
        settings.blockNetworkImage = hideMedia
        settings.loadsImagesAutomatically = !hideMedia

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // تسجيل موضع التمرير أثناء التصفح لكل رابط (يُستخدم عند الرجوع)
        webView.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val wv = v as? WebView ?: return@setOnScrollChangeListener
            val tab = tabs.find { it.webView == wv } ?: return@setOnScrollChangeListener
            if (tab.pageLoading || tab.pendingRestoreY >= 0) return@setOnScrollChangeListener
            val url = wv.url ?: return@setOnScrollChangeListener
            rememberScroll(tab, url, scrollY)
        }

        // إذا لمس المستخدم الشاشة أثناء الاستعادة نوقفها حتى لا نتحكم بتمريره
        webView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                tabs.find { it.webView == v }?.let { t ->
                    if (t.pendingRestoreY >= 0) {
                        cancelScrollRestore(t)
                        try { t.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (e: Exception) { }
                    }
                }
            }
            false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                AdBlocker.maybeBlock(this@BrowserActivity, request)?.let { return it }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (request.isForMainFrame) {
                    val known = DownloadsRepository.downloadList.find { it.url == url }
                    if (known != null) {
                        // رابط تنزيل معروف: كان يُتجاهل بصمت، الآن تظهر رسالة «الملف موجود: الاسم» أو يُستأنف التنزيل
                        DownloadsRepository.startNewDownload(
                            this@BrowserActivity, url, showToast = true, suggestedFileName = known.fileName,
                            userAgent = view.settings.userAgentString, referer = view.url
                        )
                        return true
                    }
                }
                if (request.isForMainFrame) {
                    navigateWithSafetyCheck(view, url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tabs.find { it.webView == view }?.pageLoading = true
                // نحفظ الحالة فور بدء التنقل أيضًا حتى لا نفقد آخر خطوة إذا أُغلق التطبيق
                // قبل onPageFinished.
                persistTabs()
                if (nightMode) applyNightModeJs(view)
                if (isActiveTab(view)) editUrl.setText(url)
                updateCustomTabDomain(url)
            }

            // أول ظهور لمحتوى الصفحة الجديدة: أسرع وقت لتطبيق الوضع الليلي
            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                if (nightMode) applyNightModeJs(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                tabs.find { it.webView == view }?.let { t ->
                    t.pageLoading = false
                    // صفحة جديدة عادية (بدون استعادة جارية): سجّل موضعها الحالي
                    if (t.pendingRestoreY < 0) {
                        view.url?.let { u -> rememberScroll(t, u, view.scrollY) }
                        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    }
                }
                if (url != null) {
                    BrowserStorage.addHistory(this@BrowserActivity, view.title ?: url, url)
                    recordVisitedNavigation(url)
                    applyVisitedLinksJs(view)
                    mainHandler.postDelayed({ applyVisitedLinksJs(view) }, 600)
                    mainHandler.postDelayed({ applyVisitedLinksJs(view) }, 1500)
                }
                val resolvedTitle = view.title?.takeIf { it.isNotBlank() }
                    ?: url?.takeIf { it.isNotBlank() }
                    ?: "صفحة جديدة"
                tabs.find { it.webView == view }?.title = resolvedTitle
                if (nightMode) {
                    applyNightModeJs(view)
                    mainHandler.postDelayed({ if (nightMode) applyNightModeJs(view) }, 500)
                    // نحفظ حكم الصفحة (داكنة/فاتحة) لتُعالج أسرع في الزيارات القادمة
                    mainHandler.postDelayed({ if (nightMode) recordNightResult(view) }, 2200)
                }
                val tabForView = tabs.find { it.webView == view }
                if (tabForView?.desktopMode == true) applyDesktopViewportJs(view)
                if (isActiveTab(view)) {
                    if (hideMedia) applyHideMediaJs(view)
                }
                persistTabs()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (nightMode && newProgress in 15..95 && System.currentTimeMillis() - lastNightInject > 250) {
                    applyNightModeJs(view)
                }
                if (isActiveTab(view)) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                // نرد على الصفحة فورًا بـ WebView مؤقت يلتقط رابط النافذة فقط.
                // الاحتفاظ برسالة النافذة حتى يضغط المستخدم «سماح» كان يوقف التطبيق
                // (المتصفح يُلغي النافذة المعلّقة إذا طلبت الصفحة نافذة أخرى أو طال الانتظار).
                val temp = WebView(this@BrowserActivity)
                var finished = false

                fun dispose() {
                    if (finished) return
                    finished = true
                    // لا نهدم الـ WebView داخل ردّه نفسه: ننفّذ ذلك بعد انتهاء الاستدعاء الحالي
                    mainHandler.post {
                        try { temp.stopLoading() } catch (e: Exception) { }
                        try { temp.destroy() } catch (e: Exception) { }
                    }
                }

                fun capture(rawUrl: String?) {
                    if (finished) return
                    val u = rawUrl?.trim().orEmpty()
                    val lower = u.lowercase()
                    if (u.isEmpty() || lower.startsWith("about:") || lower.startsWith("javascript:") ||
                        lower.startsWith("data:") || lower.startsWith("blob:")) return
                    dispose()
                    showPopupConfirmDialog(u)
                }

                temp.webViewClient = object : WebViewClient() {
                    // لا نحمّل شيئًا من الشبكة داخل الـ WebView المؤقت
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        capture(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        capture(url)
                    }
                }

                transport.webView = temp
                resultMsg.sendToTarget()

                // إن لم يصل أي رابط (نافذة فارغة) نتخلص من الـ WebView المؤقت
                mainHandler.postDelayed({ dispose() }, 8000)
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                filePathCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "اختر ملف"), FILE_CHOOSER_CODE)
                } catch (e: Exception) {
                    filePathCallback = null
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // نمنح فقط ما هو مسموح في إعدادات المتصفح، ونرفض الباقي
                val granted = request.resources.filter { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> cameraAllowed()
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> micAllowed()
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true   // مطلوب لتشغيل فيديوهات DRM
                        else -> false
                    }
                }
                val deniedByUser = request.resources.any { res ->
                    (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE && !cameraAllowed()) ||
                        (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && !micAllowed())
                }
                if (granted.isEmpty() || deniedByUser) {
                    request.deny()
                    return
                }

                val androidPerms = mutableListOf<String>()
                if (granted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) androidPerms.add(Manifest.permission.CAMERA)
                if (granted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) androidPerms.add(Manifest.permission.RECORD_AUDIO)

                runOnUiThread {
                    ensureAndroidPermissions(androidPerms.toTypedArray()) { ok ->
                        if (ok) request.grant(granted.toTypedArray()) else request.deny()
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                if (!locationAllowed()) {
                    callback.invoke(origin, false, false)
                    return
                }
                ensureAndroidPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    anyOf = true
                ) { ok -> callback.invoke(origin, ok, false) }
            }
        }

        // جسر قراءة روابط blob: (محمي برمز عشوائي لكل عملية تنزيل)
        webView.addJavascriptInterface(blobBridge, "DownLS10Blob")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val nameFromHeader = DownloadsRepository.fileNameFromContentDispositionOrNull(contentDisposition)
            if (url.startsWith("blob:", ignoreCase = true)) {
                blobBridge.start(webView, url, nameFromHeader, mimeType)
            } else {
                DownloadsRepository.startNewDownload(
                    this,
                    url,
                    showToast = true,
                    suggestedFileName = nameFromHeader,
                    userAgent = userAgent,
                    referer = webView.url,
                    mimeType = mimeType
                )
            }
        }
    }

    private val downloadBarListener: () -> Unit = {
        mainHandler.post { updateDownloadBar() }
    }

    private fun updateDownloadBar() {
        if (!::downloadBar.isInitialized) return
        val active = DownloadsRepository.downloadList.firstOrNull {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED
        }
        val recent = DownloadsRepository.downloadList.firstOrNull {
            it.state == DownloadState.COMPLETED
        }
        val item = active ?: recent ?: run {
            downloadBar.visibility = View.GONE
            return
        }

        downloadBar.visibility = View.VISIBLE
        downloadBarFileName.text = item.fileName
        downloadBarProgress.progress = item.progress.coerceIn(0, 100)
        downloadBarStatus.text = when (item.state) {
            DownloadState.DOWNLOADING -> "${item.progress}%  ${item.speed}"
            DownloadState.PAUSED -> "متوقف مؤقتاً — ${item.progress}%"
            DownloadState.COMPLETED -> "اكتمل التنزيل"
            else -> item.status
        }

        if (item.state == DownloadState.COMPLETED) {
            downloadBar.postDelayed({
                if (DownloadsRepository.downloadList.firstOrNull { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED } == null) {
                    downloadBar.visibility = View.GONE
                }
            }, 3500L)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_CODE) {
            val results: Array<Uri>? = if (resultCode == Activity.RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }

        if (requestCode == BookmarksFileManager.EXPORT_REQUEST_CODE) {
            val content = pendingBookmarkExportContent
            pendingBookmarkExportContent = null
            if (resultCode == Activity.RESULT_OK && data?.data != null && content != null) {
                val result = BookmarksFileManager.writeExport(this, data.data!!, content)
                Toast.makeText(this, if (result.first) "تم تصدير العلامات المرجعية" else "فشل التصدير: ${result.second}", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (requestCode == BookmarksFileManager.IMPORT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val result = BookmarksFileManager.readImport(this, data.data!!)
                val html = result.first
                if (html == null) {
                    Toast.makeText(this, "فشل الاستيراد: ${result.second}", Toast.LENGTH_LONG).show()
                } else {
                    val parsed = BrowserStorage.parseBookmarksHtml(html)
                    if (parsed.isEmpty()) {
                        Toast.makeText(this, "لم يتم العثور على علامات مرجعية في الملف", Toast.LENGTH_LONG).show()
                    } else {
                        val added = BrowserStorage.importBookmarks(this, parsed)
                        Toast.makeText(this, "تم استيراد $added علامة مرجعية جديدة", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun isActiveTab(view: WebView): Boolean =
        tabs.isNotEmpty() && currentTabIndex < tabs.size && tabs[currentTabIndex].webView == view

    // ---------------- التنقل + فحص الأمان ----------------

    /** يسجّل الرابط كمزار. روابط جوجل الوسيطة (/url?q=...) تُفك ليُسجَّل الموقع الحقيقي. */
    private fun isVisitedSite(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        return VisitedSites.isVisited(this, host)
    }

    private fun visitedSiteColor(url: String?, unvisitedColor: Int = Color.parseColor("#00FFFF")): Int {
        return if (isVisitedSite(url)) Color.parseColor("#B388FF") else unvisitedColor
    }

    private fun recordVisitedNavigation(url: String?) {
        if (url.isNullOrBlank()) return
        val uri = try { Uri.parse(url) } catch (e: Exception) { return }
        if (uri.scheme != "http" && uri.scheme != "https") return
        var realUrl = url
        val host = uri.host ?: return
        if (host.contains("google.") && uri.path == "/url") {
            val target = uri.getQueryParameter("q") ?: uri.getQueryParameter("url")
            if (!target.isNullOrBlank()) realUrl = target
        }
        val realHost = try { Uri.parse(realUrl).host } catch (e: Exception) { null }
        VisitedSites.recordVisit(this, realHost)
        VisitedSites.recordUrl(this, realUrl)
    }

    private fun loadAndRecord(webView: WebView, url: String) {
        recordVisitedNavigation(url)
        webView.loadUrl(url)
    }

    private fun navigateWithSafetyCheck(webView: WebView, rawUrl: String) {
        // روابط magnet وftp لا يفتحها المتصفح: تذهب لمدير التنزيل
        val lowerUrl = rawUrl.trim().lowercase()
        if (lowerUrl.startsWith("magnet:") || lowerUrl.startsWith("ftp://")) {
            DownloadsRepository.startNewDownload(this, rawUrl.trim(), showToast = true, referer = webView.url)
            return
        }
        // روابط ملفات Mega: صفحة Mega لا تستطيع التنزيل داخل WebView، فيتولاها مدير التنزيل مباشرة
        if (MegaSupport.isMegaFileLink(rawUrl.trim())) {
            DownloadsRepository.startNewDownload(this, rawUrl.trim(), showToast = true, referer = webView.url)
            return
        }
        // أي تنقل جديد (ضغط رابط/شريط العنوان) يستخدم الكاش الافتراضي وليس وضع الرجوع
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        tabs.find { it.webView == webView }?.let { cancelScrollRestore(it) }
        if (rawUrl == lastConfirmedSafetyUrl) {
            lastConfirmedSafetyUrl = null
            loadAndRecord(webView, rawUrl)
            return
        }

        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { null }

        // رابط مخصص (غير http/https): افتح التطبيق المثبّت إذا قدر يتعامل معه، وإلا تجاهله بهدوء
        if (uri?.scheme != null && uri.scheme != "http" && uri.scheme != "https") {
            openExternalAppLink(webView, rawUrl, uri)
            return
        }

        val host = uri?.host

        if (host != null) {
            val match = AdBlocker.matchSafetyCategory(this, host)
            if (match != null) {
                when (match.category) {
                    ListCategory.PORN -> {
                        showBlockedInterstitial(webView, "هذا موقع إباحي وتم حجبه")
                        return
                    }
                    ListCategory.MALWARE, ListCategory.PHISHING, ListCategory.RISK -> {
                        val label = when (match.category) {
                            ListCategory.MALWARE -> "⚠️ هذا الموقع مصنّف كموقع ضار / يوزّع برمجيات خبيثة"
                            ListCategory.PHISHING -> "⚠️ هذا الموقع مصنّف كموقع تصيّد احتيالي - لا تُدخل بيانات حساباتك"
                            else -> "⚠️ هذا الموقع مصنّف كموقع مشبوه"
                        }
                        AlertDialog.Builder(this)
                            .setTitle("تحذير أمان")
                            .setMessage(label)
                            .setPositiveButton("متابعة على مسؤوليتي") { _, _ ->
                                lastConfirmedSafetyUrl = rawUrl
                                loadAndRecord(webView, rawUrl)
                            }
                            .setNegativeButton("رجوع", null)
                            .show()
                        return
                    }
                    ListCategory.AD -> { /* لا يحدث هنا */ }
                }
            }
        }

        if (uri?.scheme == "http") {
            Toast.makeText(this, "⚠️ هذا الموقع غير آمن (اتصال HTTP غير مشفّر)", Toast.LENGTH_SHORT).show()
        }

        loadAndRecord(webView, rawUrl)
    }

    // يفتح روابط التطبيقات الخارجية (Google Play, تيليجرام, واتساب, ...).
    // روابط "intent://" (مثل زر "الفتح في تطبيق Play") لها تنسيق خاص يجب فكّه
    // بواسطة Intent.parseUri وليس ببناء Intent يدويًا من الـ Uri مباشرة.
    private fun openExternalAppLink(webView: WebView, rawUrl: String, uri: Uri) {
        try {
            val intent: Intent
            var fallbackUrl: String? = null

            if (uri.scheme == "intent") {
                intent = Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
                fallbackUrl = intent.getStringExtra("browser_fallback_url")
                // نتأكد إنه ما يرجع لنفس المتصفح إذا التطبيق موجود أصلاً
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
            } else {
                intent = Intent(Intent.ACTION_VIEW, uri)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else if (!fallbackUrl.isNullOrBlank()) {
                // التطبيق غير مثبّت: افتح الرابط الاحتياطي (عادة صفحة متجر التطبيق نفسه)
                loadAndRecord(webView, fallbackUrl)
            }
            // ما فيه تطبيق ولا رابط احتياطي: تجاهل بهدوء
        } catch (e: Exception) {
            // تجاهل بهدوء عند فشل تحليل أو فتح الرابط
        }
    }

    private fun showBlockedInterstitial(webView: WebView, message: String) {
        val html = """
            <html dir="rtl"><body style="background:#000;color:#fff;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
            <h2>🚫 $message</h2></body></html>
        """
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun loadFromAddressBar() {
        var input = editUrl.text.toString().trim()
        if (input.isEmpty()) return

        input = when {
            Patterns.WEB_URL.matcher(input).matches() && !input.startsWith("http") -> "https://$input"
            input.contains(" ") || !input.contains(".") -> "https://www.google.com/search?q=${Uri.encode(input)}"
            !input.startsWith("http://") && !input.startsWith("https://") -> "https://$input"
            else -> input
        }

        if (currentTab().isHome) {
            currentTab().isHome = false
            webViewContainer.removeAllViews()
            webViewContainer.addView(
                currentWebView(),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        navigateWithSafetyCheck(currentWebView(), input)
    }

    // ---------------- قائمة ☰ ----------------

    private fun showBrowserMenu() {
        val options = arrayOf(
            "تنزيل",
            if (hideMedia) "إظهار الوسائط" else "إخفاء الوسائط بالكامل",
            "العلامات المرجعية",
            if (currentTab().desktopMode) "عرض الجوال (لهذا التبويب)" else "عرض سطح المكتب (لهذا التبويب)",
            "مانع الإعلانات وحماية التصفح",
            "تكبير/تصغير الخط",
            "السجل",
            "ترجمة إلى العربية",
            "الأذونات",
            if (nightMode) "إيقاف الوضع الليلي" else "تفعيل الوضع الليلي"
        )

        AlertDialog.Builder(this)
            .setTitle("خيارات المتصفح")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDownLS10()
                    1 -> toggleHideMedia()
                    2 -> showBookmarksDialog()
                    3 -> toggleDesktopMode()
                    4 -> showAdBlockSettingsDialog()
                    5 -> showFontZoomDialog()
                    6 -> showHistoryDialog()
                    7 -> runTranslate()
                    8 -> showPermissionsDialog()
                    9 -> toggleNightMode()
                }
            }
            .show()
    }

    // ---------------- الأذونات: كاميرا / ميكروفون / موقع ----------------

    private var pendingPermCallback: ((Boolean) -> Unit)? = null
    private var pendingPermList: Array<String> = emptyArray()
    private var pendingPermAnyOf = false

    /** يطلب صلاحيات أندرويد إن لم تكن ممنوحة ثم يستدعي callback(true) عند النجاح (anyOf: يكفي منح واحدة). */
    private fun ensureAndroidPermissions(
        perms: Array<String>,
        anyOf: Boolean = false,
        callback: (Boolean) -> Unit
    ) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val alreadyOk = if (anyOf) missing.size < perms.size else missing.isEmpty()
        if (alreadyOk) {
            callback(true)
            return
        }
        pendingPermCallback?.invoke(false)     // طلب سابق لم يكتمل: نرفضه
        pendingPermCallback = callback
        pendingPermList = perms
        pendingPermAnyOf = anyOf
        requestPermissions(missing.toTypedArray(), ANDROID_PERM_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ANDROID_PERM_CODE) return
        val callback = pendingPermCallback ?: return
        pendingPermCallback = null
        val granted = pendingPermList.filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val ok = if (pendingPermAnyOf) granted.isNotEmpty() else granted.size == pendingPermList.size
        callback(ok)
    }

    private fun permissionStateText(name: String, allowed: Boolean) =
        if (allowed) "$name: مسموح" else "$name: ممنوع"

    /** القائمة الفرعية: كل بند يعرض (مسموح/ممنوع) والضغط عليه يبدّل الحالة فعليًا. */
    private fun showPermissionsDialog() {
        fun rows() = listOf(
            permissionStateText("الكاميرا (الأمامية والخلفية)", cameraAllowed()),
            permissionStateText("الميكروفون", micAllowed()),
            permissionStateText("الموقع الجغرافي", locationAllowed())
        )

        val items = ArrayList(rows())
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.select_dialog_item, items)
        val dialog = AlertDialog.Builder(this)
            .setTitle("الأذونات")
            .setAdapter(adapter, null)
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.show()

        // نحدّد المستمع بعد show() حتى تبقى القائمة مفتوحة وتتحدّث الحالة أمام المستخدم
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> togglePermission("camera", !cameraAllowed(), arrayOf(Manifest.permission.CAMERA), false)
                1 -> togglePermission("mic", !micAllowed(), arrayOf(Manifest.permission.RECORD_AUDIO), false)
                2 -> togglePermission(
                    "location", !locationAllowed(),
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), true
                )
            }
            items.clear()
            items.addAll(rows())
            adapter.notifyDataSetChanged()
        }
    }

    private fun togglePermission(key: String, newValue: Boolean, androidPerms: Array<String>, anyOf: Boolean) {
        settingsPrefs().edit().putBoolean(key, newValue).apply()
        // عند السماح نطلب صلاحية أندرويد مسبقًا حتى تعمل مباشرة عندما يطلبها الموقع
        if (newValue) ensureAndroidPermissions(androidPerms, anyOf) { }
    }

    /** نافذة تأكيد فتح نافذة منبثقة؛ عند «سماح» يُفتح الرابط في تبويب جديد. */
    private var popupDialogShowing = false

    private fun showPopupConfirmDialog(url: String) {
        if (popupDialogShowing || isFinishing || isDestroyed) return   // نافذة واحدة في كل مرة
        popupDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("نافذة منبثقة")
            .setMessage("هل تريد فتح نافذة منبثقة؟")
            .setPositiveButton("سماح") { _, _ -> openPopupInNewTab(url) }
            .setNegativeButton("رفض", null)
            .setOnDismissListener { popupDialogShowing = false }
            .show()
    }

    private fun openPopupInNewTab(rawUrl: String) {
        val lower = rawUrl.trim().lowercase()
        val isWebUrl = lower.startsWith("http://") || lower.startsWith("https://")
        if (!isWebUrl || MegaSupport.isMegaFileLink(rawUrl.trim())) {
            // magnet/ftp/Mega/تطبيق خارجي: يتولاها مسار التنقل الحالي بدون تبويب فارغ
            navigateWithSafetyCheck(currentWebView(), rawUrl)
            return
        }
        createTabInternal(isHome = false, url = null)
        switchToTab(tabs.size - 1)
        navigateWithSafetyCheck(tabs[tabs.size - 1].webView, rawUrl)
        persistTabs()
    }

    private fun toggleSetting(key: String, value: Boolean, onEnabled: (Boolean) -> Unit) {
        settingsPrefs().edit().putBoolean(key, value).apply()
        onEnabled(value)
        Toast.makeText(this, if (value) "تم التفعيل" else "تم الإيقاف", Toast.LENGTH_SHORT).show()
    }

    private fun requestAndroidPermIfNeeded(permission: String) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), ANDROID_PERM_CODE)
        }
    }

    // 0) تنزيل -> يفتح DownLS10 مباشرة
    private fun openDownLS10() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    // 1) إخفاء الوسائط بالكامل
    private fun toggleHideMedia() {
        hideMedia = !hideMedia
        settingsPrefs().edit().putBoolean("hideMedia", hideMedia).apply()
        tabs.forEach { tab ->
            tab.webView.settings.blockNetworkImage = hideMedia
            tab.webView.settings.loadsImagesAutomatically = !hideMedia
        }
        if (hideMedia) applyHideMediaJs(currentWebView()) else currentWebView().reload()
        Toast.makeText(this, if (hideMedia) "تم إخفاء الوسائط (سيبقى مفعّلاً دائماً)" else "تم إظهار الوسائط", Toast.LENGTH_SHORT).show()
    }

    private fun applyHideMediaJs(webView: WebView) {
        val js = """
            (function(){
                var css = 'img, video, iframe, embed, object, picture, svg { display:none !important; }';
                var style = document.getElementById('__downls10_hide_media__');
                if (!style) {
                    style = document.createElement('style');
                    style.id = '__downls10_hide_media__';
                    document.head.appendChild(style);
                }
                style.innerHTML = css;
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    /**
     * يلوّن روابط المواقع التي سبق فتحها بالبنفسجي.
     * حالة الزيارة تأتي من VisitedSites المخزنة على القرص، لذلك لا تعتمد على
     * بقاء التطبيق مفتوحاً أو على حالة WebView الحالية.
     */
    private fun applyVisitedLinksJs(webView: WebView) {
        val visitedJson = VisitedSites.asJsonForInjection(this)
        val pageHost = try { Uri.parse(webView.url ?: "").host } catch (e: Exception) { null }
        val pageUrlsJson = VisitedSites.urlsForHostJson(this, pageHost)
        val js = """
            (function(){
                window.__downls10Visited = $visitedJson;
                window.__downls10PageUrls = new Set($pageUrlsJson);
                function normHost(h) { return (h || '').toLowerCase().replace(/^www\./, ''); }
                function normUrl(u) { return u.split('#')[0].replace(/\/$/, ''); }
                var pageHost = normHost(location.hostname);

                // لون الرابط المزار: يُفرض على الرابط وعلى كل ما بداخله (مثل عناوين h3 في نتائج البحث)
                if (!document.getElementById('__downls10_visited_style__')) {
                    var st = document.createElement('style');
                    st.id = '__downls10_visited_style__';
                    st.textContent = 'a[data-downls10-visited="1"], a[data-downls10-visited="1"] * ' +
                        '{ color: #B388FF !important; -webkit-text-fill-color: #B388FF !important; }';
                    (document.head || document.documentElement).appendChild(st);
                }

                function resolveLink(a) {
                    var u;
                    try { u = new URL(a.href, location.href); } catch(e) { return null; }
                    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
                    var host = normHost(u.hostname);
                    var full = u.href;
                    // رابط جوجل الوسيط: نستخدم الموقع الحقيقي
                    if (/(^|\.)google\.[a-z.]+${'$'}/.test(host) && u.pathname === '/url') {
                        var t = u.searchParams.get('q') || u.searchParams.get('url');
                        if (t) {
                            try { var r = new URL(t); host = normHost(r.hostname); full = r.href; } catch(e) {}
                        }
                    }
                    return { host: host, url: normUrl(full) };
                }
                function isVisited(info) {
                    // رابط داخل نفس الموقع: يُعتبر مزاراً فقط إذا زرت هذا الرابط بالذات
                    if (info.host === pageHost) return window.__downls10PageUrls.has(info.url);
                    // رابط لموقع آخر: يُعتبر مزاراً إذا زرت الموقع
                    return window.__downls10Visited.hasOwnProperty(info.host);
                }
                function markLink(a) {
                    var info = resolveLink(a);
                    if (!info) return;
                    a.setAttribute('data-downls10-marked', '1');
                    if (isVisited(info)) {
                        a.setAttribute('data-downls10-visited', '1');
                    } else {
                        a.removeAttribute('data-downls10-visited');
                        if (info.host !== pageHost) a.style.color = '#00FFFF';
                    }
                }
                function scanAll(root) {
                    var links = root.querySelectorAll ? root.querySelectorAll('a[href]') : [];
                    for (var i = 0; i < links.length; i++) markLink(links[i]);
                }
                scanAll(document);
                if (!window.__downls10VisitedObserver) {
                    window.__downls10VisitedObserver = new MutationObserver(function(mutations) {
                        mutations.forEach(function(m) {
                            m.addedNodes && m.addedNodes.forEach(function(node) {
                                if (node.nodeType !== 1) return;
                                if (node.tagName === 'A') markLink(node);
                                scanAll(node);
                            });
                        });
                    });
                    if (document.body) window.__downls10VisitedObserver.observe(document.body, { childList: true, subtree: true });
                    window.addEventListener('pageshow', function() { scanAll(document); });
                }
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // ⭐ إضافة الصفحة الحالية مباشرة
    private fun addCurrentPageToBookmarks() {
        if (currentTab().isHome) {
            Toast.makeText(this, "لا توجد صفحة لإضافتها", Toast.LENGTH_SHORT).show()
            return
        }
        val url = currentWebView().url
        val title = currentWebView().title
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "لا توجد صفحة لإضافتها", Toast.LENGTH_SHORT).show()
            return
        }
        if (BrowserStorage.isBookmarked(this, url)) {
            Toast.makeText(this, "الصفحة مضافة مسبقاً للعلامات", Toast.LENGTH_SHORT).show()
            return
        }
        BrowserStorage.addBookmark(this, title ?: url, url)
        Toast.makeText(this, "تمت الإضافة للعلامات المرجعية", Toast.LENGTH_SHORT).show()
    }

    // 2) العلامات المرجعية (عرض + حذف + استيراد/تصدير)
    private fun showBookmarksDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("العلامات المرجعية")
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()

        fun refresh() {
            container.removeAllViews()
            val bookmarks = BrowserStorage.getBookmarks(this)

            if (bookmarks.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "لا توجد علامات مرجعية بعد"
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 16, 0, 16)
                })
            } else {
                bookmarks.forEach { bookmark ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 20, 0, 20)
                    }
                    val titleText = TextView(this).apply {
                        text = bookmark.title
                        setTextColor(visitedSiteColor(bookmark.url, Color.parseColor("#00FFFF")))
                        textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            createBrowsingTab(bookmark.url)
                            dialog.dismiss()
                        }
                    }
                    val deleteBtn = Button(this).apply {
                        text = "✕"
                        setTextColor(Color.WHITE)
                        setPadding(24, 0, 24, 0)
                        setOnClickListener {
                            BrowserStorage.removeBookmark(this@BrowserActivity, bookmark.url)
                            refresh()
                        }
                    }
                    row.addView(titleText)
                    row.addView(deleteBtn)
                    container.addView(row)

                    container.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(Color.parseColor("#333333"))
                    })
                }
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 24, 0, 0)
            }
            val exportBtn = Button(this).apply {
                text = "تصدير"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { exportBookmarks { refresh() } }
            }
            val importBtn = Button(this).apply {
                text = "استيراد"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { importBookmarks { refresh() } }
            }
            actionsRow.addView(exportBtn)
            actionsRow.addView(importBtn)
            container.addView(actionsRow)
        }

        refresh()
        dialog.show()
    }

    private fun exportBookmarks(onFinished: () -> Unit) {
        pendingBookmarkExportContent = BrowserStorage.buildBookmarksHtml(this)
        try {
            BookmarksFileManager.startExport(this, pendingBookmarkExportContent!!)
        } catch (e: Exception) {
            pendingBookmarkExportContent = null
            Toast.makeText(this, "فشل فتح حفظ العلامات: ${e.message ?: "غير معروف"}", Toast.LENGTH_LONG).show()
        }
        onFinished()
    }

    private fun importBookmarks(onFinished: () -> Unit) {
        try {
            BookmarksFileManager.startImport(this)
        } catch (e: Exception) {
            Toast.makeText(this, "فشل فتح اختيار ملف العلامات: ${e.message ?: "غير معروف"}", Toast.LENGTH_LONG).show()
        }
        onFinished()
    }

    // 3) عرض سطح المكتب (خاص بالتبويب الحالي فقط)
    private fun toggleDesktopMode() {
        val tab = currentTab()
        tab.desktopMode = !tab.desktopMode
        currentWebView().settings.userAgentString = if (tab.desktopMode) desktopUA else mobileUA
        currentWebView().reload()
        Toast.makeText(this, if (tab.desktopMode) "عرض سطح المكتب مفعّل لهذا التبويب" else "عرض الجوال مفعّل لهذا التبويب", Toast.LENGTH_SHORT).show()
    }

    private fun applyDesktopViewportJs(webView: WebView) {
        val js = """
            (function(){
                var meta = document.querySelector('meta[name=viewport]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.setAttribute('name', 'viewport');
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', 'width=1024, initial-scale=1, user-scalable=yes');
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // الوضع الليلي: يقلب ألوان أي موقع فاتح إلى أسود، ويترك الموقع الداكن كما هو.
    // الحالة محفوظة دائماً (تبقى بعد إغلاق التطبيق)، ويُطبَّق مباشرة على كل التبويبات عند التشغيل.
    private fun toggleNightMode() {
        nightMode = !nightMode
        settingsPrefs().edit().putBoolean("nightMode", nightMode).apply()
        tabs.forEach { tab ->
            tab.webView.setBackgroundColor(if (nightMode) Color.BLACK else Color.WHITE)
            if (!tab.isHome) {
                if (nightMode) applyNightModeJs(tab.webView) else removeNightModeJs(tab.webView)
            }
        }
        Toast.makeText(this, if (nightMode) "الوضع الليلي مفعّل (ثابت دائماً)" else "تم إيقاف الوضع الليلي", Toast.LENGTH_SHORT).show()
    }

    private var lastNightInject = 0L

    /** آخر حكم محفوظ لهذا الموقع: true = داكن أصلاً، false = فاتح، null = غير معروف. */
    private fun nightHostState(host: String?): Boolean? {
        if (host.isNullOrBlank()) return null
        val raw = settingsPrefs().getString("nightHosts", null) ?: return null
        return try {
            val obj = org.json.JSONObject(raw)
            if (obj.has(host)) obj.getBoolean(host) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun rememberNightHost(host: String?, dark: Boolean) {
        if (host.isNullOrBlank()) return
        try {
            val raw = settingsPrefs().getString("nightHosts", null)
            var obj = if (raw != null) org.json.JSONObject(raw) else org.json.JSONObject()
            if (obj.length() > 400) obj = org.json.JSONObject()
            if (obj.has(host) && obj.getBoolean(host) == dark) return
            obj.put(host, dark)
            settingsPrefs().edit().putString("nightHosts", obj.toString()).apply()
        } catch (e: Exception) { }
    }

    /** يقرأ ما حكمت به الصفحة (داكنة أم فاتحة) ويحفظه لهذا الموقع ليُطبَّق أسرع في الزيارة القادمة. */
    private fun recordNightResult(webView: WebView) {
        val host = try { Uri.parse(webView.url ?: "").host } catch (e: Exception) { null } ?: return
        webView.evaluateJavascript(
            "(window.__downls10NightDark === true ? '1' : (window.__downls10NightDark === false ? '0' : ''))"
        ) { result ->
            val value = result?.trim('"')
            if (value == "1") rememberNightHost(host, true)
            else if (value == "0") rememberNightHost(host, false)
        }
    }

    /**
     * يطبّق الوضع الليلي على الصفحة. آمن للاستدعاء المتكرر (يُستدعى عند بدء الصفحة، وظهورها، وتقدّم التحميل، واكتمالها).
     * الصفحة الفاتحة تُقلب إلى أسود، والصفحة الداكنة أصلاً تبقى كما هي (يُفحص لون الخلفية الفعلي في عدة نقاط من الشاشة).
     */
    private fun applyNightModeJs(webView: WebView) {
        lastNightInject = System.currentTimeMillis()
        val host = try { Uri.parse(webView.url ?: "").host } catch (e: Exception) { null }
        val hostDark = when (nightHostState(host)) {
            true -> "true"
            false -> "false"
            null -> "null"
        }
        val js = """
            (function(hostDark) {
                try {
                    window.__downls10NightOff = false;
                    var ID = '__downls10_night_mode__';
                    var CSS_ON = 'html{filter:invert(1) hue-rotate(180deg) !important;background:#fff !important;}' +
                        'img,video,iframe,picture,svg,canvas,[style*="background-image"]{filter:invert(1) hue-rotate(180deg) !important;}';

                    function styleEl() {
                        var s = document.getElementById(ID);
                        if (!s) {
                            s = document.createElement('style');
                            s.id = ID;
                            (document.head || document.documentElement).appendChild(s);
                        }
                        return s;
                    }
                    function setInverted(on) {
                        var s = styleEl();
                        var css = on ? CSS_ON : '';
                        if (s.textContent !== css) s.textContent = css;
                        window.__downls10NightInverted = on;
                    }
                    function parseColor(str) {
                        var m = String(str).match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?/);
                        if (!m) return null;
                        return { r: +m[1], g: +m[2], b: +m[3], a: (m[4] === undefined ? 1 : parseFloat(m[4])) };
                    }
                    // لون الخلفية الفعلي عند نقطة من الشاشة (أول عنصر له خلفية صريحة)
                    function backgroundAt(x, y) {
                        var els = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
                        for (var i = 0; i < els.length; i++) {
                            var cs = getComputedStyle(els[i]);
                            var c = parseColor(cs.backgroundColor);
                            if (c && c.a > 0.6) return c;
                            if (cs.backgroundImage && cs.backgroundImage !== 'none') return null;
                        }
                        return 'canvas';
                    }
                    function detectDark() {
                        if (!document.body) return null;
                        var w = window.innerWidth, h = window.innerHeight;
                        if (!w || !h) return null;
                        var scheme = String(getComputedStyle(document.documentElement).colorScheme || '').trim();
                        var canvasLum = (scheme === 'dark') ? 0 : 255;
                        var pts = [0.1, 0.3, 0.5, 0.7, 0.9];
                        var dark = 0, light = 0;
                        for (var i = 0; i < pts.length; i++) {
                            for (var j = 0; j < pts.length; j++) {
                                var bg = backgroundAt(Math.floor(w * pts[i]), Math.floor(h * pts[j]));
                                if (bg === null) continue;
                                var lum = (bg === 'canvas') ? canvasLum : (0.299 * bg.r + 0.587 * bg.g + 0.114 * bg.b);
                                if (lum < 110) dark++; else light++;
                            }
                        }
                        if (dark + light < 6) return null;
                        return (dark / (dark + light)) >= 0.55;
                    }
                    function run() {
                        if (window.__downls10NightOff) return;
                        var d = detectDark();
                        if (d === null) {
                            // لم نستطع الحكم بعد (الصفحة لم تُرسم): نستخدم المحفوظ لهذا الموقع، وإلا نفترضها فاتحة
                            if (window.__downls10NightInverted === undefined) setInverted(hostDark !== true);
                            return;
                        }
                        window.__downls10NightDark = d;
                        setInverted(!d);
                    }
                    run();

                    if (!window.__downls10NightHooked) {
                        window.__downls10NightHooked = true;
                        var timer = null;
                        var later = function() { clearTimeout(timer); timer = setTimeout(run, 250); };
                        document.addEventListener('DOMContentLoaded', run);
                        window.addEventListener('load', function() { run(); setTimeout(run, 800); setTimeout(run, 2000); });
                        setTimeout(run, 300);
                        setTimeout(run, 1000);
                        // إذا بدّل الموقع نفسه بين فاتح وداكن نعيد الحكم
                        var opts = { attributes: true, attributeFilter: ['class', 'style', 'data-theme', 'data-bs-theme'] };
                        if (document.documentElement) new MutationObserver(later).observe(document.documentElement, opts);
                        var bodyWatch = setInterval(function() {
                            if (document.body) {
                                new MutationObserver(later).observe(document.body, opts);
                                clearInterval(bodyWatch);
                            }
                        }, 200);
                        setTimeout(function() { clearInterval(bodyWatch); }, 5000);
                    }
                } catch (e) { }
            })($hostDark);
        """
        webView.evaluateJavascript(js, null)
    }

    private fun removeNightModeJs(webView: WebView) {
        val js = """
            (function(){
                window.__downls10NightOff = true;
                window.__downls10NightInverted = undefined;
                var style = document.getElementById('__downls10_night_mode__');
                if (style) style.textContent = '';
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // 4) مانع الإعلانات وحماية التصفح (كل القوائم)
    private fun showAdBlockSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        fun refresh() {
            container.removeAllViews()
            AdBlocker.LISTS.forEach { def ->
                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 16, 0, 16)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val nameText = TextView(this).apply {
                    text = def.displayName
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val toggleBtn = Button(this).apply {
                    val enabled = AdBlocker.isEnabled(this@BrowserActivity, def.id)
                    text = if (enabled) "مفعّل" else "متوقف"
                    setOnClickListener {
                        val newVal = !AdBlocker.isEnabled(this@BrowserActivity, def.id)
                        AdBlocker.setEnabled(this@BrowserActivity, def.id, newVal)
                        tabs.forEach { tab -> if (!tab.isHome) tab.webView.reload() }
                        refresh()
                    }
                }
                row.addView(nameText)
                row.addView(toggleBtn)

                val statusRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val statusText = TextView(this).apply {
                    text = "${AdBlocker.ruleCount(this@BrowserActivity, def.id)} قاعدة - ${AdBlocker.lastUpdateText(this@BrowserActivity, def.id)}"
                    setTextColor(Color.LTGRAY)
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val updateBtn = Button(this).apply {
                    text = "تحديث"
                    setOnClickListener {
                        AdBlocker.updateList(this@BrowserActivity, def.id) { success, count, error ->
                            if (!success) {
                                Toast.makeText(this@BrowserActivity, "${def.displayName}: فشل - $error", Toast.LENGTH_LONG).show()
                            }
                            refresh()
                        }
                    }
                }
                statusRow.addView(statusText)
                statusRow.addView(updateBtn)

                block.addView(row)
                block.addView(statusRow)
                container.addView(block)

                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#333333"))
                })
            }
        }

        refresh()

        AlertDialog.Builder(this)
            .setTitle("مانع الإعلانات وحماية التصفح")
            .setView(scroll)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    // 5) تكبير/تصغير الخط
    private fun showFontZoomDialog() {
        AlertDialog.Builder(this)
            .setTitle("حجم الخط: ${currentWebView().settings.textZoom}%")
            .setPositiveButton("تكبير +") { _, _ ->
                currentWebView().settings.textZoom = (currentWebView().settings.textZoom + 20).coerceAtMost(300)
            }
            .setNegativeButton("تصغير -") { _, _ ->
                currentWebView().settings.textZoom = (currentWebView().settings.textZoom - 20).coerceAtLeast(50)
            }
            .setNeutralButton("إعادة الضبط") { _, _ ->
                currentWebView().settings.textZoom = 100
            }
            .show()
    }

    // 6) السجل
    private fun showHistoryDialog() {
        val history = BrowserStorage.getHistory(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "السجل فارغ", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        history.forEach { entry ->
            val displayText = entry.title.ifBlank { entry.url }
            val row = TextView(this).apply {
                text = displayText
                setTextColor(visitedSiteColor(entry.url, Color.parseColor("#00FFFF")))
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 20, 0, 20)
                setOnClickListener { createBrowsingTab(entry.url) }
            }
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            })
        }

        AlertDialog.Builder(this)
            .setTitle("سجل التصفح")
            .setView(scroll)
            .setPositiveButton("مسح السجل") { _, _ -> BrowserStorage.clearHistory(this) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    // 7) ترجمة إلى العربية
    private fun runTranslate() {
        if (currentTab().isHome) {
            Toast.makeText(this, "لا توجد صفحة لترجمتها", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "جاري ترجمة الصفحة...", Toast.LENGTH_SHORT).show()
        PageTranslator.translatePage(currentWebView(), "ar") { success, message ->
            if (!isFinishing) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- قائمة التبويبات ----------------

    private fun showTabsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("الصفحات المفتوحة")
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()

        fun refresh() {
            container.removeAllViews()
            tabs.forEachIndexed { index, tab ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 20, 0, 20)
                }
                val label = if (tab.isHome) "الصفحة الرئيسية" else tab.title
                val tabUrl = if (tab.isHome) null else tab.webView.url
                val titleText = TextView(this).apply {
                    text = if (index == currentTabIndex) "● $label" else label
                    setTextColor(if (tab.isHome) Color.WHITE else visitedSiteColor(tabUrl, Color.parseColor("#00FFFF")))
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        switchToTab(index)
                        dialog.dismiss()
                    }
                }
                val closeBtn = Button(this).apply {
                    text = "✕"
                    setTextColor(Color.WHITE)
                    setPadding(24, 0, 24, 0)
                    setOnClickListener {
                        closeTab(index)
                        refresh()
                    }
                }
                row.addView(titleText)
                row.addView(closeBtn)
                container.addView(row)

                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#333333"))
                })
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 24, 0, 0)
            }
            val addBtn = Button(this).apply {
                text = "+ إضافة صفحة جديدة"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    createHomeTab()
                    dialog.dismiss()
                }
            }
            val closeAllBtn = Button(this).apply {
                text = "إغلاق الكل"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    closeAllTabs()
                    dialog.dismiss()
                }
            }
            actionsRow.addView(addBtn)
            actionsRow.addView(closeAllBtn)
            container.addView(actionsRow)
        }

        refresh()
        dialog.show()
    }

    override fun onBackPressed() {
        val tab = currentTab()
        if (!tab.isHome && navigateHistory(tab, -1)) {
            return
        }
        if (!tab.isHome) {
            tab.isHome = true
            switchToTab(currentTabIndex)
            persistTabs()
            return
        }
        finishAffinity()
    }

    override fun onDestroy() {
        DownloadsRepository.removeListener(downloadBarListener)
        tabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }
}
