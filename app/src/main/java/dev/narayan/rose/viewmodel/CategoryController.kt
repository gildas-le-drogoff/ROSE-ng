package dev.narayan.rose.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.repository.MediaStoreRepository
import dev.narayan.rose.domain.repository.SettingsRepository
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface CategoryHost {
    val excludedFolders: Set<String>

    val suppressRefreshUntil: Long

    fun sort(list: List<FileItem>): List<FileItem>

    fun onEnterCategory(forceRefresh: Boolean)

    fun onExitCategory()

    fun setRefreshing(refreshing: Boolean)

    fun onCategoryError(message: String)
}

class CategoryController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val mediaStoreRepository: MediaStoreRepository,
    private val host: CategoryHost
) {
    var categoryTitle by mutableStateOf<String?>(null)
        private set

    var categoryFilterType by mutableStateOf<FileType?>(null)
        private set

    var categoryBucketId by mutableStateOf<String?>(null)
        private set

    var isCategoryLoading by mutableStateOf(false)
        private set

    var categoryFiles by mutableStateOf(listOf<FileItem>())
        private set

    var categoryCounts by mutableStateOf(
        settings.cachedCategoryCounts.mapNotNull { (key, count) ->
            runCatching { FileType.valueOf(key) to count }.getOrNull()
        }.toMap()
    )
        private set

    var isCategoryCountsLoading by mutableStateOf(false)
        private set

    private var categoryJob: Job? = null
    private var categoryGeneration = 0
    private var lastCountsLoadTime = 0L

    private data class SourceKey(val mediaGeneration: Long, val excludedFolders: Set<String>)

    private var countsKey: SourceKey? = null
    private val cache = mutableMapOf<Pair<FileType, String?>, List<FileItem>>()
    private val cacheTime = mutableMapOf<Pair<FileType, String?>, Long>()
    private val cacheKeys = mutableMapOf<Pair<FileType, String?>, SourceKey>()

    private fun currentKey(): SourceKey = SourceKey(mediaStoreRepository.generation(), host.excludedFolders)

    val isActive: Boolean get() = categoryFilterType != null

    fun invalidateCategoryCache(type: FileType? = null) {
        if (type != null) {
            cache.keys.filter { it.first == type }.forEach {
                cache.remove(it)
                cacheTime.remove(it)
                cacheKeys.remove(it)
            }
        } else {
            cache.clear()
            cacheTime.clear()
            cacheKeys.clear()
        }
    }

    fun loadCategoryCounts(force: Boolean = false) {
        if (isCategoryCountsLoading) return
        val now = System.currentTimeMillis()
        if (categoryCounts.isNotEmpty() && !force && (now - lastCountsLoadTime < COUNTS_TTL_MS)) return

        isCategoryCountsLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                lastCountsLoadTime = now
                val key = currentKey()
                if (key.mediaGeneration >= 0 && key == countsKey && categoryCounts.isNotEmpty()) return@launch
                val counts = roseNGLog.timed(TAG, "loadCategoryCounts", { c -> c.entries.joinToString { "${it.key}=${it.value}" } }) {
                    mediaStoreRepository.countByCategory(key.excludedFolders)
                }
                settings.cachedCategoryCounts = counts.mapKeys { it.key.name }
                withContext(Dispatchers.Main) {
                    categoryCounts = counts
                    countsKey = key
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { isCategoryCountsLoading = false }
            }
        }
    }

    fun browseCategory(type: FileType, title: String, bucketId: String? = null, forceRefresh: Boolean = false) {
        val sameListing = categoryFilterType == type && categoryTitle == title && categoryBucketId == bucketId
        val now = System.currentTimeMillis()
        val cacheKey = type to bucketId
        val cached = cache[cacheKey]
        val cacheAge = now - (cacheTime[cacheKey] ?: 0L)

        if (!forceRefresh && sameListing && categoryFiles.isNotEmpty() && now < host.suppressRefreshUntil) return

        host.onEnterCategory(forceRefresh)
        categoryTitle = title
        categoryFilterType = type
        categoryBucketId = bucketId

        if (cached != null) {
            categoryFiles = cached
            if (!forceRefresh && cacheAge < CACHE_TTL_MS) {
                isCategoryLoading = false
                host.setRefreshing(false)
                return
            }
        } else {
            categoryFiles = emptyList()
            isCategoryLoading = true
        }

        categoryJob?.cancel()
        val requestGeneration = ++categoryGeneration
        categoryJob = scope.launch(Dispatchers.IO) {
            try {
                val key = currentKey()
                if (!forceRefresh && cached != null && key.mediaGeneration >= 0 && cacheKeys[cacheKey] == key) {
                    withContext(Dispatchers.Main) {
                        if (requestGeneration == categoryGeneration) cacheTime[cacheKey] = System.currentTimeMillis()
                    }
                    return@launch
                }
                val results = roseNGLog.timed(TAG, "browseCategory $type${bucketId?.let { " album=$it" }.orEmpty()}", { r -> "${r.size} items" }) {
                    mediaStoreRepository.listCategory(type, bucketId, host.excludedFolders)
                }
                ensureActive()
                val sortedResults = host.sort(results)
                withContext(Dispatchers.Main) {
                    if (requestGeneration == categoryGeneration) {
                        categoryFiles = sortedResults
                        cache[cacheKey] = sortedResults
                        cacheTime[cacheKey] = System.currentTimeMillis()
                        cacheKeys[cacheKey] = key
                    }
                }
            } catch (e: CancellationException) {
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    if (requestGeneration == categoryGeneration) host.onCategoryError("Failed to load category: ${e.message}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (requestGeneration == categoryGeneration) {
                        isCategoryLoading = false
                        host.setRefreshing(false)
                    }
                }
            }
        }
    }

    fun exitCategoryMode() {
        clear()
        host.onExitCategory()
    }

    fun clear() {
        categoryJob?.cancel()
        isCategoryLoading = false
        categoryTitle = null
        categoryFilterType = null
        categoryBucketId = null
    }

    fun reloadCurrent() {
        val type = categoryFilterType ?: return
        val title = categoryTitle ?: return
        browseCategory(type, title, categoryBucketId)
    }

    fun resort() {
        val type = categoryFilterType ?: return
        categoryFiles = host.sort(categoryFiles)
        cache[type to categoryBucketId] = categoryFiles
    }

    fun replaceItem(oldPath: String, updated: FileItem) {
        categoryFiles = categoryFiles.map { if (it.file.absolutePath == oldPath) updated else it }
        categoryFilterType?.let { cache[it to categoryBucketId] = categoryFiles }
    }

    fun removePaths(paths: Set<String>) {
        categoryFiles = categoryFiles.filter { it.file.absolutePath !in paths }
        invalidateCategoryCache()
    }

    fun onItemsRemoved(removedTypes: List<FileType>, removedBuckets: Set<String>) {
        val updated = categoryCounts.toMutableMap()
        removedTypes.forEach { type ->
            updated[type]?.let { current -> updated[type] = (current - 1).coerceAtLeast(0) }
        }
        categoryCounts = updated

        if (categoryFilterType == FileType.IMAGE && categoryBucketId == null && removedBuckets.isNotEmpty()) {
            categoryFiles = categoryFiles.map { item ->
                if (item.isDirectory && item.bucketId in removedBuckets) item.copy(itemCount = (item.itemCount ?: 0) - 1) else item
            }
        }
    }

    private companion object {
        const val TAG = "Category"
        const val COUNTS_TTL_MS = 30_000L
        const val CACHE_TTL_MS = 30_000L
    }
}
