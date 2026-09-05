package com.downloads10.app

data class DownloadItem(
    val id: Long,
    var url: String,
    var fileName: String,
    var size: Long = -1L,
    var downloaded: Long = 0L,
    var speed: Long = 0L,
    var status: Status = Status.QUEUED,
    var error: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var localUri: String = ""
) {
    enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }
}
