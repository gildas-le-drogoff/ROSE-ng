@file:Suppress("NewApi")

package dev.narayan.rose.filejob

import android.content.Context
import dev.narayan.rose.SafManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal fun deleteRecursive(context: Context, path: Path, job: FileJob? = null, onProgress: ((FileJob) -> Unit)? = null): Boolean {
    if (job != null) {
        if (JobManager.isCancelled(job.id)) return false
        try {
            JobManager.checkWaitIfPaused(job.id)
        } catch (e: Exception) {
            return false
        }
    }

    val pathStr = SafManager.normalizeSafPath(path.toString())
    val file = path.toFile()
    val size = if (file.isFile) file.length() else if (SafManager.isSafUri(pathStr)) SafManager.getReliableSize(context, pathStr) else 0L

    if ((pathStr == "/" || dev.narayan.rose.RootManager.isRootPath(pathStr)) && dev.narayan.rose.RootManager.isRootAvailable()) {
        val success = dev.narayan.rose.RootManager.delete(pathStr)
        if (success) {
            if (job != null && onProgress != null) {
                job.processedBytes += size
                job.processedItems++
                onProgress(job)
                JobManager.updateJob(job)
            }
            return true
        } else {
            if (job != null) {
                recordFailure(context, job, pathStr, file.name, detectFailureReason(context, file.name, "Failed to delete with root", null), size, onProgress)
            }
            return false
        }
    }

    if (SafManager.isRestrictedPath(pathStr) || SafManager.isCloudUri(pathStr) || SafManager.isSafUri(pathStr)) {
        val success = if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(pathStr) && !SafManager.isCloudUri(pathStr)) {
            val clean = dev.narayan.rose.ShizukuManager.normalize(pathStr)
            dev.narayan.rose.ShizukuManager.runCommandSync(
                "rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}"
            ) == 0
        } else if (SafManager.isCloudUri(pathStr) || SafManager.hasPermission(context, pathStr)) {
            SafManager.delete(context, pathStr)
        } else {
            false
        }
        if (success) {
            if (job != null && onProgress != null) {
                job.processedBytes += size
                job.processedItems++
                if (!job.isIndeterminate) {
                    if (job.totalBytes > 0) {
                        job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    } else if (job.totalItems > 0) {
                        job.progress = (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                    }
                }
                onProgress(job)
                JobManager.updateJob(job)
            }
            return true
        } else {
            if (job != null) {
                recordFailure(context, job, pathStr, file.name, detectFailureReason(context, file.name, "Failed to delete restricted path", null), size, onProgress)
            }
            return false
        }
    }

    var allChildrenDeleted = true
    if (file.isDirectory) {
        file.listFiles()?.forEach {
            val childSuccess = deleteRecursive(context, it.toPath(), job, onProgress)
            if (!childSuccess) allChildrenDeleted = false
        }
    }

    var deleted = false
    try {
        Files.delete(path)
        deleted = true
    } catch (e: Exception) {
        deleted = file.delete()
    }

    if (!deleted && (file.exists() || (SafManager.isRestrictedPath(pathStr) && SafManager.exists(context, pathStr)))) {
        if (job != null) {
            recordFailure(context, job, pathStr, file.name, detectFailureReason(context, file.name, null, null), size, onProgress)
        }
        return false
    }

    if (job != null && onProgress != null) {
        job.processedBytes += size
        job.processedItems++

        val shouldUpdate = if (job.processedItems < 10) true
        else if (job.processedItems < 100) job.processedItems % 10 == 0
        else job.processedItems % 50 == 0 || job.processedItems == job.totalItems

        if (shouldUpdate) {
            job.currentFileName = path.fileName.toString()
            if (!job.isIndeterminate) {
                if (job.totalBytes > 0) {
                    job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                } else if (job.totalItems > 0) {
                    job.progress = (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                }
            }
            onProgress(job)
            JobManager.updateJob(job)
        }
    }
    return true
}

internal fun deleteSucceededSourceItems(context: Context, sourcePath: String, job: FileJob?) {
    if (job == null) return
    val failedPaths = synchronized(job.failedItems) {
        job.failedItems.map { it.path }.toSet()
    }
    if (failedPaths.isEmpty()) {
        deleteRecursive(context, Paths.get(sourcePath), null, null)
        return
    }

    fun isOrContainsFailed(path: String): Boolean {
        val normPath = dev.narayan.rose.ShizukuManager.normalize(path)
        return failedPaths.any { failed ->
            val normFailed = dev.narayan.rose.ShizukuManager.normalize(failed)
            normFailed == normPath || normFailed.startsWith(if (normPath.endsWith("/")) normPath else "$normPath/")
        }
    }

    fun cleanSource(currentSource: String) {
        if (!isOrContainsFailed(currentSource)) {
            deleteRecursive(context, Paths.get(currentSource), null, null)
        } else if (isDirectory(context, currentSource)) {
            val children = listChildren(context, currentSource)
            children.forEach { child ->
                cleanSource(child.path)
            }
            val remaining = listChildren(context, currentSource)
            if (remaining.isEmpty()) {
                deleteRecursive(context, Paths.get(currentSource), null, null)
            }
        }
    }

    cleanSource(sourcePath)
}
