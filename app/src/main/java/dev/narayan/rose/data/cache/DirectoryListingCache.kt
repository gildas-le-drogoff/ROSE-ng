package dev.narayan.rose.data.cache

import dev.narayan.rose.domain.model.FileItem

class DirectoryListingCache(
    private val maxDirectories: Int = 48,
    private val maxTotalItems: Int = 60_000
) {
    class Entry(val items: List<FileItem>, val dirMtime: Long, val storedAt: Long) {
        val ageMs: Long get() = System.currentTimeMillis() - storedAt
    }

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f,  true)
    private var totalItems = 0

    @Synchronized
    fun get(path: String): Entry? = entries[path]

    @Synchronized
    fun put(path: String, items: List<FileItem>, dirMtime: Long) {
        if (items.size > maxTotalItems) return
        entries.remove(path)?.let { totalItems -= it.items.size }
        entries[path] = Entry(items, dirMtime, System.currentTimeMillis())
        totalItems += items.size
        val iterator = entries.entries.iterator()
        while ((entries.size > maxDirectories || totalItems > maxTotalItems) && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == path) continue
            totalItems -= eldest.value.items.size
            iterator.remove()
        }
    }

    @Synchronized
    fun invalidate(path: String? = null) {
        if (path == null) {
            entries.clear()
            totalItems = 0
            return
        }
        entries.remove(path)?.let { totalItems -= it.items.size }
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) entries.remove(parent)?.let { totalItems -= it.items.size }
    }

    @Synchronized
    fun removePaths(paths: Set<String>) {
        if (paths.isEmpty()) return
        for (key in entries.keys.toList()) {
            val entry = entries[key] ?: continue
            if (entry.items.none { it.file.absolutePath in paths }) continue
            val kept = entry.items.filter { it.file.absolutePath !in paths }
            totalItems -= entry.items.size - kept.size
            entries[key] = Entry(kept, entry.dirMtime, entry.storedAt)
        }
    }
}
