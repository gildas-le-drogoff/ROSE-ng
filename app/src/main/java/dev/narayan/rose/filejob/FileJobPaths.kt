@file:Suppress("NewApi")

package dev.narayan.rose.filejob

import android.content.Context
import dev.narayan.rose.SafManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal fun calculateDirectFilesTotalSize(context: Context, sources: List<SourcePath>): Long? {
    var sum = 0L
    for (source in sources) {
        val path = source.path
        if (SafManager.isSafUri(path)) return null
        val isRestricted = SafManager.isRestrictedPath(path)
        if (!isRestricted) {
            val f = java.io.File(path)
            if (f.isDirectory) return null
            if (f.isFile) {
                sum += f.length()
            } else {
                return null
            }
        } else if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission()) {
            val clean = dev.narayan.rose.ShizukuManager.normalize(path)
            val isDir = dev.narayan.rose.ShizukuManager.runCommandSync(
                "[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(clean)} ]"
            ) == 0
            if (isDir) return null
            val size = dev.narayan.rose.ShizukuManager.getFileSize(clean)
            sum += size
        } else {
            if (SafManager.isDirectory(context, path)) return null
            val size = SafManager.getReliableSize(context, path)
            sum += size
        }
    }
    return sum
}

internal fun startAsyncBatchStatsCalculation(
    context: Context,
    sources: List<SourcePath>,
    job: FileJob
) {
    Thread {
        try {
            var totalBytes = 0L
            var totalCount = 0
            for (source in sources) {
                if (JobManager.isCancelled(job.id) || JobManager.isCompleted(job.id)) return@Thread
                val path = source.path
                val isRestricted = SafManager.isRestrictedPath(path) || SafManager.isSafUri(path)
                if (!isRestricted) {
                    val f = java.io.File(path)
                    if (f.isDirectory) {
                        fun fastDirScan(dir: java.io.File) {
                            if (JobManager.isCancelled(job.id) || JobManager.isCompleted(job.id)) return
                            dir.listFiles()?.forEach { child ->
                                if (child.isDirectory) {
                                    fastDirScan(child)
                                } else {
                                    totalCount++
                                    totalBytes += child.length()
                                }
                            }
                        }
                        fastDirScan(f)
                    } else {
                        totalCount++
                        totalBytes += f.length()
                    }
                } else if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
                    val clean = dev.narayan.rose.ShizukuManager.normalize(path)
                    val isDir = dev.narayan.rose.ShizukuManager.runCommandSync(
                        "[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(clean)} ]"
                    ) == 0
                    if (isDir) {
                        val folderSize = dev.narayan.rose.ShizukuManager.getFolderSize(clean)
                        totalBytes += folderSize
                        totalCount += 1
                    } else {
                        totalCount += 1
                        totalBytes += dev.narayan.rose.ShizukuManager.getFileSize(clean)
                    }
                } else {
                    fun scanSaf(p: String) {
                        if (JobManager.isCancelled(job.id) || JobManager.isCompleted(job.id)) return
                        val children = SafManager.listFiles(context, p)
                        children.forEach { child ->
                            if (child.isDirectory) {
                                scanSaf(child.file.path)
                            } else {
                                totalCount++
                                totalBytes += child.size
                            }
                        }
                    }
                    if (SafManager.isDirectory(context, path)) {
                        scanSaf(path)
                    } else {
                        totalCount++
                        totalBytes += SafManager.getReliableSize(context, path)
                    }
                }
            }
            if (!JobManager.isCancelled(job.id) && !JobManager.isCompleted(job.id) && totalBytes > 0) {
                job.totalBytes = totalBytes
                if (totalCount > 0) job.totalItems = totalCount
                job.isIndeterminate = false
                if (job.totalBytes > 0) {
                    job.progress = (job.processedBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f)
                }
                JobManager.updateJob(job)
            }
        } catch (ignored: Exception) {}
    }.start()
}

internal fun isDirectory(context: Context, path: String): Boolean {
    if ((path == "/" || dev.narayan.rose.RootManager.isRootPath(path)) && dev.narayan.rose.RootManager.isRootAvailable()) {
        return dev.narayan.rose.RootManager.isDirectory(path)
    }
    val restricted = SafManager.isRestrictedPath(path)
    return if (restricted || SafManager.isSafUri(path)) {
        if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
            val cleanSource = dev.narayan.rose.ShizukuManager.normalize(path)
            dev.narayan.rose.ShizukuManager.runCommandSync(
                "[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(cleanSource)} ]"
            ) == 0
        } else {
            SafManager.isDirectory(context, path)
        }
    } else {
        try {
            Files.isDirectory(Paths.get(path))
        } catch (e: Exception) {
            java.io.File(path).isDirectory
        }
    }
}

