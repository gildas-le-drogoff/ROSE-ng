@file:Suppress("NewApi")

package dev.narayan.rose.filejob

import android.content.Context

internal fun hasInvalidFatChars(name: String): Boolean {
    return name.any { it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' || it == '\\' }
}

internal fun detectFailureReason(context: Context, fileName: String, errorMsg: String?, exception: Throwable?): String {
    if (hasInvalidFatChars(fileName)) {
        return context.getString(dev.narayan.rose.R.string.dialog_reason_invalid_characters)
    }
    val msg = (errorMsg ?: "") + " " + (exception?.message ?: "")
    return when {
        msg.contains("invalid", ignoreCase = true) || msg.contains("EINVAL", ignoreCase = true) ->
            context.getString(dev.narayan.rose.R.string.dialog_reason_invalid_characters)
        msg.contains("space", ignoreCase = true) || msg.contains("full", ignoreCase = true) || msg.contains("ENOSPC", ignoreCase = true) ->
            context.getString(dev.narayan.rose.R.string.dialog_reason_storage_full)
        msg.contains("permission", ignoreCase = true) || msg.contains("denied", ignoreCase = true) || msg.contains("EACCES", ignoreCase = true) ->
            context.getString(dev.narayan.rose.R.string.dialog_reason_access_denied)
        else -> context.getString(dev.narayan.rose.R.string.dialog_reason_io_error)
    }
}

internal fun recordFailure(
    context: Context,
    job: FileJob?,
    sourcePath: String,
    fileName: String,
    reason: String,
    fileSize: Long,
    onProgress: ((FileJob) -> Unit)?
) {
    job?.let { j ->
        synchronized(j.failedItems) {
            j.failedItems.add(FileOperationError(path = sourcePath, fileName = fileName, reason = reason))
        }
        j.processedItems++
        if (fileSize > 0L) j.processedBytes += fileSize
        if (j.totalBytes > 0L) {
            j.progress = (j.processedBytes.toFloat() / j.totalBytes).coerceIn(0f, 1f)
        }
        JobManager.updateJob(j)
        onProgress?.invoke(j)
    }
}
