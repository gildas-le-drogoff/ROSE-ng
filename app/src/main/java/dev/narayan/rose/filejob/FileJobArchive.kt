@file:Suppress("NewApi")

package dev.narayan.rose.filejob

import dev.narayan.rose.roseNGLog
import android.content.Context
import dev.narayan.rose.CompressSource
import dev.narayan.rose.SafManager
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val TAG = "FileJob"

internal fun extractRecursive(sourceZip: Path, targetDir: Path, job: FileJob, onProgress: (FileJob) -> Unit): Boolean {
    try {
        val file = sourceZip.toFile()
        if (!Files.exists(targetDir)) Files.createDirectories(targetDir)

        val extractType = job.type as? FileJobType.Extract
        val entriesToExtract = extractType?.entries
        val passphrase = extractType?.passphrase
        val entries = dev.narayan.rose.ArchiveManager.readEntries(file)
        val filteredEntries = if (entriesToExtract != null) {
            entries.filter { entry ->
                entriesToExtract.any { target ->
                    entry.name == target || entry.name.startsWith("$target/")
                }
            }
        } else {
            entries
        }

        job.totalItems = filteredEntries.size
        job.totalBytes = filteredEntries.filter { !it.isDirectory }.sumOf { it.size }
        job.processedItems = 0
        job.processedBytes = 0L

        val targetSet = if (entriesToExtract != null) filteredEntries.map { it.name }.toSet() else null

        dev.narayan.rose.ArchiveManager.extractAll(file, passphrase) { name, isDirectory, copyTask ->
            if (JobManager.isCancelled(job.id)) return@extractAll
            if (targetSet != null && name !in targetSet) return@extractAll

            val entryFile = targetDir.resolve(name).normalize()
            if (!entryFile.startsWith(targetDir)) return@extractAll

            if (isDirectory) {
                Files.createDirectories(entryFile)
            } else {
                if (entryFile.parent != null) Files.createDirectories(entryFile.parent)
                Files.newOutputStream(entryFile).use { output ->
                    val progressOutput = object : java.io.OutputStream() {
                        override fun write(b: Int) {
                            output.write(b)
                            job.processedBytes += 1
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            output.write(b, off, len)
                            job.processedBytes += len
                            if (job.totalBytes > 0 && System.currentTimeMillis() % 100 == 0L) {
                                job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                                onProgress(job)
                            }
                        }
                    }
                    copyTask(progressOutput)
                }
            }
            job.processedItems++
            job.currentFileName = name.substringAfterLast('/')
            onProgress(job)
        }
        return true
    } catch (e: Exception) {
        roseNGLog.e(TAG, e) { "Archive extraction failed" }
        if (JobManager.isCancelled(job.id)) return false
        throw e
    }
}

internal fun openSourceInputStream(context: Context, path: String): InputStream? {
    if ((path == "/" || dev.narayan.rose.RootManager.isRootPath(path)) && dev.narayan.rose.RootManager.isRootAvailable()) {
        return try {
            dev.narayan.rose.RootManager.openInputStream(path)
        } catch (e: Exception) {
            null
        }
    }
    val restricted = SafManager.isRestrictedPath(path) || SafManager.isSafUri(path)
    if (restricted) {
        if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
            val pfd = dev.narayan.rose.ShizukuManager.openFileDescriptor(context, path, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            if (pfd != null) {
                return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            }
        }
        return SafManager.openInputStream(context, path)
    }
    return try {
        Files.newInputStream(Paths.get(path))
    } catch (e: Exception) {
        try {
            java.io.FileInputStream(path)
        } catch (e2: Exception) {
            null
        }
    }
}

internal fun buildCompressSource(
    context: Context,
    path: String,
    entryName: String,
    knownIsDir: Boolean? = null,
    knownSize: Long? = null
): CompressSource {
    val isDir = knownIsDir ?: isDirectory(context, path)
    val size = if (isDir) 0L else {
        knownSize ?: if ((path == "/" || dev.narayan.rose.RootManager.isRootPath(path)) && dev.narayan.rose.RootManager.isRootAvailable()) {
            dev.narayan.rose.RootManager.getFileSize(path)
        } else if (SafManager.isRestrictedPath(path) || SafManager.isSafUri(path)) {
            if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
                dev.narayan.rose.ShizukuManager.getFileSize(path)
            } else {
                SafManager.getReliableSize(context, path)
            }
        } else {
            try {
                Files.size(Paths.get(path))
            } catch (e: Exception) {
                java.io.File(path).length()
            }
        }
    }
    val lastModified = try {
        java.io.File(path).lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }

    return CompressSource(
        entryName = entryName,
        isDirectory = isDir,
        size = size,
        lastModified = lastModified,
        openStream = if (!isDir) {
            { openSourceInputStream(context, path) }
        } else null,
        children = if (isDir) {
            {
                val children = listChildren(context, path)
                children.map { child ->
                    val childEntryName = if (entryName.isEmpty()) child.name else "$entryName/${child.name}"
                    buildCompressSource(context, child.path, childEntryName, child.isDirectory, child.size)
                }
            }
        } else null
    )
}

