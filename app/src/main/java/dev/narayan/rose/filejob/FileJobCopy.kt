@file:Suppress("NewApi")

package dev.narayan.rose.filejob

import dev.narayan.rose.roseNGLog
import android.content.Context
import dev.narayan.rose.SafManager
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

private const val TAG = "FileJob"

internal fun copyChannelWithProgress(
    srcChannel: java.nio.channels.FileChannel,
    destChannel: java.nio.channels.FileChannel,
    fileSize: Long,
    job: FileJob? = null,
    onProgress: ((FileJob) -> Unit)? = null
) {
    val chunkSize = 4L * 1024L * 1024L
    var position = 0L
    var lastReportedPos = 0L
    var lastUpdate = 0L
    var useDirectBuffer = false
    var directBuffer: java.nio.ByteBuffer? = null

    fun reportDelta() {
        if (job == null || onProgress == null) return
        val delta = position - lastReportedPos
        if (delta <= 0) return
        job.processedBytes += delta
        lastReportedPos = position
        if (job.totalBytes > 0) {
            job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
        }
        onProgress(job)
    }

    val actualSize = if (fileSize > 0) fileSize else try { srcChannel.size() } catch (e: Exception) { 0L }

    while (actualSize <= 0L || position < actualSize) {
        if (job != null) {
            if (JobManager.isCancelled(job.id)) {
                throw java.io.InterruptedIOException("Copy cancelled")
            }
            JobManager.checkWaitIfPaused(job.id)
        }

        val count = if (actualSize > 0) Math.min(chunkSize, actualSize - position) else chunkSize
        var bytesThisLoop = 0L

        if (!useDirectBuffer) {
            try {
                val transferred = srcChannel.transferTo(position, count, destChannel)
                if (transferred > 0) {
                    bytesThisLoop = transferred
                } else {
                    useDirectBuffer = true
                }
            } catch (e: Exception) {
                useDirectBuffer = true
            }
        }

        if (useDirectBuffer) {
            if (directBuffer == null) {
                directBuffer = java.nio.ByteBuffer.allocateDirect(4 * 1024 * 1024)
            }
            directBuffer.clear()
            if (actualSize > 0 && directBuffer.capacity() > (actualSize - position)) {
                directBuffer.limit((actualSize - position).toInt())
            }
            val read = srcChannel.read(directBuffer, position)
            if (read <= 0) break
            directBuffer.flip()
            while (directBuffer.hasRemaining()) {
                destChannel.write(directBuffer)
            }
            bytesThisLoop = read.toLong()
        }

        if (bytesThisLoop <= 0) break
        position += bytesThisLoop

        val now = System.currentTimeMillis()
        if (now - lastUpdate >= 100 || (actualSize > 0 && position >= actualSize)) {
            reportDelta()
            lastUpdate = now
        }
    }
    reportDelta()
}

internal fun copyFileWithProgress(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    totalSize: Long,
    job: FileJob? = null,
    onProgress: ((FileJob) -> Unit)? = null,
    activeProcesses: List<Process> = emptyList(),
    initialBytesAlreadyReported: Long = 0L
) {
    try {
        input.use { inp ->
            output.use { out ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var fileBytesRead = 0L
                var lastReportedFileBytes = initialBytesAlreadyReported
                var lastUpdate = 0L

                val baseProcessed = if (job != null) {
                    (job.processedBytes - initialBytesAlreadyReported).coerceAtLeast(0L)
                } else 0L

                fun reportDelta() {
                    if (job == null) return
                    val effectiveFileBytes = maxOf(fileBytesRead, initialBytesAlreadyReported)
                    if (effectiveFileBytes <= lastReportedFileBytes) return
                    job.processedBytes = baseProcessed + effectiveFileBytes
                    lastReportedFileBytes = effectiveFileBytes
                    if (job.totalBytes > 0) {
                        job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    }
                    onProgress?.invoke(job)
                    JobManager.updateJob(job)
                }

                while (inp.read(buffer).also { bytesRead = it } != -1) {
                    if (job != null) {
                        if (JobManager.isCancelled(job.id)) {
                            activeProcesses.forEach { it.destroy() }
                            throw java.io.InterruptedIOException("Copy cancelled")
                        }
                        JobManager.checkWaitIfPaused(job.id)
                    }
                    out.write(buffer, 0, bytesRead)
                    fileBytesRead += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 100 || (totalSize > 0 && fileBytesRead >= totalSize)) {
                        reportDelta()
                        lastUpdate = now
                    }
                }
                out.flush()
                if (job != null) {
                    val finalBytes = if (totalSize > 0) totalSize else fileBytesRead
                    job.processedBytes = baseProcessed + finalBytes
                    if (job.totalBytes > 0) {
                        job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    }
                    onProgress?.invoke(job)
                    JobManager.updateJob(job)
                }
            }
        }
    } catch (e: Exception) {
        activeProcesses.forEach { it.destroy() }
        throw e
    }
}

