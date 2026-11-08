package dev.narayan.rose.data

import android.content.Context
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.roseNGLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RecentFilesCache(context: Context) {

    private val filesDir = context.applicationContext.filesDir
    private val cacheFile: File by lazy { File(filesDir, "recent_files_cache.json") }

    fun load(): List<FileItem> = try {
        if (!cacheFile.exists()) {
            emptyList()
        } else {
            val text = cacheFile.readText()
            if (text.isBlank()) emptyList() else parse(JSONArray(text))
        }
    } catch (e: Exception) {
        roseNGLog.w(TAG, e) { "Recent files cache unreadable, ignoring" }
        emptyList()
    }

    private fun parse(array: JSONArray): List<FileItem> {
        val list = mutableListOf<FileItem>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val file = File(obj.getString("path"))
            if (file.exists() && file.isFile) {
                list.add(
                    FileItem(
                        file = file,
                        isDirectory = false,
                        name = obj.optString("name", file.name),
                        size = obj.optLong("size", file.length()),
                        lastModified = obj.optLong("lastModified", file.lastModified()),
                        extension = file.extension.lowercase(),
                        mimeType = if (obj.has("mimeType") && !obj.isNull("mimeType")) obj.getString("mimeType") else null
                    )
                )
            }
        }
        return list
    }

    fun save(items: List<FileItem>) {
        try {
            val array = JSONArray()
            for (item in items.take(MAX_CACHED)) {
                array.put(JSONObject().apply {
                    put("path", item.file.absolutePath)
                    put("name", item.name)
                    put("size", item.size)
                    put("lastModified", item.lastModified)
                    if (item.mimeType != null) put("mimeType", item.mimeType)
                })
            }
            val tempFile = File(filesDir, "recent_files_cache.tmp")
            tempFile.writeText(array.toString())
            tempFile.renameTo(cacheFile)
        } catch (e: Exception) {
            roseNGLog.w(TAG, e) { "Failed to write recent files cache" }
        }
    }

    private companion object {
        const val TAG = "RecentCache"
        const val MAX_CACHED = 20
    }
}