internal fun transferArchiveToTarget(context: Context, tempFile: java.io.File, targetPath: String): Boolean {
    var out: OutputStream? = null
    try {
        if ((targetPath == "/" || dev.narayan.rose.RootManager.isRootPath(targetPath)) && dev.narayan.rose.RootManager.isRootAvailable()) {
            out = dev.narayan.rose.RootManager.openOutputStream(targetPath)
        } else if (SafManager.isRestrictedPath(targetPath) || SafManager.isSafUri(targetPath)) {
            if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(targetPath)) {
                val clean = dev.narayan.rose.ShizukuManager.normalize(targetPath)
                val parent = java.io.File(clean).parent
                if (parent != null) {
                    val cleanParent = dev.narayan.rose.ShizukuManager.normalize(parent)
                    dev.narayan.rose.ShizukuManager.runCommandSync("mkdir -p ${dev.narayan.rose.ShizukuManager.shellEscape(cleanParent)}")
                }
                val pfd = dev.narayan.rose.ShizukuManager.openFileDescriptor(
                    context,
                    clean,
                    android.os.ParcelFileDescriptor.MODE_CREATE or
                            android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or
                            android.os.ParcelFileDescriptor.MODE_TRUNCATE
                )
                if (pfd != null) {
                    out = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                }
            }
            if (out == null) {
                out = SafManager.openOutputStreamForNewFile(context, targetPath)
            }
        } else {
            val file = java.io.File(targetPath)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            out = java.io.FileOutputStream(file)
        }

        if (out == null) return false

        tempFile.inputStream().use { input ->
            out.use { output ->
                input.copyTo(output, bufferSize = 128 * 1024)
            }
        }
        return true
    } catch (e: Exception) {
        roseNGLog.e(TAG, e) { "Stream copy failed" }
        return false
    } finally {
        try { out?.close() } catch (ignored: Exception) {}
    }
}

internal fun compressRecursive(context: android.content.Context, sources: List<SourcePath>, targetFile: Path, job: FileJob, onProgress: (FileJob) -> Unit): Boolean {
    val targetPath = targetFile.toString()
    val isRestrictedTarget = SafManager.isRestrictedPath(targetPath) ||
            SafManager.isSafUri(targetPath) ||
            ((targetPath == "/" || dev.narayan.rose.RootManager.isRootPath(targetPath)) && dev.narayan.rose.RootManager.isRootAvailable())

    val tempArchiveFile = if (isRestrictedTarget) {
        val fileName = targetFile.fileName?.toString() ?: "archive.zip"
        java.io.File(context.cacheDir, "temp_compress_${System.currentTimeMillis()}_$fileName")
    } else {
        null
    }
    val actualOutputFile = tempArchiveFile ?: targetFile.toFile()

    try {
        val stats = calculateBatchStats(context, sources.map { it.path })
        job.totalBytes = stats?.first ?: 0L
        job.processedBytes = 0L
        job.isIndeterminate = stats == null

        val compressType = job.type as? FileJobType.Compress
        val passphrase = compressType?.passphrase

        val compressSources = sources.map { source ->
            buildCompressSource(context, source.path, source.displayName)
        }

        dev.narayan.rose.ArchiveManager.compressSources(
            sources = compressSources,
            targetFile = actualOutputFile,
            passphrase = passphrase
        ) { name, processed ->
            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
            job.currentFileName = name
            job.processedBytes += processed
            if (job.totalBytes > 0) {
                job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                onProgress(job)
            }
        }

        if (tempArchiveFile != null) {
            if (JobManager.isCancelled(job.id)) throw java.io.InterruptedIOException("Cancelled")
            val transferred = transferArchiveToTarget(context, tempArchiveFile, targetPath)
            if (!transferred) {
                return false
            }
        }

        return true
    } catch (e: Exception) {
        roseNGLog.e(TAG, e) { "Copy failed" }
        return false
    } finally {
        if (tempArchiveFile != null) {
            try { tempArchiveFile.delete() } catch (ignored: Exception) {}
        }
    }
}
