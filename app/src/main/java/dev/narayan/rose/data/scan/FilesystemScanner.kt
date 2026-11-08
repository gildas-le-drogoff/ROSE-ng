package dev.narayan.rose.data.scan

import android.os.Environment
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.matchesCategory
import java.io.File

object FilesystemScanner {

    private val junkDirNames = setOf(".thumbnails", ".trashed", ".Trash", "LOST.DIR", "\$RECYCLE.BIN", "cache", ".cache")
    private const val MAX_DIRS = 20000

    private fun shouldSkipDir(dir: File): Boolean {
        val name = dir.name
        if (name.startsWith(".")) return true
        if (name in junkDirNames) return true
        val parentName = dir.parentFile?.name
        return parentName == "Android" && (name == "data" || name == "obb")
    }

    fun scanForExtensions(
        extensions: Set<String>,
        root: File = Environment.getExternalStorageDirectory(),
        maxResults: Int = 500
    ): List<File> {
        val results = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        var dirsVisited = 0
        while (stack.isNotEmpty() && results.size < maxResults && dirsVisited < MAX_DIRS) {
            val dir = stack.removeLast()
            dirsVisited++
            val children = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (child in children) {
                if (results.size >= maxResults) break
                if (child.isDirectory) {
                    if (!shouldSkipDir(child)) stack.addLast(child)
                } else {
                    val ext = child.name.substringAfterLast('.', "").lowercase()
                    if (ext in extensions) results.add(child)
                }
            }
        }
        return results
    }

    fun scanForQuery(query: String, root: File, filterType: FileType? = null, maxResults: Int = 100): List<File> {
        val results = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        val lowerQuery = query.lowercase()
        var dirsVisited = 0
        while (stack.isNotEmpty() && results.size < maxResults && dirsVisited < MAX_DIRS) {
            val dir = stack.removeLast()
            dirsVisited++
            val children = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (child in children) {
                if (results.size >= maxResults) break
                if (child.isDirectory) {
                    if (filterType == null && child.name.lowercase().contains(lowerQuery)) {
                        results.add(child)
                    }
                    if (!shouldSkipDir(child)) stack.addLast(child)
                } else if (child.name.lowercase().contains(lowerQuery)) {
                    if (filterType == null || FileItem(child).matchesCategory(filterType)) {
                        results.add(child)
                    }
                }
            }
        }
        return results
    }
}
