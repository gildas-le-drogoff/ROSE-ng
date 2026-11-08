package dev.narayan.rose.filejob

import dev.narayan.rose.roseNGLog
import android.content.Context
import dev.narayan.rose.SafManager

@Suppress("NewApi")
object FileOperationRunner {

    private const val TAG = "FileJob"

    fun execute(context: Context, job: FileJob, onProgress: (FileJob) -> Unit, onFinished: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val touchedPaths = mutableListOf<String>()
            val jobLabel = "${job.type.javaClass.simpleName} job ${job.id.take(8)}"
            val startedAt = android.os.SystemClock.elapsedRealtime()
            roseNGLog.i(TAG) { "▶ $jobLabel started (${job.totalItems} items)" }
            roseNGLog.heap(TAG, "Before $jobLabel")
            try {
                JobManager.updateJob(job)
                when (val type = job.type) {
                    is FileJobType.Copy -> {
                        val directBytes = calculateDirectFilesTotalSize(appContext, type.sources)
                        if (directBytes != null) {
                            job.totalBytes = directBytes
                            job.totalItems = type.sources.size
                            job.processedBytes = 0L
                            job.isIndeterminate = false
                        } else {
                            job.totalBytes = 0L
                            job.totalItems = type.sources.size
                            job.processedBytes = 0L
                            job.isIndeterminate = true
                            startAsyncBatchStatsCalculation(appContext, type.sources, job)
                        }

                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")

                            if (isDirectory(appContext, source.path) && SafManager.isSubdirectoryOrSame(type.targetDir.toString(), source.path)) {
                                throw IllegalArgumentException("Cannot copy a directory into itself or its subdirectories: ${source.displayName}")
                            }

                            job.currentFileName = source.displayName
                            onProgress(job)
                            JobManager.updateJob(job)

                            val target = getNonConflictingTarget(appContext, type.targetDir, source.displayName, sourcePath = source.path, isCopy = true)
                            synchronized(job.createdPaths) {
                                job.createdPaths.add(target.toString())
                            }
                            val success = copyRecursive(appContext, source.path, target, job) {
                                onProgress(it)
                                JobManager.updateJob(it)
                            }
                            if (!success && job.failedItems.isEmpty()) {
                                if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                                throw Exception("Failed to copy ${source.displayName}. Check if storage is full or access is denied.")
                            }
                            touchedPaths.add(target.toString())
                        }
                    }
                    is FileJobType.Move -> {
                        val directBytes = calculateDirectFilesTotalSize(appContext, type.sources)
                        if (directBytes != null) {
                            job.totalBytes = directBytes
                            job.totalItems = type.sources.size
                            job.processedBytes = 0L
                            job.isIndeterminate = false
                        } else {
                            job.totalBytes = 0L
                            job.totalItems = type.sources.size
                            job.processedBytes = 0L
                            job.isIndeterminate = true
                            startAsyncBatchStatsCalculation(appContext, type.sources, job)
                        }

                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")

                            if (isDirectory(appContext, source.path) && SafManager.isSubdirectoryOrSame(type.targetDir.toString(), source.path)) {
                                throw IllegalArgumentException("Cannot move a directory into itself or its subdirectories: ${source.displayName}")
                            }

                            job.currentFileName = source.displayName
                            onProgress(job)
                            JobManager.updateJob(job)

                            val target = getNonConflictingTarget(appContext, type.targetDir, source.displayName, sourcePath = source.path, isCopy = false)

                            if (source.path == target.toString() || SafManager.isSamePath(source.path, target.toString())) {
                                job.processedItems++
                                return@forEach
                            }

                            synchronized(job.createdPaths) {
                                job.createdPaths.add(target.toString())
                            }

                            val success = moveRecursive(appContext, source.path, target, job) {
                                onProgress(it)
                                JobManager.updateJob(it)
                            }
                            if (!success && job.failedItems.isEmpty()) {
                                if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                                throw Exception("Failed to move ${source.displayName}. Make sure Shizuku is authorized and target is writable.")
                            }
                            touchedPaths.add(source.path)
                            touchedPaths.add(target.toString())
                        }
                    }
                    is FileJobType.Delete -> {
                        val isAnyRoot = type.targets.any { it.toString() == "/" || dev.narayan.rose.RootManager.isRootPath(it.toString()) }
                        if (isAnyRoot && dev.narayan.rose.RootManager.isRootAvailable()) {
                            val failedTargets = mutableListOf<String>()
                            type.targets.forEach { target ->
                                if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                                job.currentFileName = target.fileName?.toString() ?: target.toString()
                                val clean = target.toString()
                                val success = dev.narayan.rose.RootManager.delete(clean)
                                if (success) {
                                    job.completedPaths.add(target.toString())
                                    touchedPaths.add(target.toString())
                                } else {
                                    failedTargets.add(target.toString())
                                }
                                job.processedItems++
                                job.progress = if (job.totalItems > 0) {
                                    (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                                } else 1f
                                onProgress(job)
                                JobManager.updateJob(job)
                            }
                            if (failedTargets.isNotEmpty()) {
                                failedTargets.forEach { failedPath ->
                                    val name = java.io.File(failedPath).name
                                    synchronized(job.failedItems) {
                                        job.failedItems.add(
                                            FileOperationError(
                                                path = failedPath,
                                                fileName = name,
                                                reason = detectFailureReason(appContext, name, null, null)
                                            )
                                        )
                                    }
                                }
                            }
                            job.progress = 1f
                            onProgress(job)
                            val hasFailures = job.failedItems.isNotEmpty()
                            JobManager.completeJob(
                                job,
                                success = !hasFailures,
                                error = if (hasFailures) "Failed to delete ${failedTargets.size} item(s) with root" else null
                            )
                            onFinished(!hasFailures)
                            return@Thread
                        }

                        if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                            val failedTargets = mutableListOf<String>()
                            type.targets.forEach { target ->
                                if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                                job.currentFileName = target.fileName?.toString() ?: target.toString()
                                val clean = dev.narayan.rose.ShizukuManager.normalize(target.toString())
                                val exitCode = dev.narayan.rose.ShizukuManager.runCommandSync(
                                    "rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}"
                                )
                                if (exitCode == 0) {
                                    job.completedPaths.add(target.toString())
                                    touchedPaths.add(target.toString())
                                } else {
                                    failedTargets.add(target.toString())
                                }
                                job.processedItems++
                                job.progress = if (job.totalItems > 0) {
                                    (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                                } else 1f
                                onProgress(job)
                                JobManager.updateJob(job)
                            }

                            if (touchedPaths.isNotEmpty()) {
                                try {
                                    android.media.MediaScannerConnection.scanFile(appContext, touchedPaths.toTypedArray(), null, null)
                                } catch (e: Exception) {  }
                            }

                            if (failedTargets.isNotEmpty()) {
                                failedTargets.forEach { failedPath ->
                                    val name = java.io.File(failedPath).name
                                    synchronized(job.failedItems) {
                                        job.failedItems.add(
                                            FileOperationError(
                                                path = failedPath,
                                                fileName = name,
                                                reason = detectFailureReason(appContext, name, null, null)
                                            )
                                        )
                                    }
                                }
                            }

                            job.progress = 1f
                            onProgress(job)
                            val hasFailures = job.failedItems.isNotEmpty()
                            JobManager.completeJob(
                                job,
                                success = !hasFailures,
                                error = if (hasFailures) "Failed to delete ${failedTargets.size} item(s)" else null
                            )
                            onFinished(!hasFailures)
                            return@Thread
                        }

                        var totalBytes = 0L
                        var totalItems = 0

                        val firstTarget = type.targets.firstOrNull()?.toFile()
                        val shouldPreScan = type.targets.size < 5 && (firstTarget?.listFiles()?.size ?: 0) < 100

                        if (shouldPreScan) {
                            fun fastScan(file: java.io.File) {
                                totalItems++
                                if (file.isFile) {
                                    totalBytes += file.length()
                                } else {
                                    file.listFiles()?.forEach { fastScan(it) }
                                }
                            }

                            type.targets.forEach { target ->
                                fastScan(target.toFile())
                            }
                        }

                        job.totalItems = totalItems
                        job.totalBytes = totalBytes
                        job.processedItems = 0
                        job.processedBytes = 0L
                        job.isIndeterminate = !shouldPreScan

                        type.targets.forEach { target ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            val success = deleteRecursive(appContext, target, job, onProgress)
                            if (success) {
                                job.completedPaths.add(target.toString())
                                touchedPaths.add(target.toString())
                            }
                        }
                        job.progress = 1f
                        onProgress(job)
                        JobManager.updateJob(job)
                    }
                    is FileJobType.Download -> {
                        if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                        val stats = calculateBatchStats(appContext, listOf(type.source.path))
                        job.totalBytes = stats?.first ?: 0L
                        job.processedBytes = 0L
                        job.isIndeterminate = (stats?.first ?: 0L) <= 0L
                        job.currentFileName = type.source.displayName
                        onProgress(job)
                        JobManager.updateJob(job)
                        val success = copyRecursive(
                            context = appContext,
                            sourcePath = type.source.path,
                            target = type.targetFile,
                            job = job,
                            knownIsDir = false,
                            knownSize = if ((stats?.first ?: 0L) > 0L) stats?.first else null
                        ) {
                            onProgress(it)
                            JobManager.updateJob(it)
                        }
                        val isCancelled = JobManager.isCancelled(job.id)
                        if (!success || isCancelled) {
                            try {
                                val targetF = java.io.File(type.targetFile.toString())
                                if (targetF.exists()) targetF.delete()
                            } catch (e: Exception) {  }
                            if (isCancelled) throw java.io.InterruptedIOException("Cancelled")
                            throw Exception("Failed to download file.")
                        }
                        job.completedPaths.add(type.source.path)
                        touchedPaths.add(type.targetFile.toString())
                    }
                    is FileJobType.Recycle -> {
                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            job.currentFileName = source.displayName
                            job.progress = (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                            onProgress(job)
                            JobManager.updateJob(job)

                            val file = java.io.File(source.path)
                            dev.narayan.rose.RecycleBinManager.recycle(appContext, file) { processed, total ->
                                if (total > 0) {
                                    val itemWeight = 1f / job.totalItems
                                    val itemProgress = processed.toFloat() / total
                                    job.progress = (job.processedItems.toFloat() / job.totalItems + itemProgress * itemWeight).coerceIn(0f, 1f)
                                    onProgress(job)
                                    JobManager.updateJob(job)
                                }
                            }
                            job.completedPaths.add(source.path)
                            touchedPaths.add(source.path)
                            job.processedItems++
                        }
                    }
                    is FileJobType.Restore -> {
                        val metadata = dev.narayan.rose.RecycleBinManager.listItems(appContext)
                        type.sources.forEach { source ->
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            job.currentFileName = source.displayName
                            job.progress = (job.processedItems.toFloat() / job.totalItems).coerceIn(0f, 1f)
                            onProgress(job)
                            JobManager.updateJob(job)
                            val item = metadata.find { it.id == source.path }
                            if (item != null) {
                                val success = dev.narayan.rose.RecycleBinManager.restore(appContext, item) { processed, total ->
                                    if (total > 0) {
                                        val itemWeight = 1f / job.totalItems
                                        val itemProgress = processed.toFloat() / total
                                        job.progress = (job.processedItems.toFloat() / job.totalItems + itemProgress * itemWeight).coerceIn(0f, 1f)
                                        onProgress(job)
                                        JobManager.updateJob(job)
                                    }
                                }
                                if (success) {
                                    touchedPaths.add(item.originalPath)
                                    job.completedPaths.add(source.path)
                                }
                            }
                            job.processedItems++
                            onProgress(job)
                            JobManager.updateJob(job)
                        }
                    }
                    is FileJobType.Extract -> {
                        if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                        job.currentFileName = type.source.fileName.toString()
                        onProgress(job)
                        JobManager.updateJob(job)

                        val success = extractRecursive(type.source, type.targetDir, job) {
                            onProgress(it)
                            JobManager.updateJob(it)
                        }
                        if (!success) {
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            throw Exception("Failed to extract archive. Check if storage is full or archive is corrupted.")
                        }
                        touchedPaths.add(type.targetDir.toString())
                        job.processedItems++
                    }
                    is FileJobType.Compress -> {
                        if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                        job.currentFileName = type.targetFile.fileName.toString()
                        onProgress(job)
                        JobManager.updateJob(job)

                        var lastUpdate = 0L
                        val success = compressRecursive(appContext, type.sources, type.targetFile, job) {
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150 || it.processedBytes == it.totalBytes) {
                                onProgress(it)
                                JobManager.updateJob(it)
                                lastUpdate = now
                            }
                        }
                        if (!success) {
                            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
                            throw Exception("Failed to create archive.")
                        }
                        synchronized(job.createdPaths) {
                            job.createdPaths.add(type.targetFile.toString())
                        }
                        touchedPaths.add(type.targetFile.toString())
                        job.processedItems++
                    }
                }
                if (touchedPaths.isNotEmpty()) {
                    try {
                        android.media.MediaScannerConnection.scanFile(appContext, touchedPaths.toTypedArray(), null, null)
                    } catch (e: Exception) {  }
                }
                val isJobCancelled = JobManager.isCancelled(job.id)
                val hasFailures = job.failedItems.isNotEmpty() || isJobCancelled
                val isSuccess = !hasFailures && !isJobCancelled
                JobManager.completeJob(
                    job,
                    success = isSuccess,
                    error = if (isJobCancelled) "Cancelled" else if (hasFailures) "Some items could not be processed" else null
                )
                onFinished(isSuccess)
            } catch (e: Exception) {
                if (e is java.io.InterruptedIOException) {
                    roseNGLog.i(TAG) { "$jobLabel cancelled" }
                } else {
                    roseNGLog.e(TAG, e) { "$jobLabel crashed" }
                }
                JobManager.completeJob(job, success = false, error = e.message ?: e.javaClass.simpleName)
                onFinished(false)
            } finally {
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                roseNGLog.i(TAG) {
                    "■ $jobLabel finished in $elapsed ms — ${job.processedItems}/${job.totalItems} items, " +
                        "${job.processedBytes}/${job.totalBytes} bytes, ${job.failedItems.size} failure(s)"
                }
                roseNGLog.heap(TAG, "After $jobLabel")
            }
        }.start()
    }
}
