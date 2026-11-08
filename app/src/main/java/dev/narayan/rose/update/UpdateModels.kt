package dev.narayan.rose.update

import java.io.File

sealed class UpdateCheckResult {
    object Idle : UpdateCheckResult()
    object Checking : UpdateCheckResult()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

data class UpdateInfo(
    val tagName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkUrl: String?
)

sealed class UpdateDownloadState {
    object Idle : UpdateDownloadState()
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateDownloadState() {
        val progress: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    }
    data class ReadyToInstall(val file: File) : UpdateDownloadState()
    data class Error(val message: String) : UpdateDownloadState()
}