internal fun copyRecursive(
    context: Context,
    sourcePath: String,
    target: Path,
    job: FileJob? = null,
    knownIsDir: Boolean? = null,
    knownSize: Long? = null,
    onProgress: ((FileJob) -> Unit)? = null
): Boolean {
    if (job != null) {
        if (JobManager.isCancelled(job.id)) return false
        try {
            JobManager.checkWaitIfPaused(job.id)
        } catch (e: Exception) {
            return false
        }
    }

    val targetPath = SafManager.normalizeSafPath(target.toString())
    val sourceIsDir = knownIsDir ?: isDirectory(context, sourcePath)

    if (sourceIsDir && SafManager.isSubdirectoryOrSame(targetPath, sourcePath)) {
        return false
    }

    val sourceRestricted = SafManager.isRestrictedPath(sourcePath)
    val targetRestricted = SafManager.isRestrictedPath(targetPath)

    val fileSize = if (sourceIsDir) 0L else {
        knownSize ?: if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
            if (sourceRestricted && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(sourcePath)) {
                dev.narayan.rose.ShizukuManager.getFileSize(sourcePath)
            } else {
                SafManager.getReliableSize(context, sourcePath)
            }
        } else {
            try { Files.size(Paths.get(sourcePath)) } catch (e: Exception) { 0L }
        }
    }

    if (job != null && job.totalBytes == 0L && fileSize > 0L) {
        job.totalBytes = fileSize
        job.isIndeterminate = false
        JobManager.updateJob(job)
    }

    val targetFileName = target.fileName?.toString() ?: ""

    if (!sourceIsDir && hasInvalidFatChars(targetFileName)) {
        val reason = detectFailureReason(context, targetFileName, "Invalid characters in filename", null)
        recordFailure(context, job, sourcePath, targetFileName, reason, fileSize, onProgress)
        return false
    }

    var lastException: Exception? = null

    if (!sourceIsDir
        && (sourceRestricted || targetRestricted)
        && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()
        && !SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
        val cleanSrc = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
        val cleanDest = dev.narayan.rose.ShizukuManager.normalize(targetPath)
        val cpDestParent = java.io.File(cleanDest).parent
        if (cpDestParent != null) {
            val cleanParent = dev.narayan.rose.ShizukuManager.normalize(cpDestParent)
            dev.narayan.rose.ShizukuManager.runCommandSync(
                "mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(cleanParent)}"
            )
        }

        fun cleanTarget() {
            try {
                val clean = dev.narayan.rose.ShizukuManager.normalize(cleanDest)
                dev.narayan.rose.ShizukuManager.runCommandSync("rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}")
            } catch (ignored: Exception) {}
        }

        try {
            val cpProcess = dev.narayan.rose.ShizukuManager.newProcess(
                arrayOf("sh", "-c", "cp -f ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)} ${dev.narayan.rose.ShizukuManager.shellEscape(cleanDest)}"),
                null, null
            )

            val isFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            val exitCodeHolder = java.util.concurrent.atomic.AtomicInteger(-1)
            val errorMsgHolder = java.lang.StringBuilder()

            val waitThread = Thread {
                try {
                    try { cpProcess.outputStream.close() } catch (ignored: Exception) {}
                    val errThread = Thread {
                        try {
                            cpProcess.errorStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    if (errorMsgHolder.length < 1000) {
                                        errorMsgHolder.appendLine(line)
                                    }
                                }
                            }
                        } catch (ignored: Exception) {}
                    }.apply { isDaemon = true; start() }

                    val code = cpProcess.waitFor()
                    exitCodeHolder.set(code)
                    try { errThread.join(500) } catch (ignored: Exception) {}
                } catch (e: Exception) {
                    roseNGLog.e(TAG, e) { "cpProcess waiter thread error" }
                    exitCodeHolder.set(-1)
                } finally {
                    isFinished.set(true)
                }
            }.apply {
                name = "ShizukuCpWaiter-${System.currentTimeMillis()}"
                isDaemon = true
                start()
            }

            val bytesAtFileStart = job?.processedBytes ?: 0L
            val destFile = java.io.File(cleanDest)

            var lastPolledBytes = 0L
            while (!isFinished.get()) {
                Thread.sleep(120)
                if (job != null && onProgress != null) {
                    if (JobManager.isCancelled(job.id)) {
                        try { cpProcess.destroy() } catch (ignored: Exception) {}
                        try { waitThread.join(500) } catch (ignored: Exception) {}
                        cleanTarget()
                        return false
                    }
                    try {
                        JobManager.checkWaitIfPaused(job.id)
                    } catch (e: Exception) {
                        try { cpProcess.destroy() } catch (ignored: Exception) {}
                        try { waitThread.join(500) } catch (ignored: Exception) {}
                        cleanTarget()
                        return false
                    }

                    val rawDestBytes = if (!targetRestricted) {
                        val len = destFile.length()
                        if (len > 0L) len else {
                            if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
                                dev.narayan.rose.ShizukuManager.getFileSize(cleanDest)
                            } else len
                        }
                    } else {
                        val len = destFile.length()
                        if (len > 0L) len else {
                            dev.narayan.rose.ShizukuManager.getFileSize(cleanDest)
                        }
                    }

                    val destBytes = if (fileSize > 0L) rawDestBytes.coerceIn(0L, fileSize) else rawDestBytes
                    if (destBytes >= lastPolledBytes) {
                        lastPolledBytes = destBytes
                        job.processedBytes = bytesAtFileStart + destBytes
                        if (job.totalBytes > 0L) {
                            job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                        }
                        JobManager.updateJob(job)
                        onProgress(job)
                    }
                }
            }

            try { waitThread.join(2000) } catch (ignored: Exception) {}
            val cpExitCode = exitCodeHolder.get()
            if (cpExitCode == 0) {
                job?.let { j ->
                    j.processedItems++
                    val finalFileBytes = if (fileSize > 0L) fileSize else {
                        if (targetRestricted) dev.narayan.rose.ShizukuManager.getFileSize(cleanDest)
                        else destFile.length()
                    }
                    j.processedBytes = bytesAtFileStart + finalFileBytes
                    if (j.totalBytes > 0L) {
                        j.progress = (j.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                    }
                    JobManager.updateJob(j)
                    onProgress?.invoke(j)
                }
                return true
            } else {
                val err = errorMsgHolder.toString().trim()
                roseNGLog.e(TAG) { "cp failed with exit code $cpExitCode: $err" }
                lastException = Exception("cp failed ($cpExitCode): $err")
                cleanTarget()
            }
        } catch (e: Exception) {
            lastException = e
            if (job != null && JobManager.isCancelled(job.id)) {
                cleanTarget()
                return false
            }
            roseNGLog.e(TAG, e) { "Fast path cp failed with exception, falling through" }
            cleanTarget()
        }
    }

    if (sourceRestricted && targetRestricted
        && (!dev.narayan.rose.ShizukuManager.isAvailable() || !dev.narayan.rose.ShizukuManager.hasPermission())
        && !SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
        val targetParentForCopy = target.parent?.toString()
        if (targetParentForCopy != null) {
            val copied = SafManager.copyDocument(context, sourcePath, targetParentForCopy)
            if (copied) {
                val expectedName = target.fileName.toString()
                val actualSrcName = sourcePath.substringAfterLast('/')
                if (expectedName != actualSrcName) {
                    SafManager.rename(context, "$targetParentForCopy/$actualSrcName", expectedName)
                }
                job?.let { j ->
                    j.processedItems++
                    if (fileSize > 0L) {
                        j.processedBytes += fileSize
                        if (j.totalBytes > 0L) {
                            j.progress = (j.processedBytes.toFloat() / j.totalBytes).coerceIn(0f, 1f)
                        }
                        JobManager.updateJob(j)
                        onProgress?.invoke(j)
                    }
                }
                return true
            }
        }
    }

    if (sourceIsDir) {
        val dirCreated = try {
            if (targetRestricted) {
                if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(targetPath)) {
                    val clean = dev.narayan.rose.ShizukuManager.normalize(targetPath)
                    dev.narayan.rose.ShizukuManager.runCommandSync("mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}") == 0
                } else {
                    SafManager.createDirectory(context, targetPath)
                }
            } else if (!Files.exists(target)) {
                Files.createDirectories(target)
                true
            } else true
        } catch (e: Exception) {
            val reason = detectFailureReason(context, targetFileName, e.message, e)
            recordFailure(context, job, sourcePath, targetFileName, reason, 0L, onProgress)
            false
        }

        if (!dirCreated) {
            return false
        }

        val children = listChildren(context, sourcePath)
        children.forEach { child ->
            if (job != null && JobManager.isCancelled(job.id)) return false
            job?.currentFileName = child.name
            val childTarget = target.resolve(child.name)
            copyRecursive(
                context = context,
                sourcePath = child.path,
                target = childTarget,
                job = job,
                knownIsDir = child.isDirectory,
                knownSize = child.size,
                onProgress = onProgress
            )
        }
        return true
    } else {
        fun cleanTarget() {
            if (targetRestricted) {
                if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(targetPath)) {
                    val clean = dev.narayan.rose.ShizukuManager.normalize(targetPath)
                    dev.narayan.rose.ShizukuManager.runCommandSync("rm -rf ${dev.narayan.rose.ShizukuManager.shellEscape(clean)}")
                } else {
                    SafManager.delete(context, targetPath)
                }
            } else {
                try { Files.deleteIfExists(target) } catch (ignored: Exception) {}
            }
        }

        var srcChannel: java.nio.channels.FileChannel? = null
        var srcCloseable: java.io.Closeable? = null
        var destChannel: java.nio.channels.FileChannel? = null
        var destCloseable: java.io.Closeable? = null

        if (!SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
            try {
                if (sourceRestricted) {
                    val pfd = SafManager.openFileDescriptor(context, sourcePath, "r")
                    if (pfd != null) {
                        val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                        srcCloseable = stream
                        srcChannel = stream.channel
                    }
                } else {
                    val fis = java.io.FileInputStream(java.io.File(sourcePath))
                    srcCloseable = fis
                    srcChannel = fis.channel
                }

                if (targetRestricted) {
                    val pfd = SafManager.openFileDescriptorForNewFile(context, targetPath)
                    if (pfd != null) {
                        val stream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                        destCloseable = stream
                        destChannel = stream.channel
                    }
                } else {
                    target.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                    val fos = java.io.FileOutputStream(target.toFile())
                    destCloseable = fos
                    destChannel = fos.channel
                }

                if (srcChannel != null && destChannel != null) {
                    copyChannelWithProgress(srcChannel, destChannel, fileSize, job, onProgress)
                    job?.let { it.processedItems++ }
                    return true
                }
            } catch (e: Exception) {
                lastException = e
                cleanTarget()
                if (JobManager.isCancelled(job?.id ?: "")) {
                    throw java.io.InterruptedIOException("Cancelled")
                }
            } finally {
                try { srcCloseable?.close() } catch (ignored: Exception) {}
                try { destCloseable?.close() } catch (ignored: Exception) {}
            }
        }

        var simulatedNetworkBytes = 0L
        val cancellationSignal = android.os.CancellationSignal()
        val isCloud = SafManager.isCloudUri(sourcePath)

        val input = if (isCloud && job != null) {
            val inputRef = java.util.concurrent.atomic.AtomicReference<InputStream?>()
            val isOpeningFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            val openException = java.util.concurrent.atomic.AtomicReference<Exception?>()

            val openThread = Thread({
                try {
                    val stream = SafManager.openInputStream(context, sourcePath, cancellationSignal)
                    inputRef.set(stream)
                } catch (e: Exception) {
                    openException.set(e)
                } finally {
                    isOpeningFinished.set(true)
                }
            }, "CloudStreamOpener-${job.id}").apply {
                isDaemon = true
                start()
            }

            val initialRx = android.net.TrafficStats.getTotalRxBytes()
            val hasTrafficStats = initialRx != android.net.TrafficStats.UNSUPPORTED.toLong()
            val baseProcessedBytes = job.processedBytes

            while (!isOpeningFinished.get()) {
                Thread.sleep(120)

                if (JobManager.isCancelled(job.id)) {
                    cancellationSignal.cancel()
                    openThread.interrupt()
                    return false
                }
                try {
                    JobManager.checkWaitIfPaused(job.id)
                } catch (e: Exception) {
                    cancellationSignal.cancel()
                    openThread.interrupt()
                    return false
                }

                if (fileSize > 0L && hasTrafficStats) {
                    val currentRx = android.net.TrafficStats.getTotalRxBytes()
                    if (currentRx >= initialRx) {
                        val netDelta = currentRx - initialRx
                        val progressBytes = netDelta.coerceIn(0L, (fileSize * 0.98).toLong())
                        if (progressBytes > simulatedNetworkBytes) {
                            simulatedNetworkBytes = progressBytes
                            job.processedBytes = baseProcessedBytes + progressBytes
                            if (job.totalBytes > 0L) {
                                job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 0.98f)
                            }
                            JobManager.updateJob(job)
                            onProgress?.invoke(job)
                        }
                    }
                } else if (fileSize <= 0L) {
                    if (!job.isIndeterminate) {
                        job.isIndeterminate = true
                        JobManager.updateJob(job)
                        onProgress?.invoke(job)
                    }
                }
            }

            try { openThread.join(2000) } catch (ignored: Exception) {}
            if (openException.get() != null) {
                lastException = openException.get()
                null
            } else {
                inputRef.get()
            }
        } else {
            try {
                if ((sourcePath == "/" || dev.narayan.rose.RootManager.isRootPath(sourcePath)) && dev.narayan.rose.RootManager.isRootAvailable()) {
                    dev.narayan.rose.RootManager.openInputStream(sourcePath)
                } else if (sourceRestricted || SafManager.isSafUri(sourcePath)) {
                    SafManager.openInputStream(context, sourcePath)
                } else {
                    Files.newInputStream(Paths.get(sourcePath))
                }
            } catch (e: Exception) {
                lastException = e
                null
            }
        }

        val output = try {
            if ((targetPath == "/" || dev.narayan.rose.RootManager.isRootPath(targetPath)) && dev.narayan.rose.RootManager.isRootAvailable()) {
                dev.narayan.rose.RootManager.openOutputStream(targetPath)
            } else if (targetRestricted) {
                SafManager.openOutputStreamForNewFile(context, targetPath)
            } else {
                target.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                Files.newOutputStream(target)
            }
        } catch (e: Exception) {
            lastException = e
            null
        }

        var streamCopied = false
        if (input != null && output != null) {
            streamCopied = try {
                copyFileWithProgress(
                    input = input,
                    output = output,
                    totalSize = fileSize,
                    job = job,
                    onProgress = onProgress,
                    activeProcesses = emptyList(),
                    initialBytesAlreadyReported = simulatedNetworkBytes
                )
                job?.let { it.processedItems++ }
                true
            } catch (e: Exception) {
                lastException = e
                cleanTarget()
                false
            }
        } else {
            cleanTarget()
        }

        if (!streamCopied) {
            val reason = detectFailureReason(context, targetFileName, lastException?.message, lastException)
            recordFailure(context, job, sourcePath, targetFileName, reason, fileSize, onProgress)
            return false
        }
        return true
    }
}