internal fun isSubdirectoryOrSame(childPath: String, parentPath: String): Boolean {
    return SafManager.isSubdirectoryOrSame(childPath, parentPath)
}

internal fun checkExists(context: Context, path: String): Boolean {
    if ((path == "/" || dev.narayan.rose.RootManager.isRootPath(path)) && dev.narayan.rose.RootManager.isRootAvailable()) {
        return dev.narayan.rose.RootManager.exists(path)
    }
    return if (SafManager.isRestrictedPath(path)) {
        if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
            dev.narayan.rose.ShizukuManager.exists(path)
        } else if (SafManager.hasPermission(context, path) && SafManager.exists(context, path)) {
            true
        } else {
            SafManager.exists(context, path)
        }
    } else {
        try {
            val f = java.io.File(path)
            f.exists() || Files.exists(Paths.get(path))
        } catch (e: Exception) {
            false
        }
    }
}

internal fun getNonConflictingTarget(context: Context, targetDir: Path, displayName: String, sourcePath: String? = null, isCopy: Boolean = false): Path {
    val lastDot = displayName.lastIndexOf('.')
    val (name, ext) = if (lastDot > 0 && !displayName.startsWith(".")) {
        displayName.substring(0, lastDot) to displayName.substring(lastDot)
    } else {
        displayName to ""
    }

    val targetDirStr = SafManager.normalizeSafPath(targetDir.toString())
    val isSaf = SafManager.isSafUri(targetDirStr)

    fun resolveCandidate(candidateName: String): Path {
        return if (isSaf) {
            val cleanBase = targetDirStr.trimEnd('/')
            Paths.get("$cleanBase/$candidateName")
        } else {
            targetDir.resolve(candidateName)
        }
    }

    fun existsCandidate(candidatePath: Path, candidateName: String): Boolean {
        val candidatePathStr = SafManager.normalizeSafPath(candidatePath.toString())
        return if (isSaf) {
            try {
                SafManager.listFiles(context, targetDirStr).any { it.name.equals(candidateName, ignoreCase = true) }
            } catch (e: Exception) {
                checkExists(context, candidatePathStr)
            }
        } else {
            checkExists(context, candidatePathStr)
        }
    }

    var target = resolveCandidate(displayName)

    if (!isCopy && sourcePath != null && (sourcePath == target.toString() || SafManager.isSamePath(sourcePath, target.toString()))) {
        return target
    }

    var count = 1
    val isSameAsSource = isCopy && sourcePath != null && (
        sourcePath == target.toString() || SafManager.isSamePath(sourcePath, target.toString())
    )

    if (isSameAsSource || existsCandidate(target, displayName)) {
        var candidateName = "$name ($count)$ext"
        target = resolveCandidate(candidateName)
        count++
        while (existsCandidate(target, candidateName)) {
            candidateName = "$name ($count)$ext"
            target = resolveCandidate(candidateName)
            count++
        }
    }
    return target
}

internal data class ChildItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long
)

