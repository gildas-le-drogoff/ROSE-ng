package dev.narayan.rose.data.index

import android.content.Context
import android.os.Environment
import dev.narayan.rose.data.FileStat
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.matchesCategory
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object FileNameIndex {

    private class Dir(
        val mtime: Long,
        val names: Array<String>,
        val isDir: BooleanArray,
        val sizes: LongArray,
        val mtimes: LongArray
    )

    private const val TAG = "FileIndex"
    private const val FILE_NAME = "search_index_v1.bin"
    private const val MAGIC = 0x524F5345
    private const val VERSION = 1
    private const val MAX_DIRS = 50_000
    private const val FRESH_MS = 30_000L

    private val junkDirNames = setOf(".thumbnails", ".trashed", ".Trash", "LOST.DIR", "\$RECYCLE.BIN", "cache", ".cache")

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val refreshLock = Any()

    @Volatile private var storeFile: File? = null
    @Volatile private var root: String = ""
    @Volatile private var dirs: Map<String, Dir> = emptyMap()
    @Volatile private var loadAttempted = false
    @Volatile private var validatedAt = 0L
    @Volatile private var stale = true
    private var refreshJob: Deferred<Boolean>? = null

    fun init(context: Context) {
        storeFile = File(context.applicationContext.cacheDir, FILE_NAME)
        root = Environment.getExternalStorageDirectory().absolutePath
    }

    fun markStale() {
        stale = true
    }

    fun covers(scopePath: String): Boolean {
        val r = root
        if (r.isEmpty()) return false
        val start = scopePath.trimEnd('/').ifEmpty { r }
        if (start != r && !start.startsWith("$r/")) return false
        return start.removePrefix(r).split('/').none { it.isNotEmpty() && isSkippedName(it) } &&
                !start.startsWith("$r/Android/data") && !start.startsWith("$r/Android/obb")
    }

    suspend fun ensureLoaded() {
        if (loadAttempted) return
        loadMutex.withLock {
            if (loadAttempted) return
            loadAttempted = true
            val file = storeFile ?: return
            if (!file.exists()) return
            val loaded = roseNGLog.timed(TAG, "load index", { m -> "${m?.size ?: 0} dirs" }) {
                runCatching { readFrom(file) }.onFailure {
                    roseNGLog.w(TAG, it) { "Corrupted index, rebuilding" }
                    file.delete()
                }.getOrNull()
            }
            if (loaded != null && dirs.isEmpty()) dirs = loaded
        }
    }

    suspend fun ensureFresh(): Boolean {
        ensureLoaded()
        if (!stale && dirs.isNotEmpty() && System.currentTimeMillis() - validatedAt < FRESH_MS) return false
        return refreshAsync().await()
    }

    private fun refreshAsync(): Deferred<Boolean> = synchronized(refreshLock) {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        stale = false
        ioScope.async {
            val r = root
            val before = dirs
            val changed = roseNGLog.timed(TAG, "revalidate index", { c -> "${dirs.size} dirs, changed=$c" }) {
                val (fresh, changed) = revalidate(r, before)
                dirs = fresh
                changed
            }
            validatedAt = System.currentTimeMillis()
            if (changed) persist()
            changed
        }.also { refreshJob = it }
    }

    private fun isSkippedName(name: String): Boolean = name.startsWith(".") || name in junkDirNames

    private fun shouldSkipChild(parentPath: String, name: String): Boolean {
        if (isSkippedName(name)) return true
        return parentPath == "$root/Android" && (name == "data" || name == "obb")
    }

    private suspend fun revalidate(rootPath: String, old: Map<String, Dir>): Pair<Map<String, Dir>, Boolean> {
        val result = ConcurrentHashMap<String, Dir>(old.size.coerceAtLeast(256))
        val changed = AtomicBoolean(false)
        val visited = AtomicInteger(0)

        fun CoroutineScope.visit(path: String) {
            launch(FileStat.ioDispatcher) {
                if (visited.incrementAndGet() > MAX_DIRS) return@launch
                val dirFile = File(path)
                val mtime = dirFile.lastModified()
                if (mtime == 0L) {
                    changed.set(true)
                    return@launch
                }
                val previous = old[path]
                val node = if (previous != null && previous.mtime == mtime) {
                    previous
                } else {
                    changed.set(true)
                    scanDir(dirFile, mtime)
                }
                result[path] = node
                for (i in node.names.indices) {
                    if (node.isDir[i] && !shouldSkipChild(path, node.names[i])) visit("$path/${node.names[i]}")
                }
            }
        }

        coroutineScope { visit(rootPath) }
        if (result.size != old.size) changed.set(true)
        return HashMap(result) to changed.get()
    }

    private suspend fun scanDir(dir: File, mtime: Long): Dir {
        val children = try { dir.listFiles() } catch (e: Exception) { null }
        if (children.isNullOrEmpty()) return Dir(mtime, emptyArray(), BooleanArray(0), LongArray(0), LongArray(0))
        val items = FileStat.readAll(children.asList())
        return Dir(
            mtime = mtime,
            names = Array(items.size) { items[it].name },
            isDir = BooleanArray(items.size) { items[it].isDirectory },
            sizes = LongArray(items.size) { items[it].size },
            mtimes = LongArray(items.size) { items[it].lastModified }
        )
    }

    fun search(
        query: String,
        scopePath: String,
        filterType: FileType?,
        showHiddenFiles: Boolean,
        limit: Int
    ): List<FileItem>? {
        val snapshot = dirs
        if (snapshot.isEmpty() || !covers(scopePath)) return null
        val start = scopePath.trimEnd('/').ifEmpty { root }
        if (snapshot[start] == null) return null

        val results = ArrayList<FileItem>()
        val stack = ArrayDeque<String>()
        stack.addLast(start)
        while (stack.isNotEmpty() && results.size < limit) {
            val path = stack.removeLast()
            val node = snapshot[path] ?: continue
            val names = node.names
            for (i in names.indices) {
                val name = names[i]
                val childIsDir = node.isDir[i]
                if (childIsDir) {
                    val childPath = "$path/$name"
                    if (snapshot.containsKey(childPath)) stack.addLast(childPath)
                }
                if (!showHiddenFiles && name.startsWith(".")) continue
                if (!name.contains(query, ignoreCase = true)) continue
                if (childIsDir && filterType != null) continue
                val item = FileItem(
                    file = File(path, name),
                    isDirectory = childIsDir,
                    name = name,
                    size = node.sizes[i],
                    lastModified = node.mtimes[i],
                    extension = if (childIsDir) "" else name.substringAfterLast('.', "").lowercase()
                )
                if (filterType == null || item.matchesCategory(filterType)) {
                    results.add(item)
                    if (results.size >= limit) break
                }
            }
        }
        return results
    }

    private fun persist() {
        val file = storeFile ?: return
        val snapshot = dirs
        val tmp = File(file.parentFile, "$FILE_NAME.part")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeUTF(root)
                out.writeInt(snapshot.size)
                for ((path, node) in snapshot) {
                    out.writeUTF(path)
                    out.writeLong(node.mtime)
                    out.writeInt(node.names.size)
                    for (i in node.names.indices) {
                        out.writeUTF(node.names[i])
                        out.writeBoolean(node.isDir[i])
                        out.writeLong(node.sizes[i])
                        out.writeLong(node.mtimes[i])
                    }
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            roseNGLog.w(TAG, e) { "Failed to persist index" }
            tmp.delete()
        }
    }

    private fun readFrom(file: File): Map<String, Dir>? {
        DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            if (input.readUTF() != root) return null
            val count = input.readInt()
            if (count < 0 || count > MAX_DIRS) return null
            val map = HashMap<String, Dir>(count * 2)
            repeat(count) {
                val path = input.readUTF()
                val mtime = input.readLong()
                val n = input.readInt()
                val names = arrayOfNulls<String>(n)
                val isDir = BooleanArray(n)
                val sizes = LongArray(n)
                val mtimes = LongArray(n)
                for (i in 0 until n) {
                    names[i] = input.readUTF()
                    isDir[i] = input.readBoolean()
                    sizes[i] = input.readLong()
                    mtimes[i] = input.readLong()
                }
                @Suppress("UNCHECKED_CAST")
                map[path] = Dir(mtime, names as Array<String>, isDir, sizes, mtimes)
            }
            return map
        }
    }

    fun clear() {
        synchronized(refreshLock) {
            refreshJob?.cancel()
            refreshJob = null
        }
        dirs = emptyMap()
        stale = true
        validatedAt = 0L
        storeFile?.delete()
    }
}