internal fun getPackageDir(path: String): String? {
    val clean = dev.narayan.rose.ShizukuManager.normalize(path)
    val markerData = "/Android/data/"
    val markerObb = "/Android/obb/"
    val marker = when {
        clean.contains(markerData) -> markerData
        clean.contains(markerObb) -> markerObb
        else -> null
    } ?: return null
    val after = clean.substringAfter(marker)
    val pkg = after.substringBefore('/')
    return if (pkg.isNotBlank()) pkg else null
}

internal fun moveRecursive(context: Context, sourcePath: String, target: Path, job: FileJob? = null, onProgress: ((FileJob) -> Unit)? = null): Boolean {
    if (job != null) {
        if (JobManager.isCancelled(job.id)) return false
        try {
            JobManager.checkWaitIfPaused(job.id)
        } catch (e: Exception) {
            return false
        }
    }

    val targetPath = SafManager.normalizeSafPath(target.toString())
    if (isDirectory(context, sourcePath) && SafManager.isSubdirectoryOrSame(targetPath, sourcePath)) {
        return false
    }
    val sourceRestricted = SafManager.isRestrictedPath(sourcePath)
    val targetRestricted = SafManager.isRestrictedPath(targetPath)

    if (!sourceRestricted && !targetRestricted && !SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
        val srcFile = java.io.File(sourcePath)
        val movedSize = if (srcFile.isFile) srcFile.length() else 0L
        return try {
            Files.move(Paths.get(sourcePath), target, StandardCopyOption.REPLACE_EXISTING)
            job?.let {
                it.processedItems++
                if (movedSize > 0L) it.processedBytes += movedSize
                if (it.totalBytes > 0L) {
                    it.progress = (it.processedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f)
                }
                JobManager.updateJob(it)
                onProgress?.invoke(it)
            }
            true
        } catch (e: Exception) {
            if (copyRecursive(context, sourcePath, target, job, onProgress = onProgress)) {
                if (job?.failedItems?.isEmpty() != false) {
                    deleteRecursive(context, Paths.get(sourcePath), null, null)
                } else {
                    deleteSucceededSourceItems(context, sourcePath, job)
                }
                true
            } else false
        }
    }

    if (sourceRestricted && targetRestricted
        && dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()
        && !SafManager.isSafUri(sourcePath) && !SafManager.isSafUri(targetPath)) {
        val srcPkg = getPackageDir(sourcePath)
        val dstPkg = getPackageDir(targetPath)
        if (srcPkg != null && dstPkg != null && srcPkg == dstPkg) {
            val cleanSrc = dev.narayan.rose.ShizukuManager.normalize(sourcePath)
            val cleanTarget = dev.narayan.rose.ShizukuManager.normalize(targetPath)
            val parent = java.io.File(cleanTarget).parent
            if (parent != null) {
                val cleanParent = dev.narayan.rose.ShizukuManager.normalize(parent)
                dev.narayan.rose.ShizukuManager.runCommandSync("mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(cleanParent)}")
            }
            val isDir = dev.narayan.rose.ShizukuManager.runCommandSync("[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)} ]") == 0
            val movedFileSize = if (isDir) {
                dev.narayan.rose.ShizukuManager.getFolderSize(cleanSrc)
            } else {
                dev.narayan.rose.ShizukuManager.getFileSize(cleanSrc)
            }
            val exitCode = dev.narayan.rose.ShizukuManager.runCommandSync(
                "mv -f ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSrc)} ${dev.narayan.rose.ShizukuManager.shellEscape(cleanTarget)}"
            )
            if (exitCode == 0) {
                job?.let {
                    it.processedItems++
                    if (movedFileSize > 0L) it.processedBytes += movedFileSize
                    if (it.totalBytes > 0L) {
                        it.progress = (it.processedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f)
                    }
                    JobManager.updateJob(it)
                    onProgress?.invoke(it)
                }
                return true
            }
        }
    }

    if (sourceRestricted && targetRestricted
        && (!dev.narayan.rose.ShizukuManager.isAvailable() || !dev.narayan.rose.ShizukuManager.hasPermission())) {
        val targetParent = target.parent?.toString()
        if (targetParent != null) {
            val moved = SafManager.moveDocument(context, sourcePath, targetParent)
            if (moved) {
                val expectedName = target.fileName.toString()
                val actualSrcName = sourcePath.substringAfterLast('/')
                if (expectedName != actualSrcName) {
                    SafManager.rename(context, "$targetParent/$actualSrcName", expectedName)
                }
                job?.let {
                    it.processedItems++
                    val sz = SafManager.getReliableSize(context, sourcePath)
                    if (sz > 0L) it.processedBytes += sz
                    if (it.totalBytes > 0L) {
                        it.progress = (it.processedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f)
                    }
                    JobManager.updateJob(it)
                    onProgress?.invoke(it)
                }
                return true
            }
        }
    }

    if (copyRecursive(context, sourcePath, target, job, onProgress = onProgress)) {
        if (job?.failedItems?.isEmpty() != false) {
            deleteRecursive(context, Paths.get(sourcePath), null, null)
        } else {
            deleteSucceededSourceItems(context, sourcePath, job)
        }
        return true
    }
    return false
}