internal fun listChildren(context: Context, path: String): List<ChildItem> {
    if ((path == "/" || dev.narayan.rose.RootManager.isRootPath(path)) && dev.narayan.rose.RootManager.isRootAvailable()) {
        val rootFiles = dev.narayan.rose.RootManager.listFilesSync(path, showHiddenFiles = true)
        return rootFiles.map {
            ChildItem(
                path = it.file.absolutePath,
                name = it.name,
                isDirectory = it.isDirectory,
                size = if (it.isDirectory) 0L else it.size
            )
        }
    }
    val restricted = SafManager.isRestrictedPath(path) || SafManager.isSafUri(path)
    if (!restricted) {
        val f = java.io.File(path)
        val list = f.listFiles() ?: return emptyList()
        return list.map {
            ChildItem(
                path = it.path,
                name = it.name,
                isDirectory = it.isDirectory,
                size = if (it.isDirectory) 0L else it.length()
            )
        }
    }

    if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
        val results = mutableListOf<ChildItem>()
        val cleanSource = dev.narayan.rose.ShizukuManager.normalize(path)
        val searchPrefix = if (cleanSource == "/") "" else cleanSource.trimEnd('/')
        val escapedPrefix = dev.narayan.rose.ShizukuManager.shellEscape(searchPrefix)
        val cmd = "stat -c '%F|%s|%n' $escapedPrefix/* $escapedPrefix/.[!.]* $escapedPrefix/..?* 2>/dev/null"
        try {
            val process = dev.narayan.rose.ShizukuManager.newProcess(arrayOf("sh", "-c", cmd), null, null)
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach
                    val parts = trimmed.split('|', limit = 3)
                    if (parts.size == 3) {
                        val typeStr = parts[0]
                        val sizeStr = parts[1]
                        val fullPath = parts[2]
                        val name = fullPath.substringAfterLast('/')
                        if (name.isNotEmpty() && name != "." && name != "..") {
                            val isDir = typeStr.contains("directory", ignoreCase = true)
                            results.add(
                                ChildItem(
                                    path = fullPath,
                                    name = name,
                                    isDirectory = isDir,
                                    size = if (isDir) 0L else (sizeStr.toLongOrNull() ?: 0L)
                                )
                            )
                        }
                    }
                }
            }
            process.waitFor()
        } catch (e: Exception) {
        }
        if (results.isNotEmpty()) return results

        try {
            val findCmd = "find $escapedPrefix -mindepth 1 -maxdepth 1 2>/dev/null"
            val findProc = dev.narayan.rose.ShizukuManager.newProcess(arrayOf("sh", "-c", findCmd), null, null)
            val paths = mutableListOf<String>()
            findProc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { l ->
                    val t = l.trim()
                    if (t.isNotEmpty()) paths.add(t)
                }
            }
            findProc.waitFor()
            if (paths.isNotEmpty()) {
                return paths.map { p ->
                    val name = p.substringAfterLast('/')
                    val isDir = dev.narayan.rose.ShizukuManager.runCommandSync("[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(p)} ]") == 0
                    val sz = if (isDir) 0L else dev.narayan.rose.ShizukuManager.getFileSize(p)
                    ChildItem(path = p, name = name, isDirectory = isDir, size = sz)
                }
            }
        } catch (e: Exception) {}
    }

    return try {
        SafManager.listFiles(context, path).map {
            ChildItem(
                path = it.file.path,
                name = it.name,
                isDirectory = it.isDirectory,
                size = it.size
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

internal fun calculateBatchStats(context: Context, paths: List<String>, job: FileJob? = null): Pair<Long, Int>? {
    var totalBytes = 0L
    var totalItems = 0
    val scanLimit = 20_000

    fun scan(path: String): Boolean {
        if (job != null) {
            if (JobManager.isCancelled(job.id)) return false
            try {
                JobManager.checkWaitIfPaused(job.id)
            } catch (e: Exception) {
                return false
            }
        }
        totalItems++
        if (totalItems > scanLimit) return false

        val restricted = SafManager.isRestrictedPath(path) || SafManager.isSafUri(path)
        return if (restricted) {
            if (dev.narayan.rose.ShizukuManager.isAvailable() && dev.narayan.rose.ShizukuManager.hasPermission() && !SafManager.isSafUri(path)) {
                val clean = dev.narayan.rose.ShizukuManager.normalize(path)
                val isDir = dev.narayan.rose.ShizukuManager.runCommandSync("[ -d ${dev.narayan.rose.ShizukuManager.shellEscape(clean)} ]") == 0
                if (isDir) {
                    val folderSize = dev.narayan.rose.ShizukuManager.getFolderSize(clean)
                    totalBytes += folderSize
                    try {
                        val countProc = dev.narayan.rose.ShizukuManager.newProcess(
                            arrayOf("sh", "-c", "find ${dev.narayan.rose.ShizukuManager.shellEscape(clean)} 2>/dev/null | wc -l"),
                            null, null
                        )
                        val countStr = countProc.inputStream.bufferedReader().readText().trim()
                        countProc.waitFor()
                        val c = countStr.toIntOrNull() ?: 1
                        totalItems += (c - 1).coerceAtLeast(0)
                    } catch (e: Exception) {}
                } else {
                    totalBytes += dev.narayan.rose.ShizukuManager.getFileSize(path)
                }
                true
            } else if (isDirectory(context, path)) {
                val children = listChildren(context, path)
                children.all { child ->
                    if (child.isDirectory) {
                        scan(child.path)
                    } else {
                        totalItems++
                        totalBytes += child.size
                        true
                    }
                }
            } else {
                val size = SafManager.getReliableSize(context, path)
                totalBytes += size
                true
            }
        } else {
            val file = java.io.File(path)
            if (file.isDirectory) {
                file.listFiles()?.all { scan(it.path) } ?: true
            } else {
                totalBytes += file.length()
                true
            }
        }
    }

    for (path in paths) {
        if (!scan(path)) return null
    }
    return totalBytes to totalItems
}
