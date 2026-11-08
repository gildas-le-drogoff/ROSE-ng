package dev.narayan.rose.data.repository

import android.content.Context
import dev.narayan.rose.RootManager
import dev.narayan.rose.SafManager
import dev.narayan.rose.ShizukuManager
import dev.narayan.rose.data.FileStat
import dev.narayan.rose.data.scan.FilesystemScanner
import dev.narayan.rose.domain.model.DirectoryListing
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.SearchRequest
import dev.narayan.rose.domain.model.matchesCategory
import dev.narayan.rose.domain.repository.FileRepository
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class DefaultFileRepository(context: Context) : FileRepository {

    private val appContext = context.applicationContext

    override suspend fun listDirectory(path: String, showHiddenFiles: Boolean, useRoot: Boolean): DirectoryListing =
        withContext(Dispatchers.IO) {
            val isSaf = SafManager.isSafUri(path)
            when {
                isSaf -> DirectoryListing.Success(listSaf(path, showHiddenFiles))
                isRemotePath(path) -> DirectoryListing.Success(emptyList())
                isRestrictedPath(path) -> listRestricted(path, showHiddenFiles)
                else -> {
                    listLocalDirectory(path, showHiddenFiles)?.let { return@withContext DirectoryListing.Success(it) }
                    if (useRoot && RootManager.isRootAvailable()) {
                        roseNGLog.d(TAG) { "java.io refused $path, falling back to root" }
                        DirectoryListing.Success(RootManager.listFiles(path, showHiddenFiles))
                    } else {
                        roseNGLog.w(TAG) { "Access denied for $path (no root)" }
                        DirectoryListing.AccessDenied
                    }
                }
            }
        }

    override suspend fun listLocalDirectory(path: String, showHiddenFiles: Boolean): List<FileItem>? =
        withContext(Dispatchers.IO) {
            val rawFiles = try { File(path).listFiles() } catch (e: Exception) { null } ?: return@withContext null
            val filtered = if (showHiddenFiles) rawFiles.toList() else rawFiles.filter { !it.name.startsWith(".") }
            FileStat.readAll(filtered) { dir -> countDirectoryChildren(dir, showHiddenFiles) }
        }

    private fun countDirectoryChildren(dir: File, showHiddenFiles: Boolean): Int? {
        val names = try { dir.list() } catch (e: Exception) { null }
        if (names != null) return if (showHiddenFiles) names.size else names.count { !it.startsWith(".") }
        return countChildren(dir, showHiddenFiles)
    }

    private fun listSaf(path: String, showHiddenFiles: Boolean): List<FileItem> =
        SafManager.listFiles(appContext, path).let { list ->
            if (showHiddenFiles) list else list.filter { !it.name.startsWith(".") }
        }

    private suspend fun listRestricted(path: String, showHiddenFiles: Boolean): DirectoryListing {
        if (!ShizukuManager.isAvailable()) {
            ShizukuManager.requestBinder(appContext)
            repeat(6) {
                if (ShizukuManager.isAvailable()) return@repeat
                delay(60)
            }
        }

        return if (ShizukuManager.isAvailable()) {
            if (ShizukuManager.hasPermission()) {
                val shizukuResults = ShizukuManager.listFiles(path, showHiddenFiles)
                if (shizukuResults.isEmpty() && SafManager.hasPermission(appContext, path)) {
                    val safResults = listSaf(path, showHiddenFiles)
                    DirectoryListing.Success(if (safResults.isNotEmpty()) safResults else shizukuResults)
                } else {
                    DirectoryListing.Success(shizukuResults)
                }
            } else {
                DirectoryListing.AccessDenied
            }
        } else if (SafManager.hasPermission(appContext, path)) {
            DirectoryListing.Success(listSaf(path, showHiddenFiles))
        } else {
            DirectoryListing.AccessDenied
        }
    }

    override fun countChildren(file: File, showHiddenFiles: Boolean): Int? {
        if (!file.isDirectory) return null
        val names = try { file.list() } catch (e: Exception) { null }
        if (names != null) {
            return if (showHiddenFiles) names.size else names.count { !it.startsWith(".") }
        }

        val path = file.absolutePath
        if (SafManager.isRestrictedPath(path)) {
            if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val escapedPath = ShizukuManager.shellEscape(ShizukuManager.normalize(path))
                val cmd = if (showHiddenFiles) "ls -1A $escapedPath | wc -l" else "ls -1 $escapedPath | wc -l"
                return try {
                    val process = ShizukuManager.newProcess(arrayOf("sh", "-c", cmd), null, null)
                    val output = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    output.toIntOrNull() ?: 0
                } catch (e: Exception) {
                    roseNGLog.w(TAG, e) { "Shizuku item count failed for $path" }
                    0
                }
            } else if (SafManager.hasPermission(appContext, path)) {
                return try {
                    val safResults = SafManager.listFiles(appContext, path)
                    if (showHiddenFiles) safResults.size else safResults.count { !it.name.startsWith(".") }
                } catch (e: Exception) { 0 }
            }
        }
        return 0
    }

    @Suppress("NewApi")
    override fun folderSize(directory: File, useRoot: Boolean): Long {
        val path = directory.absolutePath
        if (isRestrictedPath(path)) {
            if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val size = ShizukuManager.getFolderSize(path)
                if (size > 0L) return size
            }
            return 0L
        }
        if (useRoot && RootManager.isRootAvailable() && RootManager.isRootPath(path)) {
            val size = RootManager.getFolderSize(path)
            if (size > 0L) return size
        }
        if (SafManager.isSafUri(path)) {
            fun scanSaf(p: String): Long {
                var sum = 0L
                for (item in SafManager.listFiles(appContext, p)) {
                    sum += if (item.isDirectory) scanSaf(item.file.absolutePath) else item.size
                }
                return sum
            }
            return try { scanSaf(path) } catch (_: Exception) { 0L }
        }
        var totalSize = 0L
        try {
            Files.walkFileTree(Paths.get(path), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) totalSize += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
            })
            return totalSize
        } catch (_: Exception) {}

        fun scanDir(f: File) {
            val children = f.listFiles() ?: return
            for (child in children) {
                if (child.isFile) totalSize += child.length() else if (child.isDirectory) scanDir(child)
            }
        }
        scanDir(directory)
        return totalSize
    }

    override fun isRestrictedPath(path: String): Boolean {
        if (SafManager.isSafUri(path)) return false
        val lowPath = try { File(path).canonicalPath.lowercase() } catch (e: Exception) { path.lowercase() }
        return lowPath.endsWith("/android/data") || lowPath.contains("/android/data/") ||
                lowPath.endsWith("/android/obb") || lowPath.contains("/android/obb/")
    }

    override fun isRemotePath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase().trim()
        return REMOTE_SCHEMES.any { scheme -> lower.startsWith("$scheme://") || lower.startsWith("$scheme:/") }
    }

    override suspend fun searchRestricted(request: SearchRequest): List<FileItem> = withContext(Dispatchers.IO) {
        if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
            ShizukuManager.searchFiles(
                searchRoot = request.scopePath,
                query = request.query,
                filterType = request.filterType,
                showHiddenFiles = request.showHiddenFiles,
                maxResults = request.maxResults
            )
        } else if (SafManager.hasPermission(appContext, request.scopePath)) {
            SafManager.searchFiles(
                context = appContext,
                rootPath = request.scopePath,
                query = request.query,
                filterType = request.filterType,
                showHiddenFiles = request.showHiddenFiles,
                maxResults = request.maxResults
            )
        } else {
            emptyList()
        }
    }

    override suspend fun scanByName(query: String, root: File, filterType: FileType?, maxResults: Int): List<FileItem> =
        withContext(Dispatchers.IO) {
            FilesystemScanner.scanForQuery(query, root, filterType = filterType, maxResults = maxResults).mapNotNull { file ->
                ensureActive()
                val path = file.absolutePath
                if (path.contains("/Android/data", ignoreCase = true) || path.contains("/Android/obb", ignoreCase = true)) return@mapNotNull null
                FileItem(file).takeIf { filterType == null || it.matchesCategory(filterType) }
            }
        }

    private companion object {
        const val TAG = "FileRepository"
        val REMOTE_SCHEMES = listOf("mega", "onedrive", "gdrive", "dropbox", "smb", "ftp", "ftps", "sftp", "webdav", "webdavs")
    }
}
