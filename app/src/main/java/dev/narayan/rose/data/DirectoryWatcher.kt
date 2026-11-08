package dev.narayan.rose.data

import android.os.Build
import android.os.FileObserver
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DirectoryWatcher(
    private val scope: CoroutineScope,
    private val onChange: (path: String, changedNames: Set<String>?) -> Unit
) {
    private data class Signature(val lastModified: Long, val length: Long, val isDir: Boolean?, val lastChangeAt: Long)

    private var observer: FileObserver? = null
    private var pollJob: Job? = null
    private var debounceJob: Job? = null

    private val tracked = ConcurrentHashMap<String, Signature>()
    private val pendingNames = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pendingFull = false
    @Volatile private var dirLastModified = 0L

    @Volatile
    var watchedPath: String? = null
        private set

    fun start(path: String) {
        if (watchedPath == path && pollJob != null) return
        stop()

        val dir = File(path)
        if (!dir.isDirectory) return
        watchedPath = path
        dirLastModified = dir.lastModified()
        roseNGLog.d(TAG) { "Watching $path" }

        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
                FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
                FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

        val newObserver = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(dir, mask) {
                    override fun onEvent(event: Int, relativePath: String?) = onFsEvent(path, event, relativePath)
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, relativePath: String?) = onFsEvent(path, event, relativePath)
                }
            }
        } catch (e: Exception) {
            roseNGLog.w(TAG, e) { "FileObserver unavailable for $path, polling only" }
            null
        }
        newObserver?.startWatching()
        observer = newObserver

        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (watchedPath != path) break
                poll(path)
            }
        }
    }

    fun track(path: String, items: List<FileItem>, dirMtimeBeforeListing: Long) {
        if (watchedPath != path) return
        val now = System.currentTimeMillis()
        if (dirMtimeBeforeListing != 0L) dirLastModified = dirMtimeBeforeListing
        tracked.clear()
        var dirs = 0
        var recent = 0
        for (item in items) {
            if (item.isDirectory) {
                if (dirs >= MAX_TRACKED_DIRS) continue
                dirs++
            } else {
                if (now - item.lastModified > RECENT_WINDOW_MS || recent >= MAX_TRACKED_FILES) continue
                recent++
            }
            tracked[item.name] = Signature(item.lastModified, if (item.isDirectory) 0L else item.size, item.isDirectory, now)
        }
    }

    private fun onFsEvent(path: String, event: Int, relativePath: String?) {
        if (watchedPath != path) return
        val type = event and FileObserver.ALL_EVENTS
        when {
            relativePath == null || type and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0 -> pendingFull = true
            type and (FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO) != 0 -> {
                pendingFull = true
                if (type and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0) {
                    tracked[relativePath] = Signature(-1L, -1L, null, System.currentTimeMillis())
                }
            }
            else -> {
                pendingNames.add(relativePath)
                tracked.putIfAbsent(relativePath, Signature(-1L, -1L, null, System.currentTimeMillis()))
            }
        }
        scheduleFlush(path)
    }

    private fun scheduleFlush(path: String) {
        if (debounceJob?.isActive == true) return
        debounceJob = scope.launch(Dispatchers.Default) {
            delay(EVENT_DEBOUNCE_MS)
            flush(path)
        }
    }

    private suspend fun flush(path: String) {
        if (watchedPath != path) return
        val full = pendingFull
        pendingFull = false
        val names = if (full) emptySet() else pendingNames.toSet()
        pendingNames.clear()
        if (!full && names.isEmpty()) return
        withContext(Dispatchers.Main) {
            if (watchedPath == path) onChange(path, if (full) null else names)
        }
    }

    private suspend fun poll(path: String) {
        val now = System.currentTimeMillis()
        val dirMtime = File(path).lastModified()
        if (dirMtime == 0L) {
            pendingFull = true
        } else if (dirMtime != dirLastModified) {
            dirLastModified = dirMtime
            pendingFull = true
        }

        if (!pendingFull) {
            val iterator = tracked.entries.iterator()
            while (iterator.hasNext()) {
                val (name, sig) = iterator.next()
                val file = File(path, name)
                val mtime = file.lastModified()
                if (mtime == 0L) {
                    iterator.remove()
                    pendingFull = true
                    break
                }
                val isDir = sig.isDir ?: file.isDirectory
                val length = if (isDir) 0L else file.length()
                if (mtime != sig.lastModified || length != sig.length) {
                    if (sig.lastModified != -1L) pendingNames.add(name)
                    tracked[name] = Signature(mtime, length, isDir, now)
                } else if (!isDir && now - sig.lastChangeAt > IDLE_DROP_MS) {
                    iterator.remove()
                }
            }
        }
        flush(path)
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
        debounceJob?.cancel()
        debounceJob = null
        pollJob?.cancel()
        pollJob = null
        tracked.clear()
        pendingNames.clear()
        pendingFull = false
        if (watchedPath != null) roseNGLog.d(TAG) { "Stopped watching $watchedPath" }
        watchedPath = null
    }

    private companion object {
        const val TAG = "DirWatcher"
        const val EVENT_DEBOUNCE_MS = 400L
        const val POLL_INTERVAL_MS = 1000L
        const val RECENT_WINDOW_MS = 2 * 60_000L
        const val IDLE_DROP_MS = 15_000L
        const val MAX_TRACKED_DIRS = 64
        const val MAX_TRACKED_FILES = 64
    }
}
