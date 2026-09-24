package com.downls10

enum class DownloadState {
    DOWNLOADING, PAUSED, COMPLETED, ERROR
}

data class DownloadItem(
    val id: Long,
    var url: String,
    val fileName: String,
    val extension: String,
    var progress: Int = 0,
    var status: String = "في الانتظار...",
    var speed: String = "0 KB/s",
    var state: DownloadState = DownloadState.DOWNLOADING,
    var downloadedBytes: Long = 0L,
    var totalBytes: Long = 0L,
    var localUri: String? = null,
    // تُرسل مع طلب التنزيل ليقبله الموقع (تبقى في الذاكرة فقط ولا تُحفظ على القرص)
    var userAgent: String? = null,
    var referer: String? = null
)
