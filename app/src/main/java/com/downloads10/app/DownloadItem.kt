package com.downloads10.app

data class DownloadItem(
    val id: Long,
    var url: String,
    var fileName: String,
    var totalBytes: Long = -1L,
    var downloadedBytes: Long = 0L,
    var status: String = STATUS_QUEUED,
    var speedBytes: Long = 0L,
    var filePath: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var error: String = ""
) {
    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}
