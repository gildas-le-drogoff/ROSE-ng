package dev.narayan.rose

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import org.json.JSONObject
import java.io.File
import java.util.*

data class RecycledItem(
    val id: String,
    val originalName: String,
    val originalPath: String,
    val deletionTime: Long,
    val size: Long,
    val isDirectory: Boolean,
    val binPath: String? = null
)

object RecycleBinManager {
    private const val RECYCLE_BIN_FOLDER = ".rose_recycle_bin"
    private const val METADATA_FILE = "metadata.json"
    private const val RETENTION_PERIOD_MS = 30L * 24 * 60 * 60 * 1000

    private val lock = Any()

    private fun shellEscape(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    private fun existsInternal(file: File): Boolean {
        if (file.exists()) return true
        if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
            val clean = ShizukuManager.normalize(file.absolutePath)
            return ShizukuManager.runCommandSync("test -e ${shellEscape(clean)}") == 0
        }
        return false
    }

    private fun deleteInternal(file: File): Boolean {
        return if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
            val clean = ShizukuManager.normalize(file.absolutePath)
            ShizukuManager.runCommandSync("rm -rf ${shellEscape(clean)}") == 0
        } else {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }

    private fun mkdirsInternal(dir: File): Boolean {
        if (existsInternal(dir)) return dir.isDirectory || (ShizukuManager.isAvailable() && ShizukuManager.hasPermission())
        return if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
            val clean = ShizukuManager.normalize(dir.absolutePath)
            ShizukuManager.runCommandSync("mkdir -p ${shellEscape(clean)}") == 0
        } else {
            dir.mkdirs()
        }
    }

    private fun readTextInternal(context: Context, file: File): String? {
        try {
            if (file.exists()) return file.readText()
        } catch (e: Exception) {}

        if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
            return try {
                val clean = ShizukuManager.normalize(file.absolutePath)
                val process = ShizukuManager.newProcess(arrayOf("sh", "-c", "cat ${shellEscape(clean)}"), null, null)
                val text = process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitFor() == 0) text else null
            } catch (e: Exception) { null }
        }
        return null
    }

    private fun writeTextInternal(context: Context, file: File, text: String): Boolean {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tempFile = File(parent, "${file.name}.tmp-${UUID.randomUUID()}")
            tempFile.writeText(text)
            if (tempFile.renameTo(file)) return true
            return try {
                file.writeText(text)
                true
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val tempFile = File(context.cacheDir, "rose_meta_tmp_${UUID.randomUUID()}.json")
                return try {
                    tempFile.writeText(text)
                    val cleanTemp = ShizukuManager.normalize(tempFile.absolutePath)
                    val cleanDest = ShizukuManager.normalize(file.absolutePath)
                    val success = ShizukuManager.runCommandSync(
                        "mv ${shellEscape(cleanTemp)} ${shellEscape(cleanDest)}"
                    ) == 0
                    if (!success) tempFile.delete()
                    success
                } catch (e2: Exception) {
                    tempFile.delete()
                    false
                } finally {
                    if (tempFile.exists()) {
                        runCatching { tempFile.delete() }
                    }
                }
            }
        }
        return false
    }

    private fun getRecycleBinDirs(context: Context): List<File> {
        val dirs = mutableListOf<File>()

        val primary = Environment.getExternalStorageDirectory()
        dirs.add(File(primary, RECYCLE_BIN_FOLDER))

        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { volume ->
                if (!volume.isPrimary && volume.state == Environment.MEDIA_MOUNTED) {
                    val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory
                    } else {
                        try {
                            val getPath = volume.javaClass.getMethod("getPath")
                            File(getPath.invoke(volume) as String)
                        } catch (e: Exception) { null }
                    }
                    if (root != null) {
                        dirs.add(File(root, RECYCLE_BIN_FOLDER))
                    }
                }
            }
        } catch (e: Exception) {}

        context.getExternalFilesDirs(null).forEach { baseDir ->
            if (baseDir != null) {
                val binDir = File(baseDir, RECYCLE_BIN_FOLDER)
                if (!dirs.any { it.absolutePath == binDir.absolutePath }) {
                    dirs.add(binDir)
                }
            }
        }

        dirs.forEach { mkdirsInternal(it) }
        return dirs
    }

    private fun getRecycleBinDirForFile(context: Context, file: File): File {
        val absolutePath = file.absolutePath
        val bins = getRecycleBinDirs(context)

        return bins.find { bin ->
            val binRoot = bin.parentFile?.absolutePath ?: "???"
            absolutePath.startsWith(binRoot)
        } ?: bins.first()
    }

    private fun getMetadataFile(binDir: File): File {
        return File(binDir, METADATA_FILE)
    }

    private fun loadMetadata(context: Context, binDir: File): MutableMap<String, RecycledItem> {
        val text = readTextInternal(context, getMetadataFile(binDir)) ?: return mutableMapOf()

        return try {
            val json = JSONObject(text)
            val map = mutableMapOf<String, RecycledItem>()
            json.keys().forEach { id ->
                val obj = json.getJSONObject(id)
                map[id] = RecycledItem(
                    id = id,
                    originalName = obj.getString("name"),
                    originalPath = obj.getString("path"),
                    deletionTime = obj.getLong("time"),
                    size = obj.optLong("size", 0L),
                    isDirectory = obj.optBoolean("isDir", false),
                    binPath = binDir.absolutePath
                )
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveMetadata(context: Context, binDir: File, metadata: Map<String, RecycledItem>): Boolean {
        val json = JSONObject()
        metadata.forEach { (id, item) ->
            val obj = JSONObject().apply {
                put("name", item.originalName)
                put("path", item.originalPath)
                put("time", item.deletionTime)
                put("size", item.size)
                put("isDir", item.isDirectory)
            }
            json.put(id, obj)
        }
        return writeTextInternal(context, getMetadataFile(binDir), json.toString())
    }

    fun getRecycledFile(context: Context, item: RecycledItem): File {
        val binDir = if (item.binPath != null) File(item.binPath) else getRecycleBinDirs(context).first()
        return File(binDir, item.id)
    }

    fun recycle(context: Context, file: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        val path = file.absolutePath
        val isRestricted = SafManager.isRestrictedPath(path)
        val exists = if (isRestricted) {
            if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val clean = ShizukuManager.normalize(path)
                ShizukuManager.runCommandSync("[ -e ${shellEscape(clean)} ]") == 0
            } else {
                SafManager.exists(context, path)
            }
        } else {
            file.exists()
        }
        if (!exists) return false

        val isDir = if (isRestricted) {
            if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val clean = ShizukuManager.normalize(path)
                ShizukuManager.runCommandSync("[ -d ${shellEscape(clean)} ]") == 0
            } else {
                SafManager.isDirectory(context, path)
            }
        } else {
            file.isDirectory
        }

        val extension = file.extension
        val uuid = UUID.randomUUID().toString()
        val fileName = if (extension.isNotEmpty() && !isDir) "$uuid.$extension" else uuid
        val recycleBinDir = getRecycleBinDirForFile(context, file)
        val destination = File(recycleBinDir, fileName)

        return try {
            val size = if (isDir) 0L else {
                if (isRestricted) {
                    SafManager.getReliableSize(context, path).coerceAtLeast(0L)
                } else {
                    file.length()
                }
            }
            val item = RecycledItem(
                id = fileName,
                originalName = file.name,
                originalPath = file.absolutePath,
                deletionTime = System.currentTimeMillis(),
                size = size,
                isDirectory = isDir,
                binPath = recycleBinDir.absolutePath
            )

            val moved = if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val cleanSrc = ShizukuManager.normalize(file.absolutePath)
                val cleanDest = ShizukuManager.normalize(destination.absolutePath)
                ShizukuManager.runCommandSync("mv ${shellEscape(cleanSrc)} ${shellEscape(cleanDest)}") == 0
            } else {
                false
            }

            if (moved || file.renameTo(destination)) {
                var success = false
                synchronized(lock) {
                    val metadata = loadMetadata(context, recycleBinDir)
                    metadata[fileName] = item
                    success = saveMetadata(context, recycleBinDir, metadata)
                }
                success
            } else {
                var totalSize = 0L
                fun fastScan(f: File) {
                    if (f.isFile) totalSize += f.length()
                    else f.listFiles()?.forEach { fastScan(it) }
                }
                fastScan(file)

                var bytesCopied = 0L
                var success = if (file.isDirectory) {
                    copyRecursivelyWithProgress(file, destination) { copied ->
                        bytesCopied += copied
                        onProgress?.invoke(bytesCopied, totalSize)
                    } && deleteInternal(file)
                } else {
                    try {
                        val result = copyWithProgress(file, destination, onProgress)
                        if (result) deleteInternal(file) else false
                    } catch (e: Exception) {
                        false
                    }
                }

                if (success) {
                    synchronized(lock) {
                        val metadata = loadMetadata(context, recycleBinDir)
                        metadata[fileName] = item
                        if (!saveMetadata(context, recycleBinDir, metadata)) {
                            success = false
                        }
                    }
                }
                success
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun copyWithProgress(source: File, target: File, onProgress: ((Long, Long) -> Unit)?): Boolean {
        return try {
            val totalBytes = source.length()
            var bytesCopied = 0L
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192 * 4)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                        onProgress?.invoke(bytesCopied, totalBytes)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun listItems(context: Context): List<RecycledItem> {
        cleanupExpired(context)
        val allItems = mutableListOf<RecycledItem>()
        getRecycleBinDirs(context).forEach { binDir ->
            allItems.addAll(loadMetadata(context, binDir).values)
        }
        return allItems.sortedByDescending { it.deletionTime }
    }

    private fun getNonConflictingTarget(file: File): File {
        if (!existsInternal(file)) return file
        val parent = file.parentFile ?: return file
        val name = file.name
        val dotIndex = name.lastIndexOf('.')
        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = File(parent, "$base ($counter)$ext")
            if (!existsInternal(candidate)) {
                return candidate
            }
            counter++
        }
    }

    fun restore(context: Context, item: RecycledItem, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        val binDir = if (item.binPath != null) File(item.binPath) else {
            getRecycleBinDirs(context).find { existsInternal(File(it, item.id)) }
        } ?: return false

        val recycledFile = File(binDir, item.id)
        if (!existsInternal(recycledFile)) {
            synchronized(lock) {
                val metadata = loadMetadata(context, binDir)
                metadata.remove(item.id)
                saveMetadata(context, binDir, metadata)
            }
            return false
        }

        val originalFile = File(item.originalPath)
        val targetFile = getNonConflictingTarget(originalFile)
        targetFile.parentFile?.let { mkdirsInternal(it) }

        return try {
            val moved = if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val cleanSrc = ShizukuManager.normalize(recycledFile.absolutePath)
                val cleanDest = ShizukuManager.normalize(targetFile.absolutePath)
                ShizukuManager.runCommandSync("mv ${shellEscape(cleanSrc)} ${shellEscape(cleanDest)}") == 0
            } else {
                false
            }

            if (moved || recycledFile.renameTo(targetFile)) {
                var success = false
                synchronized(lock) {
                    val metadata = loadMetadata(context, binDir)
                    metadata.remove(item.id)
                    success = saveMetadata(context, binDir, metadata)
                }
                success
            } else {
                var totalSize = 0L

                fun fastScan(f: File) {
                    if (f.isFile) totalSize += f.length()
                    else f.listFiles()?.forEach { fastScan(it) }
                }
                fastScan(recycledFile)

                var bytesCopied = 0L
                var success = if (recycledFile.isDirectory) {
                    copyRecursivelyWithProgress(recycledFile, targetFile) { copied ->
                        bytesCopied += copied
                        onProgress?.invoke(bytesCopied, totalSize)
                    } && deleteInternal(recycledFile)
                } else {
                    val result = copyWithProgress(recycledFile, targetFile, onProgress)
                    if (result) deleteInternal(recycledFile) else false
                }

                if (success) {
                    synchronized(lock) {
                        val metadata = loadMetadata(context, binDir)
                        metadata.remove(item.id)
                        if (!saveMetadata(context, binDir, metadata)) {
                            success = false
                        }
                    }
                }
                success
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun copyRecursivelyWithProgress(source: File, target: File, onProgress: (Long) -> Unit): Boolean {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) return false
            source.listFiles()?.forEach { child ->
                if (!copyRecursivelyWithProgress(child, File(target, child.name), onProgress)) return false
            }
            return true
        } else {
            return try {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192 * 4)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } >= 0) {
                            output.write(buffer, 0, bytesRead)
                            onProgress(bytesRead.toLong())
                        }
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun deletePermanently(context: Context, item: RecycledItem): Boolean {
        val binDir = if (item.binPath != null) File(item.binPath) else {
            getRecycleBinDirs(context).find { existsInternal(File(it, item.id)) }
        } ?: return false

        val recycledFile = File(binDir, item.id)
        val deleted = deleteInternal(recycledFile)

        if (deleted || !existsInternal(recycledFile)) {
            synchronized(lock) {
                val metadata = loadMetadata(context, binDir)
                metadata.remove(item.id)
                saveMetadata(context, binDir, metadata)
            }
            return true
        }
        return false
    }

    fun emptyBin(context: Context) {
        getRecycleBinDirs(context).forEach { binDir ->
            synchronized(lock) {
                deleteInternal(binDir)
                mkdirsInternal(binDir)
                saveMetadata(context, binDir, emptyMap())
            }
        }
    }

    private fun cleanupExpired(context: Context) {
        val now = System.currentTimeMillis()
        getRecycleBinDirs(context).forEach { binDir ->
            synchronized(lock) {
                val metadata = loadMetadata(context, binDir)
                val toRemove = mutableListOf<String>()

                metadata.forEach { (id, item) ->
                    if (now - item.deletionTime > RETENTION_PERIOD_MS) {
                        val file = File(binDir, id)
                        if (existsInternal(file)) {
                            deleteInternal(file)
                        }
                        toRemove.add(id)
                    }
                }

                if (toRemove.isNotEmpty()) {
                    toRemove.forEach { metadata.remove(it) }
                    saveMetadata(context, binDir, metadata)
                }
            }
        }
    }
}