package dev.narayan.rose.viewmodel

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.repository.SettingsRepository
import dev.narayan.rose.roseNGLog
import java.io.File

class FavouritesController(private val settings: SettingsRepository) {

    var quickAccessRemoved by mutableStateOf(settings.quickAccessRemoved)
        private set

    var quickAccessCustomPaths by mutableStateOf(settings.quickAccessCustomPaths)
        private set

    var favouriteFiles by mutableStateOf(settings.favouriteFiles)
        private set

    private var normalizedFavourites: Set<String> = favouriteFiles.mapTo(HashSet()) { normalizePath(it) }

    private var rawFavourites: Set<String> = favouriteFiles.mapTo(HashSet()) { File(it).absolutePath.trimEnd(File.separatorChar) }

    private fun updateFavourites(updated: List<String>) {
        favouriteFiles = updated
        normalizedFavourites = updated.mapTo(HashSet()) { normalizePath(it) }
        rawFavourites = updated.mapTo(HashSet()) { File(it).absolutePath.trimEnd(File.separatorChar) }
        settings.favouriteFiles = updated
    }

    private fun updateCustomPaths(updated: List<String>) {
        quickAccessCustomPaths = updated
        settings.quickAccessCustomPaths = updated
    }

    private fun updateRemoved(updated: Set<String>) {
        quickAccessRemoved = updated
        settings.quickAccessRemoved = updated
    }

    fun normalizePath(path: String): String = try {
        File(path).canonicalPath
    } catch (_: Exception) {
        File(path).absolutePath.trimEnd(File.separatorChar)
    }

    fun getDefaultQuickAccessFolders(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads.exists()) list.add("downloads" to downloads.absolutePath)
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val camera = File(dcim, "Camera")
        val targetCamera = if (camera.exists()) camera else dcim
        if (targetCamera.exists()) list.add("camera" to targetCamera.absolutePath)
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (documents.exists()) list.add("documents" to documents.absolutePath)
        return list
    }

    fun getDefaultFolderIdForPath(path: String): String? {
        val normalized = normalizePath(path)
        return getDefaultQuickAccessFolders().firstOrNull { (_, defaultPath) -> normalizePath(defaultPath) == normalized }?.first
    }

    fun isFolderInQuickAccess(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = normalizePath(path)
        if (getDefaultQuickAccessFolders().any { (id, defaultPath) -> !quickAccessRemoved.contains(id) && normalizePath(defaultPath) == normalized }) {
            return true
        }
        return quickAccessCustomPaths.any { normalizePath(it) == normalized }
    }

    fun removeDefaultQuickAccess(id: String) {
        updateRemoved(quickAccessRemoved + id)
    }

    fun addQuickAccessFolder(path: String) {
        if (isFolderInQuickAccess(path)) return

        val defaultId = getDefaultFolderIdForPath(path)
        if (defaultId != null && quickAccessRemoved.contains(defaultId)) {
            updateRemoved(quickAccessRemoved - defaultId)
            return
        }

        val normalized = normalizePath(path)
        if (quickAccessCustomPaths.any { normalizePath(it) == normalized }) return
        updateCustomPaths(quickAccessCustomPaths + path)
    }

    fun removeQuickAccessFolder(path: String) {
        val normalized = normalizePath(path)
        getDefaultFolderIdForPath(path)?.let { removeDefaultQuickAccess(it) }
        updateCustomPaths(quickAccessCustomPaths.filter { normalizePath(it) != normalized })
    }

    fun isFileInFavourites(path: String): Boolean {
        if (path.isBlank()) return false
        val trimmed = path.trimEnd(File.separatorChar)
        return trimmed in normalizedFavourites || trimmed in rawFavourites
    }

    private fun isFavouriteCanonical(path: String): Boolean =
        isFileInFavourites(path) || normalizePath(path) in normalizedFavourites

    fun addFavouriteFile(path: String) {
        if (path.isBlank() || isFavouriteCanonical(path)) return
        updateFavourites(favouriteFiles + path)
    }

    fun removeFavouriteFile(path: String) {
        val normalized = normalizePath(path)
        updateFavourites(favouriteFiles.filter { normalizePath(it) != normalized })
    }

    fun toggleFavouriteFile(path: String) {
        if (isFavouriteCanonical(path)) removeFavouriteFile(path) else addFavouriteFile(path)
    }

    fun addFavourites(fileItems: List<FileItem>) {
        val newPaths = fileItems
            .filter { !it.isDirectory && !isFileInFavourites(it.file.absolutePath) }
            .map { it.file.absolutePath }
        if (newPaths.isNotEmpty()) updateFavourites(favouriteFiles + newPaths)
    }

    fun removeFavourites(fileItems: List<FileItem>) {
        val toRemove = fileItems.map { normalizePath(it.file.absolutePath) }.toSet()
        updateFavourites(favouriteFiles.filter { normalizePath(it) !in toRemove })
    }

    private var favouriteItemsSource: List<String>? = null
    private var favouriteItemsCache: List<FileItem> = emptyList()
    private var favouriteItemsAt = 0L

    fun getFavouriteFileItems(): List<FileItem> {
        val source = favouriteFiles
        val now = System.currentTimeMillis()
        if (source === favouriteItemsSource && now - favouriteItemsAt < FAVOURITE_ITEMS_TTL_MS) return favouriteItemsCache
        favouriteItemsCache = source.mapNotNull { path -> dev.narayan.rose.data.FileStat.read(File(path)) }
        favouriteItemsSource = source
        favouriteItemsAt = now
        return favouriteItemsCache
    }

    fun onFileRenamed(oldPath: String, newPath: String) {
        val oldNorm = normalizePath(oldPath)
        if (oldNorm in normalizedFavourites) {
            updateFavourites(favouriteFiles.map { if (normalizePath(it) == oldNorm) newPath else it })
        }
    }

    fun sanitize() {
        val activeDefaultNormalized = getDefaultQuickAccessFolders()
            .filter { !quickAccessRemoved.contains(it.first) }
            .map { normalizePath(it.second) }
            .toSet()
        val seen = mutableSetOf<String>()
        val sanitizedCustom = quickAccessCustomPaths.filter { path ->
            val norm = normalizePath(path)
            !activeDefaultNormalized.contains(norm) && seen.add(norm)
        }
        if (sanitizedCustom.size != quickAccessCustomPaths.size) {
            roseNGLog.d(TAG) { "Quick access sanitized: ${quickAccessCustomPaths.size} → ${sanitizedCustom.size}" }
            updateCustomPaths(sanitizedCustom)
        }

        val seenFavFiles = mutableSetOf<String>()
        val sanitizedFavFiles = favouriteFiles.filter { seenFavFiles.add(normalizePath(it)) }
        if (sanitizedFavFiles.size != favouriteFiles.size) {
            roseNGLog.d(TAG) { "Favourites sanitized: ${favouriteFiles.size} → ${sanitizedFavFiles.size}" }
            updateFavourites(sanitizedFavFiles)
        }
    }

    private companion object {
        const val FAVOURITE_ITEMS_TTL_MS = 3_000L
        const val TAG = "Favourites"
    }
}
