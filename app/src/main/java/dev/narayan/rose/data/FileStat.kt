package dev.narayan.rose.data

import android.os.Build
import dev.narayan.rose.domain.model.FileItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

object FileStat {

    @OptIn(ExperimentalCoroutinesApi::class)
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(8)

    private const val CHUNK_SIZE = 64

    fun read(file: File, itemCount: ((File) -> Int?)? = null): FileItem? {
        var isDirectory: Boolean
        var size: Long
        var lastModified: Long
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                isDirectory = attrs.isDirectory
                size = if (attrs.isRegularFile) attrs.size() else 0L
                lastModified = attrs.lastModifiedTime().toMillis()
            } catch (e: Exception) {
                val noFollow = runCatching {
                    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }.getOrNull() ?: return null
                isDirectory = false
                size = 0L
                lastModified = noFollow.lastModifiedTime().toMillis()
            }
        } else {
            lastModified = file.lastModified()
            if (lastModified == 0L && !file.exists()) return null
            isDirectory = file.isDirectory
            size = if (isDirectory) 0L else file.length()
        }
        return FileItem(
            file = file,
            isDirectory = isDirectory,
            name = file.name,
            size = size,
            lastModified = lastModified,
            extension = file.extension.lowercase(),
            itemCount = if (isDirectory && itemCount != null) itemCount(file) else null
        )
    }

    suspend fun readAll(files: List<File>, itemCount: ((File) -> Int?)? = null): List<FileItem> =
        withContext(ioDispatcher) {
            if (files.size <= CHUNK_SIZE) {
                return@withContext files.mapNotNull { read(it, itemCount) }
            }
            coroutineScope {
                files.chunked(CHUNK_SIZE).map { chunk ->
                    async {
                        chunk.mapNotNull { file ->
                            ensureActive()
                            read(file, itemCount)
                        }
                    }
                }.awaitAll().flatten()
            }
        }
}
