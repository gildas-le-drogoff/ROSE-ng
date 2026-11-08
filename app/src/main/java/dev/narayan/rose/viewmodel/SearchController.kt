package dev.narayan.rose.viewmodel

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.narayan.rose.SearchFilter
import dev.narayan.rose.ShizukuManager
import dev.narayan.rose.computeSearchFilterCounts
import dev.narayan.rose.data.FileStat
import dev.narayan.rose.data.index.FileNameIndex
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.SearchRequest
import dev.narayan.rose.domain.model.matchesCategory
import dev.narayan.rose.domain.repository.FileRepository
import dev.narayan.rose.domain.repository.MediaStoreRepository
import dev.narayan.rose.filterSearchResults
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SearchController(
    private val scope: CoroutineScope,
    private val fileRepository: FileRepository,
    private val mediaStoreRepository: MediaStoreRepository,
    private val showHiddenFiles: () -> Boolean
) {
    val searchResults = mutableStateListOf<FileItem>()

    var isRecursiveSearching by mutableStateOf(false)
        private set

    var activeSearchFilter by mutableStateOf(SearchFilter.ALL)

    val filteredSearchResults: List<FileItem>
        get() = filterSearchResults(searchResults, activeSearchFilter)

    val searchFilterCounts: Map<SearchFilter, Int>
        get() = computeSearchFilterCounts(searchResults)

    private var searchJob: Job? = null

    fun search(
        query: String,
        filterType: FileType?,
        bucketId: String?,
        scopePath: String?,
        inMemorySource: List<FileItem>
    ) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults.clear()
            isRecursiveSearching = false
            activeSearchFilter = SearchFilter.ALL
            return
        }

        isRecursiveSearching = true

        if (filterType != null && inMemorySource.isNotEmpty()) {
            val instantMatches = inMemorySource.filter {
                it.name.contains(query, ignoreCase = true) &&
                (bucketId == null || it.bucketId == bucketId) &&
                it.matchesCategory(filterType)
            }.sortedWith(
                compareByDescending<FileItem> { it.isDirectory }
                    .thenByDescending { it.name.equals(query, ignoreCase = true) }
                    .thenByDescending { it.name.startsWith(query, ignoreCase = true) }
                    .thenBy { it.name.lowercase() }
            )
            searchResults.clear()
            searchResults.addAll(instantMatches)
        } else {
            searchResults.clear()
        }

        searchJob = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(180)
            ensureActive()

            val rawScope = scopePath?.let { ShizukuManager.normalize(it) } ?: ""
            val cleanScope = if (rawScope == Environment.getExternalStorageDirectory().absolutePath) "" else rawScope
            val isDataScope = cleanScope.contains("/Android/data", ignoreCase = true) || cleanScope.endsWith("/Android/data")
            val isObbScope = cleanScope.contains("/Android/obb", ignoreCase = true) || cleanScope.endsWith("/Android/obb")
            val isRestrictedScope = isDataScope || isObbScope
            val request = SearchRequest(
                query = query,
                scopePath = cleanScope,
                filterType = filterType,
                bucketId = bucketId,
                showHiddenFiles = showHiddenFiles(),
                maxResults = SEARCH_MAX_RESULTS
            )

            val showHidden = showHiddenFiles()
            val useIndex = !isRestrictedScope && bucketId == null && FileNameIndex.covers(cleanScope)

            val results = roseNGLog.timed(TAG, "searchFiles \"$query\" in ${cleanScope.ifEmpty { "/" }}", { r -> "${r.size} results" }) {
                if (isRestrictedScope) {
                    fileRepository.searchRestricted(request)
                } else if (useIndex) {
                    FileNameIndex.ensureLoaded()
                    FileNameIndex.search(query, cleanScope, filterType, showHidden, INDEX_MAX_MATCHES)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { publish(query, it, final = false) }

                    val indexed = mediaStoreRepository.searchByName(request)
                    if (indexed.isNotEmpty()) publish(query, merge(searchResultsSnapshot(), indexed), final = false)

                    val fresh = try {
                        FileNameIndex.ensureFresh()
                        FileNameIndex.search(query, cleanScope, filterType, showHidden, INDEX_MAX_MATCHES).orEmpty()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        roseNGLog.w(TAG, e) { "Index revalidation failed" }
                        FileNameIndex.search(query, cleanScope, filterType, showHidden, INDEX_MAX_MATCHES).orEmpty()
                    }
                    val ranked = rank(query, merge(indexed, fresh)).take(SEARCH_MAX_RESULTS)
                    ensureActive()
                    FileStat.readAll(ranked.map { it.file }).let { restat ->
                        val byPath = restat.associateBy { it.file.absolutePath }
                        ranked.mapNotNull { old ->
                            val now = byPath[old.file.absolutePath] ?: return@mapNotNull null
                            old.copy(size = now.size, lastModified = now.lastModified, isDirectory = now.isDirectory)
                        }
                    }
                } else if (bucketId != null) {
                    mediaStoreRepository.searchByName(request)
                } else {
                    val indexed = mediaStoreRepository.searchByName(request)
                    if (indexed.size >= SEARCH_MAX_RESULTS) {
                        indexed
                    } else {
                        val alreadyFound = indexed.mapTo(mutableSetOf()) { it.file.absolutePath }
                        val scanRoot = if (cleanScope.isNotEmpty()) File(cleanScope) else Environment.getExternalStorageDirectory()
                        val scanned = try {
                            fileRepository.scanByName(query, scanRoot, filterType, SEARCH_MAX_RESULTS - indexed.size)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            roseNGLog.w(TAG, e) { "Filesystem search fallback failed" }
                            emptyList()
                        }
                        indexed + scanned.filter { it.file.absolutePath !in alreadyFound }
                    }
                }
            }

            publish(query, results, final = true)
        }
    }

    private suspend fun searchResultsSnapshot(): List<FileItem> = withContext(Dispatchers.Main) { searchResults.toList() }

    private fun merge(a: List<FileItem>, b: List<FileItem>): List<FileItem> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val seen = HashSet<String>(a.size + b.size)
        val out = ArrayList<FileItem>(a.size + b.size)
        for (item in a) if (seen.add(item.file.absolutePath)) out.add(item)
        for (item in b) if (seen.add(item.file.absolutePath)) out.add(item)
        return out
    }

    private fun rank(query: String, items: List<FileItem>): List<FileItem> =
        items.distinctBy { it.file.absolutePath }.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }
                .thenByDescending { it.name.equals(query, ignoreCase = true) }
                .thenByDescending { it.name.startsWith(query, ignoreCase = true) }
                .thenComparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
        )

    private suspend fun publish(query: String, items: List<FileItem>, final: Boolean) {
        val ranked = rank(query, items).take(SEARCH_MAX_RESULTS)
        withContext(Dispatchers.Main) {
            searchResults.clear()
            searchResults.addAll(ranked)
            if (final) isRecursiveSearching = false
        }
    }

    fun removePaths(paths: Set<String>) {
        searchResults.removeAll { it.file.absolutePath in paths }
    }

    fun replaceItem(oldPath: String, updated: FileItem) {
        val index = searchResults.indexOfFirst { it.file.absolutePath == oldPath }
        if (index != -1) searchResults[index] = updated
    }

    private companion object {
        const val TAG = "Search"
        const val SEARCH_MAX_RESULTS = 300
        const val INDEX_MAX_MATCHES = 2000
    }
}
