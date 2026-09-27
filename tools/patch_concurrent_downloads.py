from pathlib import Path
import zipfile

ROOT = Path("project/src/main/java/com/downls10")

def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")

main = ROOT / "MainActivity.kt"
replace_once(
    main,
    '''    private fun showOptionsMenu() {
        val options = arrayOf(
            if (showSpeed) "إخفاء سرعة التنزيل" else "إظهار سرعة التنزيل",
            "السجل (History)"
        )

        AlertDialog.Builder(this)
            .setTitle("الخيارات")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        showSpeed = !showSpeed
                        mainPrefs().edit().putBoolean("showSpeed", showSpeed).apply()
                        adapter.setShowSpeed(showSpeed)
                    }
                    1 -> showHistoryDialog()
                }
            }
            .show()
    }
''',
    '''    private fun showOptionsMenu() {
        val options = arrayOf(
            if (showSpeed) "إخفاء سرعة التنزيل" else "إظهار سرعة التنزيل",
            "التنزيلات المتزامنة",
            "السجل (History)"
        )

        AlertDialog.Builder(this)
            .setTitle("الخيارات")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        showSpeed = !showSpeed
                        mainPrefs().edit().putBoolean("showSpeed", showSpeed).apply()
                        adapter.setShowSpeed(showSpeed)
                    }
                    1 -> showConcurrentDownloadsDialog()
                    2 -> showHistoryDialog()
                }
            }
            .show()
    }

    private fun showConcurrentDownloadsDialog() {
        val values = (1..10).toList()
        val current = DownloadsRepository.getMaxConcurrentDownloads(this)

        AlertDialog.Builder(this)
            .setTitle("التنزيلات المتزامنة")
            .setSingleChoiceItems(
                values.map { it.toString() }.toTypedArray(),
                current - 1
            ) { dialog, which ->
                DownloadsRepository.setMaxConcurrentDownloads(this, values[which])
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
'''
)

repo = ROOT / "DownloadsRepository.kt"
replace_once(
    repo,
    '''    private var initialized = false
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 1500L

    fun ensureLoaded(context: Context) {
        if (initialized) return
        initialized = true
        downloadList.clear()
        downloadList.addAll(DownloadPersistence.load(context.applicationContext))
    }
''',
    '''    private var initialized = false
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 1500L
    private const val DEFAULT_CONCURRENT_DOWNLOADS = 8

    fun ensureLoaded(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
        val concurrency = prefs.getInt("download_concurrency", DEFAULT_CONCURRENT_DOWNLOADS).coerceIn(1, 10)
        downloadEngine.setMaxConcurrentDownloads(concurrency)

        if (initialized) return
        initialized = true
        downloadList.clear()
        downloadList.addAll(DownloadPersistence.load(context.applicationContext))
    }

    fun getMaxConcurrentDownloads(context: Context): Int {
        return context.applicationContext
            .getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
            .getInt("download_concurrency", DEFAULT_CONCURRENT_DOWNLOADS)
            .coerceIn(1, 10)
    }

    fun setMaxConcurrentDownloads(context: Context, count: Int) {
        val value = count.coerceIn(1, 10)
        context.applicationContext
            .getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("download_concurrency", value)
            .apply()
        downloadEngine.setMaxConcurrentDownloads(value)
    }
'''
)

engine = ROOT / "DownloadManagerEngine.kt"
replace_once(
    engine,
    "import java.util.concurrent.Executors",
    """import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit"""
)
replace_once(
    engine,
    "    private val executor = Executors.newFixedThreadPool(8)",
    '''    private val executor = ThreadPoolExecutor(
        8,
        8,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    )

    fun setMaxConcurrentDownloads(count: Int) {
        val value = count.coerceIn(1, 10)
        synchronized(executor) {
            if (value > executor.corePoolSize) {
                executor.maximumPoolSize = value
                executor.corePoolSize = value
            } else {
                executor.corePoolSize = value
                executor.maximumPoolSize = value
            }
            executor.prestartAllCoreThreads()
        }
    }'''
)

with zipfile.ZipFile("patched.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for path in Path("project").rglob("*"):
        if path.is_file():
            z.write(path, path.relative_to("project").as_posix())

print("PATCH_OK")
