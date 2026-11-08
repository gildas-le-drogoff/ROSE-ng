package dev.narayan.rose

import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dev.narayan.rose.data.DirectoryWatcher
import dev.narayan.rose.data.RecentFilesCache
import dev.narayan.rose.data.repository.AndroidMediaStoreRepository
import dev.narayan.rose.data.repository.DefaultFileRepository
import dev.narayan.rose.data.rescanForMediaStore
import dev.narayan.rose.data.settings.SettingsManager
import dev.narayan.rose.domain.model.DirectoryListing
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.StartPage
import dev.narayan.rose.domain.model.ThemeMode
import dev.narayan.rose.domain.repository.FileRepository
import dev.narayan.rose.domain.repository.MediaStoreRepository
import dev.narayan.rose.domain.repository.SettingsRepository
import dev.narayan.rose.filejob.JobManager
import dev.narayan.rose.update.UpdateController
import dev.narayan.rose.viewmodel.CategoryController
import dev.narayan.rose.viewmodel.CategoryHost
import dev.narayan.rose.viewmodel.FavouritesController
import dev.narayan.rose.viewmodel.RecycleBinController
import dev.narayan.rose.viewmodel.SearchController
import dev.narayan.rose.viewmodel.StorageController
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.*

private const val TAG = "RoseViewModel"

private val FULL_REFRESH: Set<String> = java.util.Collections.unmodifiableSet(HashSet())

private const val LISTING_CACHE_FRESH_MS = 10_000L

class RoseViewModel(application: Application) : AndroidViewModel(application) {

    private val settings: SettingsRepository = SettingsManager(application)
    private val fileRepository: FileRepository = DefaultFileRepository(application)
    private val mediaStoreRepository: MediaStoreRepository = AndroidMediaStoreRepository(application)

    val updates = UpdateController(application, viewModelScope, settings)

    var currentPath by mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
        private set

    var files by mutableStateOf(listOf<FileItem>())
        private set

    val categories = CategoryController(viewModelScope, settings, mediaStoreRepository, object : CategoryHost {
        override val excludedFolders: Set<String> get() = this@RoseViewModel.excludedFolders
        override val suppressRefreshUntil: Long get() = suppressSilentRefreshUntil
        override fun sort(list: List<FileItem>) = sortFileList(list)
        override fun onEnterCategory(forceRefresh: Boolean) {
            stopWatchingDirectory()
            accessDenied = false
            currentZipFile = null
            currentZipEntryPath = ""
            currentPath = ""
            if (!forceRefresh) isRefreshing = false
        }
        override fun onExitCategory() {
            isRefreshing = false
            currentPath = Environment.getExternalStorageDirectory().absolutePath
        }
        override fun setRefreshing(refreshing: Boolean) {
            isRefreshing = refreshing
        }
        override fun onCategoryError(message: String) {
            errorMessage = message
        }
    })

    private val recentFilesCache = RecentFilesCache(application)

    fun saveCachedRecentFiles(items: List<FileItem>) = recentFilesCache.save(items)

    var recentFiles by mutableStateOf(recentFilesCache.load())
        private set

    var lastSwipeToHomeTimestamp by mutableLongStateOf(0L)
    var lastNavigationTimestamp by mutableLongStateOf(0L)

    var showHiddenFiles by mutableStateOf(settings.showHiddenFiles)
        private set

    var sortBy by mutableStateOf(
        try { SortBy.valueOf(settings.sortBy) } catch (e: IllegalArgumentException) { SortBy.NAME }
    )
        private set

    var sortOrder by mutableStateOf(
        try { SortOrder.valueOf(settings.sortOrder) } catch (e: IllegalArgumentException) { SortOrder.ASCENDING }
    )
        private set

    var selectedFiles = mutableStateListOf<FileItem>()
        private set

    private val selectedSet by derivedStateOf { selectedFiles.toHashSet() }

    fun isSelected(item: FileItem?): Boolean = item != null && item in selectedSet

    var isSelectionMode by mutableStateOf(false)
        private set

    private val _isGridView = mutableStateOf(settings.isGridView)
    val isGridView: Boolean by _isGridView

    private val _isCategoryGridView = mutableStateOf(settings.isCategoryGridView)
    val isCategoryGridView: Boolean by _isCategoryGridView

    private val _foldersFirst = mutableStateOf(settings.foldersFirst)
    val foldersFirst: Boolean by _foldersFirst

    private val _showDetails = mutableStateOf(settings.showDetails)
    val showDetails: Boolean by _showDetails

    private val _confirmBeforeDelete = mutableStateOf(settings.confirmBeforeDelete)
    val confirmBeforeDelete: Boolean by _confirmBeforeDelete

    private val _showFileExtensions = mutableStateOf(settings.showFileExtensions)
    val showFileExtensions: Boolean by _showFileExtensions

    private val _showListDividers = mutableStateOf(settings.showListDividers)
    val showListDividers: Boolean by _showListDividers

    private val _showFolderSize = mutableStateOf(settings.showFolderSize)
    val showFolderSize: Boolean by _showFolderSize

    private val _useRecycleBin = mutableStateOf(settings.useRecycleBin)
    val useRecycleBin: Boolean by _useRecycleBin

    private val _useRoot = mutableStateOf<Boolean>(settings.useRoot)
    val useRoot: Boolean by _useRoot

    fun setUseRoot(enabled: Boolean) {
        _useRoot.value = enabled
        settings.useRoot = enabled
        storage.loadStorageDevices()
    }

    private val _recentFilesLimit = mutableStateOf(settings.recentFilesLimit)
    val recentFilesLimit: Int by _recentFilesLimit

    fun setRecentFilesLimit(limit: Int) {
        _recentFilesLimit.value = limit
        settings.recentFilesLimit = limit
        loadRecentFiles()
    }

    private val _excludedFolders = mutableStateOf(settings.excludedFolders)
    val excludedFolders: Set<String> by _excludedFolders

    fun setExcludedFolders(folders: Set<String>) {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val filteredFolders = folders.filter { it != rootPath }.toMutableSet()

        val finalFolders = mutableSetOf<String>()
        val sorted = filteredFolders.sortedBy { it.length }
        for (path in sorted) {
            if (finalFolders.none { path.startsWith("$it/") }) {
                finalFolders.add(path)
            }
        }

        _excludedFolders.value = finalFolders
        settings.excludedFolders = finalFolders
        loadRecentFiles()
        categories.loadCategoryCounts(force = true)
        categories.reloadCurrent()
    }

    private val _useShizuku = mutableStateOf(settings.useShizuku)
    val useShizuku: Boolean by _useShizuku

    fun setUseShizuku(enabled: Boolean) {
        _useShizuku.value = enabled
        settings.useShizuku = enabled
    }

    private val _notificationPrimerShown = mutableStateOf(settings.notificationPrimerShown)
    val notificationPrimerShown: Boolean by _notificationPrimerShown

    fun markNotificationPrimerShown() {
        _notificationPrimerShown.value = true
        settings.notificationPrimerShown = true
    }

    private val _showRecentFiles = mutableStateOf(settings.showRecentFiles)
    val showRecentFiles: Boolean by _showRecentFiles

    val showQuickAccess: Boolean = true

    private val _showExternalStorage = mutableStateOf(settings.showExternalStorage)
    val showExternalStorage: Boolean by _showExternalStorage

    private var _recentFilesExpanded = mutableStateOf(settings.recentFilesExpanded)
    val recentFilesExpanded: Boolean by _recentFilesExpanded

    private var _quickAccessExpanded = mutableStateOf(settings.quickAccessExpanded)
    val quickAccessExpanded: Boolean by _quickAccessExpanded

    val favourites = FavouritesController(settings)

    private var _externalStorageExpanded = mutableStateOf(settings.externalStorageExpanded)
    val externalStorageExpanded: Boolean by _externalStorageExpanded

    private val _gridItemSize = mutableStateOf(
        try { GridItemSize.valueOf(settings.gridItemSize) } catch (e: IllegalArgumentException) { GridItemSize.MEDIUM }
    )
    val gridItemSize: GridItemSize by _gridItemSize

    private val _themeMode = mutableStateOf(settings.themeMode)
    val themeMode: ThemeMode by _themeMode

    private val _amoledMode = mutableStateOf(settings.amoledMode)
    val amoledMode: Boolean by _amoledMode

    var dynamicColorEnabled by mutableStateOf(settings.dynamicColor)
        private set

    private val _startPage = mutableStateOf(settings.startPage)
    val startPage: StartPage by _startPage

    var clipboardFiles = mutableStateListOf<FileItem>()
        private set

    var clipboardSourceZip by mutableStateOf<File?>(null)
        private set

    var extractionSource by mutableStateOf<File?>(null)
        private set

    var extractionEntries by mutableStateOf<List<String>?>(null)
        private set

    var isCopyOperation by mutableStateOf(true)
        private set

    var passphrasePromptItem by mutableStateOf<FileItem?>(null)
    var passphraseAction: ((String) -> Unit)? = null

    var pendingSafPath by mutableStateOf<String?>(null)
        private set

    var lastDeniedSafPath by mutableStateOf<String?>(null)
        private set

    var pendingShizukuPath by mutableStateOf<String?>(null)
        private set

    var onNotificationPermissionRequest: (() -> Unit)? = null

    fun clearPendingSaf() {
        pendingSafPath = null
    }

    fun clearPendingShizuku() {
        pendingShizukuPath = null
    }

    fun retrySaf(path: String) {
        lastDeniedSafPath = null
        pendingSafPath = path
    }

    fun retryShizuku(path: String) {
        pendingShizukuPath = path
    }

    fun onSafResult(granted: Boolean, path: String) {
        pendingSafPath = null
        if (!granted) {
            lastDeniedSafPath = path
        } else {
            lastDeniedSafPath = null
            loadFiles(path, showLoading = path != currentPath)
        }
    }

    fun isShizukuRestrictedPath(path: String): Boolean = fileRepository.isRestrictedPath(path)

    fun isRemotePath(path: String?): Boolean = fileRepository.isRemotePath(path)

    private val directoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<FileItem>>>()

    private val listingCache = dev.narayan.rose.data.cache.DirectoryListingCache()

    fun invalidateDirectoryCache(path: String? = null) {
        if (path == null) {
            directoryCache.clear()
            listingCache.invalidate()
        } else {
            val norm = ShizukuManager.normalize(path)
            directoryCache.remove(norm)
            File(norm).parent?.let { directoryCache.remove(ShizukuManager.normalize(it)) }
            listingCache.invalidate(norm)
        }
        dev.narayan.rose.data.index.FileNameIndex.markStale()
    }

    fun onShizukuResult(granted: Boolean, path: String) {
        pendingShizukuPath = null
        if (granted) {
            setUseShizuku(true)
            accessDenied = false
            isLoading = true
            files = emptyList()
            invalidateDirectoryCache(path)
            loadFiles(path, showLoading = true)
        } else {
            accessDenied = true
            isLoading = false
        }
    }

    var errorMessage by mutableStateOf<String?>(null)

    fun clearError() { errorMessage = null }

    var propertiesFile by mutableStateOf<FileItem?>(null)
    var highlightedFile by mutableStateOf<FileItem?>(null)
    var highlightedPaths by mutableStateOf<Set<String>>(emptySet())

    fun isPathHighlighted(path: String): Boolean {
        val norm = ShizukuManager.normalize(path)
        if (highlightedPaths.contains(norm)) return true
        val single = highlightedFile
        return single != null && ShizukuManager.normalize(single.file.absolutePath) == norm
    }

    fun highlightPaths(paths: List<String>) {
        highlightedPaths = paths.map { ShizukuManager.normalize(it) }.toSet()
    }

    fun clearHighlight() {
        highlightedFile = null
        highlightedPaths = emptySet()
    }

    var operationFailureReport by mutableStateOf<dev.narayan.rose.filejob.OperationFailureReport?>(null)

    fun clearOperationFailureReport() {
        operationFailureReport = null
    }

    val storage = StorageController(
        app = application,
        scope = viewModelScope,
        settings = settings,
        isRootEnabled = { useRoot },
        currentPath = { currentPath },
        navigateTo = { path ->
            currentPath = path
            loadFiles(path)
        },
        onStorageInfoRequested = { registerMediaObserver() }
    )

    val recycleBin = RecycleBinController(application, viewModelScope)

    var folderSize by mutableStateOf<Long?>(null)
        private set

    fun showProperties(fileItem: FileItem) {
        propertiesFile = fileItem
        folderSize = null
        if (fileItem.isDirectory) {
            viewModelScope.launch(Dispatchers.IO) {
                folderSize = calculateFolderSize(fileItem.file)
            }
        } else if (fileItem.size <= 0L && isShizukuRestrictedPath(fileItem.file.absolutePath)) {
            viewModelScope.launch(Dispatchers.IO) {
                val accurateSize = ShizukuManager.getFileSize(fileItem.file.absolutePath)
                if (accurateSize > 0) {
                    withContext(Dispatchers.Main) {
                        propertiesFile = propertiesFile?.copy(size = accurateSize)
                    }
                }
            }
        }
    }

    fun closeProperties() {
        propertiesFile = null
        folderSize = null
    }

    private fun calculateFolderSize(directory: File): Long {
        val size: Long = roseNGLog.timed(TAG, "folderSize ${directory.absolutePath}", { bytes: Long -> "$bytes bytes" }) {
            fileRepository.folderSize(directory, useRoot)
        }
        return size
    }

    var currentZipFile by mutableStateOf<File?>(null)
    var cachedZipPassword by mutableStateOf<String?>(null)
    var currentZipSourcePath by mutableStateOf<String?>(null)
    var currentZipEntryPath by mutableStateOf("")
    private var zipEntriesCache: List<RoseArchiveEntry> = emptyList()

    var isLoading by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)

    private var suppressSilentRefreshUntil = 0L
    private var loadJob: Job? = null

    private var filesGeneration = 0
    val search = SearchController(viewModelScope, fileRepository, mediaStoreRepository, showHiddenFiles = { showHiddenFiles })

    private val folderSizeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    fun clearFolderSizeCache() {
        folderSizeCache.clear()
    }

    private suspend fun withFolderSizes(list: List<FileItem>): List<FileItem> = coroutineScope {
        list.map { item ->
            async(Dispatchers.IO) {
                if (!item.isDirectory) return@async item
                val mtime = item.file.lastModified()
                val cached = folderSizeCache[item.file.absolutePath]
                val size = if (cached != null && cached.first == mtime) {
                    cached.second
                } else {
                    calculateFolderSize(item.file).also {
                        folderSizeCache[item.file.absolutePath] = mtime to it
                    }
                }
                item.copy(size = size)
            }
        }.awaitAll()
    }

    var hasRunDividerAnimation by mutableStateOf(false)
    var hasRunStorageAnimation by mutableStateOf(false)
    var hasRunEntranceAnimation by mutableStateOf(false)
    var hasRunCategoryCountAnimation by mutableStateOf(false)

    var homeScrollIndex by mutableStateOf(0)
    var homeScrollOffset by mutableStateOf(0)

    var categoryPageIndex by mutableStateOf(0)

    private val pathScrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun saveScrollPosition(path: String, index: Int, offset: Int) {
        if (path.isEmpty() || path == "recent") return
        pathScrollPositions[path] = index to offset
    }

    fun getScrollPosition(path: String): Pair<Int, Int>? {
        if (path == "recent") return null
        return pathScrollPositions[path]
    }

    fun clearScrollPosition(path: String) {
        pathScrollPositions.remove(path)
    }

    private var rootCache: List<FileItem>? = null

    fun preLoadRoot() {
        if (rootCache != null) {
            if (currentPath == Environment.getExternalStorageDirectory().absolutePath && files.isEmpty()) {
                files = rootCache!!
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = Environment.getExternalStorageDirectory()
                val rootFiles = root.listFiles()
                    ?.filter { !it.name.startsWith(".") || showHiddenFiles }
                    ?.map { FileItem(it) }
                    ?.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
                    ?: emptyList()

                rootCache = rootFiles

                withContext(Dispatchers.Main) {
                    if (currentPath == root.absolutePath && files.isEmpty()) {
                        files = rootFiles
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private val directoryWatcher = DirectoryWatcher(viewModelScope) { path, changedNames ->
        if (currentPath == path && currentZipFile == null && categories.categoryFilterType == null) {
            silentRefresh(path, changedNames)
        }
    }

    private fun startWatchingDirectory(path: String, items: List<FileItem>, dirMtime: Long) {
        directoryWatcher.start(path)
        directoryWatcher.track(path, items, dirMtime)
    }

    private fun stopWatchingDirectory() {
        directoryWatcher.stop()
        silentRefreshJob?.cancel()
        silentRefreshJob = null
        pendingSilentRefresh = null
    }

    private var silentRefreshJob: Job? = null
    private var silentRefreshGeneration = 0
    internal fun getItemCount(file: File, showHiddenFiles: Boolean): Int? =
        fileRepository.countChildren(file, showHiddenFiles)

    private var pendingSilentRefresh: Set<String>? = null

    private fun silentRefresh(path: String, changedNames: Set<String>? = null) {
        if (silentRefreshJob?.isActive == true) {
            val pending = pendingSilentRefresh
            pendingSilentRefresh = when {
                changedNames == null || pending === FULL_REFRESH -> FULL_REFRESH
                pending == null -> changedNames
                else -> pending + changedNames
            }
            return
        }
        pendingSilentRefresh = null
        val requestGeneration = ++silentRefreshGeneration
        val baseFiles = files
        silentRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val dirMtime = File(path).lastModified()
                if (dirMtime == 0L) return@launch
                val updated = if (changedNames != null) {
                    patchEntries(path, baseFiles, changedNames)
                } else {
                    val fileList = fileRepository.listLocalDirectory(path, showHiddenFiles) ?: return@launch
                    val sized = if (sortBy == SortBy.SIZE || showFolderSize) withFolderSizes(fileList) else fileList
                    sortFileList(sized)
                }

                withContext(Dispatchers.Main) {
                    val suppressedFor = suppressSilentRefreshUntil - System.currentTimeMillis()
                    if (suppressedFor > 0 && requestGeneration == silentRefreshGeneration && currentPath == path) {
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(suppressedFor + 50)
                            if (currentPath == path) silentRefresh(path, changedNames)
                        }
                    } else if (requestGeneration == silentRefreshGeneration && currentPath == path) {
                        if (updated != files) {
                            files = updated
                            listingCache.put(path, updated, dirMtime)
                            if (path == Environment.getExternalStorageDirectory().absolutePath) rootCache = updated
                        }
                        if (changedNames == null) directoryWatcher.track(path, updated, dirMtime)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    val pending = pendingSilentRefresh
                    if (pending != null && silentRefreshJob?.isCancelled != true && currentPath == path) {
                        pendingSilentRefresh = null
                        silentRefreshJob = null
                        silentRefresh(path, if (pending === FULL_REFRESH) null else pending)
                    }
                }
            }
        }
    }

    private suspend fun patchEntries(path: String, base: List<FileItem>, names: Set<String>): List<FileItem> {
        val byName = HashMap<String, FileItem>(names.size * 2)
        for (name in names) {
            if (!showHiddenFiles && name.startsWith(".")) continue
            val fresh = dev.narayan.rose.data.FileStat.read(File(path, name)) { dir -> getItemCount(dir, showHiddenFiles) }
            if (fresh == null || base.none { it.name == name }) {
                val fileList = fileRepository.listLocalDirectory(path, showHiddenFiles) ?: return base
                val sized = if (sortBy == SortBy.SIZE || showFolderSize) withFolderSizes(fileList) else fileList
                return sortFileList(sized)
            }
            byName[name] = fresh
        }
        if (byName.isEmpty()) return base
        val patched = base.map { old ->
            val fresh = byName[old.name] ?: return@map old
            if (fresh.isDirectory) fresh.copy(size = old.size) else fresh
        }
        return if (sortBy == SortBy.NAME || sortBy == SortBy.TYPE) patched else sortFileList(patched)
    }

    enum class SortBy { NAME, DATE, SIZE, TYPE }
    enum class SortOrder { ASCENDING, DESCENDING }

    private var mediaObserver: android.database.ContentObserver? = null
    private var debouncedMediaRefreshJob: Job? = null

    fun registerMediaObserver() {
        if (mediaObserver != null) return
        try {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    debouncedMediaRefreshJob?.cancel()
                    debouncedMediaRefreshJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(700)
                        loadRecentFiles()
                        categories.loadCategoryCounts(force = true)
                        storage.loadStorageInfo()
                    }
                }
            }
            val resolver = getApplication<Application>().contentResolver
            resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
            mediaObserver = observer
        } catch (e: Throwable) {
        }
    }

    private fun unregisterMediaObserver() {
        try {
            mediaObserver?.let {
                getApplication<Application>().contentResolver.unregisterContentObserver(it)
                mediaObserver = null
            }
        } catch (e: Throwable) {}
    }

    init {
        registerMediaObserver()

        if (settings.excludedFolders.isEmpty()) {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val defaults = mutableSetOf(
                "$root/Android/media/com.whatsapp/WhatsApp/Backups",
                "$root/Android/media/com.whatsapp/WhatsApp/Databases",
                "$root/Music/Recordings/Call Recordings"
            )
            _excludedFolders.value = defaults
            settings.excludedFolders = defaults
        }

        favourites.sanitize()

        recycleBin.loadRecycleBin()

        viewModelScope.launch {
            JobManager.jobEvents.collect { result ->
                val job = result.job

                if (result.failedItems.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val opType = when (job.type) {
                            is dev.narayan.rose.filejob.FileJobType.Copy -> "Copy"
                            is dev.narayan.rose.filejob.FileJobType.Move -> "Move"
                            is dev.narayan.rose.filejob.FileJobType.Delete -> "Delete"
                            else -> "Operation"
                        }
                        operationFailureReport = dev.narayan.rose.filejob.OperationFailureReport(
                            operationType = opType,
                            failedItems = result.failedItems
                        )
                    }
                } else if (!result.success) {
                    withContext(Dispatchers.Main) {
                        val err = result.error ?: ""
                        if (err.contains("Incorrect password", ignoreCase = true) ||
                            err.contains("wrong password", ignoreCase = true) ||
                            err.contains("cen header", ignoreCase = true) ||
                            err.contains("damaged compressed block", ignoreCase = true) ||
                            err.contains("failed to decrypt", ignoreCase = true)
                        ) {
                            errorMessage = getApplication<Application>().getString(R.string.wrong_password)
                        } else if (err.contains("Password required", ignoreCase = true)) {
                            errorMessage = getApplication<Application>().getString(R.string.password_required)
                        } else {
                            errorMessage = "Operation failed: ${result.error ?: "Unknown error"}"
                        }
                        if (job.type is dev.narayan.rose.filejob.FileJobType.Restore) {
                            recycleBin.onBinJobFinished()
                        }
                    }
                }

                val affectedPaths = job.completedPaths
                if (affectedPaths.isNotEmpty()) {
                    suppressSilentRefreshUntil = System.currentTimeMillis() + 1200

                    val isRemoval = job.type is dev.narayan.rose.filejob.FileJobType.Delete ||
                            job.type is dev.narayan.rose.filejob.FileJobType.Recycle ||
                            job.type is dev.narayan.rose.filejob.FileJobType.Move
                    if (isRemoval) {
                        val removedItems = (files + recentFiles + categories.categoryFiles + search.searchResults)
                            .filter { it.file.absolutePath in affectedPaths && !it.isDirectory }
                            .distinctBy { it.file.absolutePath }

                        val removedTypes = removedItems.map { it.fileType }
                        val removedBuckets = removedItems.mapNotNull { it.bucketId }.toSet()

                        if (removedTypes.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                categories.onItemsRemoved(removedTypes, removedBuckets)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (isRemoval) {
                            files = files.filter { it.file.absolutePath !in affectedPaths }
                            recentFiles = recentFiles.filter { it.file.absolutePath !in affectedPaths }
                            saveCachedRecentFiles(recentFiles)
                            categories.removePaths(affectedPaths)
                            invalidateDirectoryCache(currentPath)
                            affectedPaths.forEach { p ->
                                File(p).parent?.let { invalidateDirectoryCache(it) }
                            }
                            search.removePaths(affectedPaths)
                        }

                        if (job.type is dev.narayan.rose.filejob.FileJobType.Recycle ||
                            job.type is dev.narayan.rose.filejob.FileJobType.Restore) {
                            recycleBin.onBinJobFinished()
                        }
                    }
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1500)
                        categories.loadCategoryCounts(force = true)
                        loadRecentFiles()
                        storage.loadStorageInfo()
                    }
                }

                val targetPath = when (val type = job.type) {
                    is dev.narayan.rose.filejob.FileJobType.Copy -> type.targetDir.toString()
                    is dev.narayan.rose.filejob.FileJobType.Move -> type.targetDir.toString()
                    is dev.narayan.rose.filejob.FileJobType.Extract -> type.targetDir.toString()
                    is dev.narayan.rose.filejob.FileJobType.Compress -> type.targetFile.parent?.toString()
                    else -> null
                }

                if (targetPath != null) {
                    invalidateDirectoryCache(targetPath)
                }

                val isCurrentFolderAffected = (targetPath != null && ShizukuManager.normalize(targetPath) == ShizukuManager.normalize(currentPath)) ||
                        affectedPaths.any { path ->
                            val normParent = ShizukuManager.normalize(File(path).parent ?: "")
                            val normCurrent = ShizukuManager.normalize(currentPath)
                            normParent == normCurrent
                        }

                if (isCurrentFolderAffected && currentPath.isNotEmpty()) {
                    invalidateDirectoryCache(currentPath)
                    loadFiles(currentPath, isManualRefresh = true, showLoading = false)
                }

                if (targetPath != null && ShizukuManager.normalize(targetPath) == ShizukuManager.normalize(currentPath)) {
                    if (result.createdPaths.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            highlightPaths(result.createdPaths)
                        }
                    }
                }

                val downloadType = job.type as? dev.narayan.rose.filejob.FileJobType.Download ?: return@collect
                val sourcePath = downloadType.source.path
                val targetFile = File(downloadType.targetFile.toString())

                if (result.success) {
                    val normalizedSource = SafManager.normalizeSafPath(sourcePath)
                    val updated = offlineFiles + sourcePath + normalizedSource
                    withContext(Dispatchers.Main) {
                        offlineFiles = updated
                        settings.offlineFiles = updated
                        files = files.toList()
                    }
                } else {
                    if (targetFile.exists()) targetFile.delete()
                    val normalizedSource = SafManager.normalizeSafPath(sourcePath)
                    val updated = offlineFiles.filterNot {
                        it == sourcePath || it == normalizedSource || SafManager.normalizeSafPath(it) == normalizedSource
                    }.toSet()
                    withContext(Dispatchers.Main) {
                        offlineFiles = updated
                        settings.offlineFiles = updated
                        files = files.toList()
                        val isCancelled = result.error?.contains("cancel", ignoreCase = true) == true
                        if (result.error != null && !isCancelled) {
                            errorMessage = "Couldn't save ${downloadType.source.displayName} offline: ${result.error}"
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            var lastProcessedMap = mutableMapOf<String, Int>()
            JobManager.activeJobs.collect { jobs ->
                var shouldRefresh = false
                jobs.forEach { (id, job) ->
                    val target = when (val type = job.type) {
                        is dev.narayan.rose.filejob.FileJobType.Copy -> type.targetDir.toString()
                        is dev.narayan.rose.filejob.FileJobType.Move -> type.targetDir.toString()
                        else -> null
                    }

                    if (target == currentPath && currentPath.isNotEmpty()) {
                        val prevProcessed = lastProcessedMap[id] ?: 0
                        if (job.processedItems > prevProcessed) {
                            shouldRefresh = true
                        }
                    }
                    lastProcessedMap[id] = job.processedItems
                }

                lastProcessedMap.keys.retainAll(jobs.keys)

                if (shouldRefresh) {
                    invalidateDirectoryCache(currentPath)
                    if (directoryWatcher.watchedPath == currentPath) {
                        silentRefresh(currentPath)
                    } else {
                        loadFiles(currentPath, isManualRefresh = true, showLoading = false)
                    }
                }
            }
        }

        updates.start()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        settings.themeMode = mode
    }

    fun setAmoledMode(enabled: Boolean) {
        _amoledMode.value = enabled
        settings.amoledMode = enabled
    }

    fun setDynamicColor(enabled: Boolean) {
        dynamicColorEnabled = enabled
        settings.dynamicColor = enabled
    }

    fun setGridView(enabled: Boolean) {
        _isGridView.value = enabled
        settings.isGridView = enabled
    }

    fun setCategoryGridView(enabled: Boolean) {
        _isCategoryGridView.value = enabled
        settings.isCategoryGridView = enabled
    }

    fun setFoldersFirst(enabled: Boolean) {
        _foldersFirst.value = enabled
        settings.foldersFirst = enabled
        pathScrollPositions.clear()
        invalidateDirectoryCache()
        if (categories.isActive) {
            categories.resort()
        } else if (currentZipFile != null) {
            files = sortFileList(files)
        } else if (currentPath.isNotEmpty()) {
            files = sortFileList(files)
            if (sortBy == SortBy.SIZE) {
                loadFiles(currentPath, showLoading = false, isManualRefresh = true)
            }
        }
    }

    fun setShowDetails(enabled: Boolean) {
        _showDetails.value = enabled
        settings.showDetails = enabled
    }

    fun setConfirmBeforeDelete(enabled: Boolean) {
        _confirmBeforeDelete.value = enabled
        settings.confirmBeforeDelete = enabled
    }

    fun setShowFileExtensions(enabled: Boolean) {
        _showFileExtensions.value = enabled
        settings.showFileExtensions = enabled
    }

    fun setShowListDividers(enabled: Boolean) {
        _showListDividers.value = enabled
        settings.showListDividers = enabled
    }

    fun setShowFolderSize(enabled: Boolean) {
        _showFolderSize.value = enabled
        settings.showFolderSize = enabled
        invalidateDirectoryCache()
        if (currentPath.isNotEmpty()) {
            loadFiles(currentPath, showLoading = false, isManualRefresh = true)
        }
    }

    fun setUseRecycleBin(enabled: Boolean) {
        _useRecycleBin.value = enabled
        settings.useRecycleBin = enabled
    }

    fun setShowRecentFiles(enabled: Boolean) {
        _showRecentFiles.value = enabled
        settings.showRecentFiles = enabled
    }

    fun setShowQuickAccess(enabled: Boolean) {
        settings.showQuickAccess = true
    }

    fun setShowExternalStorage(enabled: Boolean) {
        _showExternalStorage.value = enabled
        settings.showExternalStorage = enabled
    }

    fun setRecentFilesExpanded(expanded: Boolean) {
        _recentFilesExpanded.value = expanded
        settings.recentFilesExpanded = expanded
    }

    fun setQuickAccessExpanded(expanded: Boolean) {
        _quickAccessExpanded.value = expanded
        settings.quickAccessExpanded = expanded
    }

    fun setExternalStorageExpanded(expanded: Boolean) {
        _externalStorageExpanded.value = expanded
        settings.externalStorageExpanded = expanded
    }

    fun setStartPage(page: StartPage) {
        _startPage.value = page
        settings.startPage = page
    }

    fun setGridItemSize(size: GridItemSize) {
        _gridItemSize.value = size
        settings.gridItemSize = size.name
    }

    fun toggleHiddenFiles() {
        showHiddenFiles = !showHiddenFiles
        settings.showHiddenFiles = showHiddenFiles
        listingCache.invalidate()
        loadFiles(currentPath)
    }

    fun setSortOrder(sort: SortBy) {
        if (sortBy == sort) {
            sortOrder = if (sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
        } else {
            sortBy = sort
            sortOrder = if (sort == SortBy.DATE) SortOrder.DESCENDING else SortOrder.ASCENDING
        }
        settings.sortBy = sortBy.name
        settings.sortOrder = sortOrder.name

        pathScrollPositions.clear()
        invalidateDirectoryCache()

        if (categories.isActive) {
            categories.resort()
        } else if (currentZipFile != null) {
            files = sortFileList(files)
        } else if (currentPath.isEmpty()) {
        } else {
            files = sortFileList(files)
            if (sortBy == SortBy.SIZE) {
                loadFiles(currentPath, showLoading = false, isManualRefresh = true)
            }
        }
    }

    fun sortFileList(list: List<FileItem>): List<FileItem> {
        val byName = Comparator<FileItem> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
        val comparator = when (sortBy) {
            SortBy.NAME -> byName
            SortBy.DATE -> compareBy<FileItem> { it.lastModified }.then(byName)
            SortBy.SIZE -> compareBy<FileItem> { it.size }.then(byName)
            SortBy.TYPE -> compareBy<FileItem> { it.fileType }.then(byName)
        }

        val finalComparator = if (sortOrder == SortOrder.DESCENDING) {
            comparator.reversed()
        } else {
            comparator
        }

        return list.sortedWith(
            if (foldersFirst) {
                compareByDescending<FileItem> { it.isDirectory }.thenComparing(finalComparator)
            } else {
                finalComparator
            }
        )
    }
    var accessDenied by mutableStateOf(false)
        private set

    fun loadFiles(path: String, isManualRefresh: Boolean = false, showLoading: Boolean = true) {
        if (storage.storageRemovedEvent != null) {
            storage.clearStorageRemovedEvent()
        }

        val normalizedPath = ShizukuManager.normalize(path)
        val archiveExtensions = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk", "xapk", "apks")
        val archiveMimeTypes = listOf(
            "application/zip", "application/x-zip", "application/x-zip-compressed",
            "application/x-7z-compressed", "application/x-rar-compressed", "application/rar", "application/vnd.rar",
            "application/x-tar", "application/gzip", "application/x-bzip2", "application/x-xz",
            "application/vnd.android.package-archive"
        )

        val isArchive = if (path.startsWith("content://") || path.startsWith("/content:/") || path.startsWith("content:/")) {
            val normalizedUriStr = SafManager.normalizeSafPath(path)
            val uri = runCatching { Uri.parse(normalizedUriStr) }.getOrNull()
            if (uri != null && (DocumentsContract.isTreeUri(uri) || storage.storageDevices.any { it.path == path || it.path == normalizedUriStr })) {
                false
            } else if (uri != null) {
                val mime = runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull()
                val displayName = runCatching { getFileNameFromUri(uri)?.lowercase() }.getOrNull() ?: ""

                mime in archiveMimeTypes ||
                        archiveExtensions.any { displayName.endsWith(".$it") } ||
                        path.lowercase().let { p -> archiveExtensions.any { p.endsWith(".$it") } }
            } else {
                false
            }
        } else if (path.startsWith("file://")) {
            val extension = MimeTypeMap.getFileExtensionFromUrl(path).lowercase()
            extension in archiveExtensions || path.lowercase().let { p -> archiveExtensions.any { p.endsWith(".$it") } }
        } else if (SafManager.isRestrictedPath(normalizedPath)) {
            val ext = normalizedPath.substringAfterLast('.', "").lowercase()
            ext in archiveExtensions && !normalizedPath.endsWith("/")
        } else {
            val ext = normalizedPath.substringAfterLast('.', "").lowercase()
            if (ext in archiveExtensions || archiveExtensions.any { normalizedPath.lowercase().endsWith(".$it") }) {
                val file = File(normalizedPath)
                file.exists() && file.isFile
            } else false
        }

        if (isArchive) {
            if (path.startsWith("content://") || path.startsWith("file://")) {
                openArchive(getApplication(), android.net.Uri.parse(path))
            } else {
                openArchive(File(normalizedPath))
            }
            return
        }

        val isRestrictedFolder = isShizukuRestrictedPath(normalizedPath)
        val cached = if (!isManualRefresh && isRestrictedFolder) directoryCache[normalizedPath] else null
        if (cached != null && System.currentTimeMillis() - cached.first < 60_000L) {
            currentPath = normalizedPath
            files = sortFileList(cached.second)
            isLoading = false
            accessDenied = false
            return
        }

        val cachedListing = if (!isManualRefresh && !isRestrictedFolder && !SafManager.isSafUri(normalizedPath)) {
            listingCache.get(normalizedPath)
        } else null

        loadJob?.cancel()
        accessDenied = false
        if (normalizedPath != currentPath) {
            currentPath = normalizedPath
            if (cachedListing != null) {
                files = sortFileList(cachedListing.items)
                isLoading = false
            } else {
                files = emptyList()
                isLoading = true
            }
        } else if (cachedListing == null) {
            if (isManualRefresh) isRefreshing = true else if (showLoading) isLoading = true
        }

        loadJob?.cancel()
        val requestGeneration = ++filesGeneration
        if (isManualRefresh) {
            folderSizeCache.clear()
        }
        loadJob = viewModelScope.launch {
            try {
                currentZipFile = null
                currentZipEntryPath = ""

                val isSaf = SafManager.isSafUri(normalizedPath)
                val isRestricted = !isSaf && isShizukuRestrictedPath(normalizedPath)
                val isRemote = isRemotePath(normalizedPath)
                val isLocal = !isSaf && !isRestricted && !isRemote

                val dirMtime = if (isLocal) withContext(Dispatchers.IO) { File(normalizedPath).lastModified() } else 0L

                val reusable = cachedListing != null && dirMtime != 0L &&
                        cachedListing.dirMtime == dirMtime && cachedListing.ageMs < LISTING_CACHE_FRESH_MS

                val result = if (reusable) {
                    sortFileList(cachedListing!!.items)
                } else {
                    val listing = roseNGLog.timed(TAG, "loadFiles $normalizedPath", { l ->
                        if (l is DirectoryListing.Success) "${l.items.size} items" else "access denied"
                    }) {
                        fileRepository.listDirectory(normalizedPath, showHiddenFiles, useRoot)
                    }
                    val rawItems = when (listing) {
                        is DirectoryListing.Success -> listing.items
                        DirectoryListing.AccessDenied -> {
                            withContext(Dispatchers.Main) { accessDenied = true }
                            emptyList()
                        }
                    }
                    withContext(Dispatchers.IO) {
                        val sized = if (sortBy == SortBy.SIZE || showFolderSize) withFolderSizes(rawItems) else rawItems
                        sortFileList(sized)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (requestGeneration != filesGeneration) return@withContext
                    if (result != files) files = result
                    isLoading = false
                    isRefreshing = false
                    if (!accessDenied && isRestricted) {
                        directoryCache[normalizedPath] = System.currentTimeMillis() to result
                    }
                    if (normalizedPath == Environment.getExternalStorageDirectory().absolutePath) {
                        rootCache = result
                    }
                    if (isLocal && dirMtime != 0L && !accessDenied) {
                        if (!reusable) listingCache.put(normalizedPath, result, dirMtime)
                        startWatchingDirectory(normalizedPath, result, dirMtime)
                    } else {
                        stopWatchingDirectory()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    isRefreshing = false
                }
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        if (requestGeneration == filesGeneration) {
                            isLoading = false
                            isRefreshing = false
                        }
                    }
                }
            }
        }
    }

    fun searchFiles(
        query: String,
        filterType: FileType? = categories.categoryFilterType,
        bucketId: String? = categories.categoryBucketId,
        scopePath: String? = if (categories.categoryFilterType != null) null else currentPath
    ) = search.search(query, filterType, bucketId, scopePath, inMemorySource = categories.categoryFiles)

    fun openArchive(file: File, initialEntryPath: String = "") {
        stopWatchingDirectory()
        currentPath = file.parent ?: Environment.getExternalStorageDirectory().absolutePath
        currentZipFile = file
        cachedZipPassword = null
        currentZipSourcePath = file.absolutePath
        currentZipEntryPath = initialEntryPath
        files = emptyList()
        isLoading = true

        val requestGeneration = ++filesGeneration
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!file.exists()) {
                    throw Exception("Archive file no longer exists.")
                }
                val entries = roseNGLog.timed(TAG, "openArchive ${file.name}", { e -> "${e.size} entries" }) {
                    ArchiveManager.readEntries(file)
                }
                if (entries.isEmpty()) {
                    throw Exception("Failed to read any entries from the archive.")
                }
                zipEntriesCache = entries
                val sorted = sortFileList(buildZipDirectoryListing(entries, initialEntryPath, file))
                withContext(Dispatchers.Main) {
                    if (requestGeneration == filesGeneration && currentZipFile == file) {
                        files = sorted
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (requestGeneration == filesGeneration) {
                        errorMessage = "Failed to open archive: ${e.message}"
                        currentZipFile = null
                        loadFiles(currentPath)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (requestGeneration == filesGeneration) {
                        isLoading = false
                    }
                }
            }
        }
    }

    fun openArchive(context: android.content.Context, uri: android.net.Uri) {
        isLoading = true
        val requestGeneration = ++filesGeneration
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val displayName = getFileNameFromUri(uri) ?: "shared_archive"
                val extFromName = displayName.substringAfterLast('.', "").lowercase()
                val mime = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
                val effectiveExt = when {
                    extFromName in listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk", "xapk", "apks") -> extFromName
                    mime == "application/vnd.android.package-archive" -> "apk"
                    mime in listOf("application/zip", "application/x-zip", "application/x-zip-compressed") -> "zip"
                    else -> "zip"
                }
                val baseName = if (displayName.contains(".")) displayName.substringBeforeLast('.') else displayName
                val safeBase = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50)

                val viewArchivesRoot = File(context.cacheDir, "view_archives")
                if (viewArchivesRoot.exists()) {
                    viewArchivesRoot.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
                }
                val targetDir = File(viewArchivesRoot, "${System.currentTimeMillis()}")
                targetDir.mkdirs()
                val tempFile = File(targetDir, "$safeBase.$effectiveExt")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception("Failed to open shared file.")

                withContext(Dispatchers.Main) {
                    if (requestGeneration == filesGeneration) {
                        currentZipSourcePath = uri.toString()
                        openArchive(tempFile)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (requestGeneration == filesGeneration) {
                        errorMessage = "Failed to open shared archive: ${e.message}"
                        isLoading = false
                    }
                }
            }
        }
    }

    private fun buildZipDirectoryListing(entries: List<RoseArchiveEntry>, dirPath: String, zipFile: File): List<FileItem> {
        val prefix = dirPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val children = LinkedHashMap<String, FileItem>()

        val directChildNames = HashMap<String, MutableSet<String>>()
        val directChildSizes = HashMap<String, Long>()

        for (entry in entries) {
            val name = entry.name.trimStart('/')
            if (!name.startsWith(prefix)) continue
            val remainder = name.removePrefix(prefix)
            if (remainder.isEmpty()) continue
            val slashIndex = remainder.indexOf('/')
            if (slashIndex == -1) {
                children[remainder] = FileItem(
                    file = File(zipFile, name),
                    isDirectory = entry.isDirectory,
                    name = remainder,
                    size = entry.size,
                    lastModified = entry.lastModified,
                    extension = remainder.substringAfterLast('.', "").lowercase(),
                    virtualZipSource = zipFile,
                    zipEntryPath = name,
                    isEncrypted = entry.isEncrypted
                )
            } else {
                val childName = remainder.substring(0, slashIndex)
                children.getOrPut(childName) {
                    FileItem(
                        file = File(zipFile, prefix + childName),
                        isDirectory = true,
                        name = childName,
                        size = 0,
                        lastModified = entry.lastModified,
                        extension = "",
                        virtualZipSource = zipFile,
                        zipEntryPath = "$prefix$childName/",
                        isEncrypted = entry.isEncrypted
                    )
                }

                if (!entry.isDirectory && entry.size > 0L) {
                    directChildSizes[childName] = (directChildSizes[childName] ?: 0L) + entry.size
                }

                val grandRemainder = remainder.substring(slashIndex + 1)
                if (grandRemainder.isNotEmpty()) {
                    val grandSlash = grandRemainder.indexOf('/')
                    val grandName = if (grandSlash == -1) grandRemainder else grandRemainder.substring(0, grandSlash)
                    directChildNames.getOrPut(childName) { mutableSetOf() }.add(grandName)
                }
            }
        }

        return children.values.map { item ->
            if (item.isDirectory) {
                item.copy(
                    itemCount = directChildNames[item.name]?.size ?: 0,
                    size = if (showFolderSize || sortBy == SortBy.SIZE) directChildSizes[item.name] ?: 0L else 0L
                )
            } else {
                item
            }
        }
    }

    fun navigateZipInto(entryPath: String) {
        val zipFile = currentZipFile ?: return
        exitSelectionMode()
        currentZipEntryPath = entryPath
        files = sortFileList(buildZipDirectoryListing(zipEntriesCache, entryPath, zipFile))
    }

    fun navigateZipUp(): Boolean {
        val zipFile = currentZipFile ?: return false
        if (currentZipEntryPath.isEmpty()) return false
        val parent = currentZipEntryPath.trimEnd('/').substringBeforeLast('/', "")
        val parentPath = if (parent.isEmpty()) "" else "$parent/"
        exitSelectionMode()
        currentZipEntryPath = parentPath
        files = sortFileList(buildZipDirectoryListing(zipEntriesCache, parentPath, zipFile))
        return true
    }

    fun clearExtraction() {
        extractionSource = null
        extractionEntries = null
    }

    fun prepareExtraction(file: File, entries: List<String>? = null) {
        extractionSource = file
        extractionEntries = entries
        clipboardFiles.clear()
        clipboardSourceZip = null
        isCopyOperation = true
    }

    fun startExtraction(
        file: File,
        destDir: File = File(file.parent ?: Environment.getExternalStorageDirectory().absolutePath, file.nameWithoutExtension),
        entries: List<String>? = extractionEntries,
        passphrase: String? = null
    ) {
        if (!passphrase.isNullOrEmpty()) {
            extractArchive(file, destDir, passphrase)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val isEncrypted = ArchiveManager.isArchiveEncrypted(file)
            withContext(Dispatchers.Main) {
                if (isEncrypted) {
                    passphrasePromptItem = FileItem(file).copy(isEncrypted = true)
                    passphraseAction = { pw ->
                        extractArchive(file, destDir, pw)
                    }
                } else {
                    extractArchive(file, destDir, null)
                }
            }
        }
    }

    fun extractArchive(file: File, destDir: File = File(file.parent ?: Environment.getExternalStorageDirectory().absolutePath, file.nameWithoutExtension), passphrase: String? = null) {
        dev.narayan.rose.filejob.FileJobService.startExtract(getApplication(), file.absolutePath, destDir.absolutePath, extractionEntries, passphrase)
        clearExtraction()
    }

    private suspend fun fetchRecentFileItems(): List<FileItem> =
        roseNGLog.timed(TAG, "loadRecentFiles (limit=$recentFilesLimit)", { r -> "${r.size} items" }) {
            mediaStoreRepository.recentFiles(recentFilesLimit, excludedFolders)
        }

    private var recentFilesKey: Triple<Long, Set<String>, Int>? = null

    fun loadRecentFiles() {
        stopWatchingDirectory()
        accessDenied = false
        if (recentFiles.isEmpty()) isRecentLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = Triple(mediaStoreRepository.generation(), excludedFolders, recentFilesLimit)
                if (key.first >= 0 && key == recentFilesKey && recentFiles.isNotEmpty()) return@launch
                val recents = fetchRecentFileItems()
                withContext(Dispatchers.Main) {
                    recentFiles = recents
                    recentFilesKey = key
                    saveCachedRecentFiles(recents)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isRecentLoading = false
                    isRefreshing = false
                }
            }
        }
    }

    private val safNavStack = mutableListOf<String>()

    fun navigateTo(directory: File, isActuallyDirectory: Boolean = false) {
        if (currentZipFile != null) return
        val rawPath = directory.path
        val normalizedPath = if (SafManager.isSafUri(rawPath)) {
            SafManager.normalizeSafPath(rawPath)
        } else {
            ShizukuManager.normalize(directory.absolutePath)
        }
        if (!useRoot && normalizedPath == "/storage") return

        val isRootPath = normalizedPath == "/" || (useRoot && RootManager.isRootPath(normalizedPath))
        val isKnownStorage = storage.storageDevices.any { it.path == normalizedPath }
        val isSaf = SafManager.isSafUri(normalizedPath)
        val shouldNavigate = isSaf || isActuallyDirectory || isKnownStorage || isRootPath || File(normalizedPath).isDirectory ||
                (SafManager.isRestrictedPath(normalizedPath) &&
                        (SafManager.hasPermission(getApplication(), normalizedPath) ||
                                (ShizukuManager.isAvailable() && ShizukuManager.hasPermission())))

        if (shouldNavigate) {
            if (SafManager.isSafUri(currentPath) && currentPath != normalizedPath) {
                safNavStack.add(currentPath)
            }
            exitSelectionMode()
            loadFiles(normalizedPath)
        }
    }

    fun navigateUp(): Boolean {
        if (currentZipFile != null) {
            if (navigateZipUp()) return true
            return false
        }
        if (isSelectionMode) {
            exitSelectionMode()
            return true
        }
        if (SafManager.isSafUri(currentPath)) {
            if (safNavStack.isNotEmpty()) {
                val parentSafPath = safNavStack.removeAt(safNavStack.lastIndex)
                loadFiles(parentSafPath)
                return true
            }
            return false
        }
        if (isRemotePath(currentPath)) {
            val uri = runCatching { Uri.parse(currentPath) }.getOrNull()
            val path = uri?.path
            if (path != null && path != "/" && path != "/root" && path.isNotEmpty()) {
                val parentPath = path.substringBeforeLast('/', "")
                val newUri = currentPath.substringBefore(path) + (if (parentPath.isEmpty()) "/" else parentPath)
                loadFiles(newUri)
                return true
            }
            return false
        }
        if (currentPath == "/") {
            return false
        }
        val currentFile = File(currentPath)
        val parent = currentFile.parentFile

        val isRootParent = parent != null && (parent.absolutePath == "/" || (useRoot && RootManager.isRootPath(parent.absolutePath)))
        if (!useRoot && parent?.absolutePath == "/storage") {
            return false
        }
        if (parent != null && (parent.exists() || isRootParent || SafManager.isRestrictedPath(parent.absolutePath)) &&
            currentPath != Environment.getExternalStorageDirectory().absolutePath &&
            currentPath != ShizukuManager.normalize(Environment.getExternalStorageDirectory().absolutePath)
        ) {
            loadFiles(parent.absolutePath)
            return true
        }
        return false
    }

    fun toggleSelection(fileItem: FileItem) {
        if (selectedFiles.contains(fileItem)) {
            selectedFiles.remove(fileItem)
            if (selectedFiles.isEmpty()) {
                isSelectionMode = false
            }
        } else {
            selectedFiles.add(fileItem)
            isSelectionMode = true
        }
    }

    fun setSelected(fileItem: FileItem, selected: Boolean) {
        if (selected) {
            if (!selectedFiles.contains(fileItem)) {
                selectedFiles.add(fileItem)
                isSelectionMode = true
            }
        } else {
            if (selectedFiles.remove(fileItem)) {
                if (selectedFiles.isEmpty()) {
                    isSelectionMode = false
                }
            }
        }
    }

    fun setSelectedRange(files: List<FileItem>, start: Int, end: Int, target: Boolean, initialPaths: Set<String>) {
        val rangePaths = mutableSetOf<String>()
        for (i in start..end) {
            if (i in files.indices) {
                rangePaths.add(files[i].file.absolutePath)
            }
        }

        val newSelectionPaths = if (target) {
            initialPaths + rangePaths
        } else {
            initialPaths - rangePaths
        }

        val currentPaths = selectedFiles.map { it.file.absolutePath }.toSet()

        selectedFiles.removeAll { it.file.absolutePath !in newSelectionPaths }

        val toAdd = files.filter { it.file.absolutePath in newSelectionPaths && it.file.absolutePath !in currentPaths }
        if (toAdd.isNotEmpty()) {
            selectedFiles.addAll(toAdd)
        }

        isSelectionMode = selectedFiles.isNotEmpty()
    }

    fun exitSelectionMode() {
        selectedFiles.clear()
        isSelectionMode = false
    }

    fun clearClipboard() {
        clipboardFiles.clear()
        clipboardSourceZip = null
    }

    fun selectAll(currentFiles: List<FileItem>) {
        selectedFiles.clear()
        selectedFiles.addAll(currentFiles)
        isSelectionMode = true
    }

    fun copySingle(fileItem: FileItem) {
        clipboardFiles.clear()
        clipboardFiles.add(fileItem)
        clipboardSourceZip = currentZipFile
        isCopyOperation = true
        exitSelectionMode()
    }

    fun moveSingle(fileItem: FileItem) {
        if (currentZipFile != null) return
        clipboardFiles.clear()
        clipboardFiles.add(fileItem)
        clipboardSourceZip = null
        isCopyOperation = false
        exitSelectionMode()
    }

    fun copySelected() {
        clipboardFiles.clear()
        clipboardFiles.addAll(selectedFiles)
        clipboardSourceZip = currentZipFile
        isCopyOperation = true
        exitSelectionMode()
    }

    fun moveSelected() {
        if (currentZipFile != null) return
        clipboardFiles.clear()
        clipboardFiles.addAll(selectedFiles)
        clipboardSourceZip = null
        isCopyOperation = false
        exitSelectionMode()
    }

    fun compressSelected(archiveName: String, format: ArchiveFormat = ArchiveFormat.ZIP, passphrase: String? = null) {
        val filesToZip = selectedFiles.toList()
        val baseDir = currentPath.ifEmpty {
            File(Environment.getExternalStorageDirectory(), "Download").absolutePath
        }
        val cleanName = archiveName.trim().replace('/', '_').replace('\\', '_')
        val ext = format.extension
        val fileName = if (cleanName.lowercase().endsWith(ext)) cleanName else "$cleanName$ext"
        val destFile = File(baseDir, fileName)
        val sources = filesToZip.map { it.file.absolutePath }
        val displayNames = filesToZip.map { it.name }

        dev.narayan.rose.filejob.FileJobService.startCompress(
            getApplication(),
            sources,
            displayNames,
            destFile.absolutePath,
            if (format == ArchiveFormat.ZIP) passphrase else null
        )
        exitSelectionMode()
    }

    fun pasteFiles() {
        val itemsToPaste = clipboardFiles.toList()
        val sourceZip = clipboardSourceZip
        val copying = isCopyOperation
        val targetDir = currentPath

        if (itemsToPaste.isEmpty()) return

        if (sourceZip != null) {
            val passphrase = if (sourceZip == currentZipFile) cachedZipPassword else null
            viewModelScope.launch(Dispatchers.IO) {
                isLoading = true
                val extractedDests = mutableListOf<File>()
                try {
                    itemsToPaste.forEach { fileItem ->
                        val dest = File(targetDir, fileItem.name)
                        extractEntry(sourceZip, fileItem.zipEntryPath ?: fileItem.name, dest, passphrase)
                        extractedDests.add(dest)
                    }
                } catch (e: Exception) {}
                withContext(Dispatchers.Main) {
                    clipboardFiles.clear()
                    clipboardSourceZip = null
                    invalidateDirectoryCache(targetDir)
                    loadFiles(targetDir, isManualRefresh = true, showLoading = false)
                    isLoading = false
                    rescanForMediaStore(getApplication(), *extractedDests.toTypedArray())
                }
            }
        } else {
            if (targetDir.isEmpty() || categories.categoryFilterType != null) {
                android.widget.Toast.makeText(getApplication(), R.string.toast_cannot_paste_here, android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            if (currentZipFile != null) {
                android.widget.Toast.makeText(getApplication(), R.string.toast_cannot_paste_in_archive, android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            if (!copying) {
                val allInSameFolder = itemsToPaste.all { item ->
                    val parent = item.file.parent ?: ""
                    SafManager.isSamePath(parent, targetDir)
                }
                if (allInSameFolder) {
                    android.widget.Toast.makeText(getApplication(), R.string.toast_source_dest_same, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            }

            val recursiveItem = itemsToPaste.find { item ->
                item.isDirectory && SafManager.isSubdirectoryOrSame(targetDir, item.file.absolutePath)
            }
            if (recursiveItem != null) {
                val msgRes = if (copying) R.string.toast_cannot_copy_into_subfolder else R.string.toast_cannot_move_into_subfolder
                android.widget.Toast.makeText(getApplication(), msgRes, android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val effectiveItems = if (!copying) {
                itemsToPaste.filterNot { item ->
                    val parent = item.file.parent ?: ""
                    SafManager.isSamePath(parent, targetDir)
                }
            } else {
                itemsToPaste
            }

            if (effectiveItems.isEmpty()) {
                android.widget.Toast.makeText(getApplication(), R.string.toast_source_dest_same, android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val sources = effectiveItems.map { it.file.absolutePath }
            val displayNames = effectiveItems.map { it.name }
            if (copying) {
                dev.narayan.rose.filejob.FileJobService.startCopy(getApplication(), sources, displayNames, targetDir)
            } else {
                dev.narayan.rose.filejob.FileJobService.startMove(getApplication(), sources, displayNames, targetDir)
            }
            clipboardFiles.clear()
            exitSelectionMode()
            invalidateDirectoryCache(targetDir)
            viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                invalidateDirectoryCache(targetDir)
                if (targetDir == currentPath) {
                    loadFiles(targetDir, isManualRefresh = true, showLoading = false)
                }
            }
        }
    }

    private fun extractEntry(archiveFile: File, entryName: String, dest: File, passphrase: String? = null) {
        try {
            ArchiveManager.extractTo(archiveFile, entryName, dest, passphrase)
        } catch (e: Exception) {
            roseNGLog.e("Archive", e) { "extractEntry failed for $entryName" }
        }
    }

    private fun copyFile(source: File, dest: File) {
        if (source.isDirectory) {
            if (!dest.exists()) dest.mkdirs()
            source.listFiles()?.forEach {
                copyFile(it, File(dest, it.name))
            }
        } else {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun deleteSelected(permanently: Boolean = false) {
        val itemsToDelete = selectedFiles.toList()

        val sources = itemsToDelete.map { it.file.absolutePath }
        val names = itemsToDelete.map { it.name }

        val isCloud = sources.any { storage.isCloudStorage(it) || SafManager.isCloudUri(it) }
        val hasRoot = sources.any { it == "/" || RootManager.isRootPath(it) }
        val hasRestricted = sources.any { SafManager.isRestrictedPath(it) }
        val canRecycleRestricted = ShizukuManager.isAvailable() && ShizukuManager.hasPermission()
        if (!isCloud && !hasRoot && useRecycleBin && !permanently && (!hasRestricted || canRecycleRestricted)) {
            dev.narayan.rose.filejob.FileJobService.startRecycle(getApplication(), sources, names)
        } else {
            dev.narayan.rose.filejob.FileJobService.startDelete(getApplication(), sources, names)
        }
        exitSelectionMode()
    }

    fun deleteFile(fileItem: FileItem, permanently: Boolean = false) {
        val path = fileItem.file.absolutePath
        val isCloud = storage.isCloudStorage(path) || SafManager.isCloudUri(path)

        val isRoot = path == "/" || RootManager.isRootPath(path)
        if (isRoot) {
            dev.narayan.rose.filejob.FileJobService.startDelete(getApplication(), listOf(path), listOf(fileItem.name))
            return
        }

        if (isCloud) {
            viewModelScope.launch(Dispatchers.IO) {
                val success = SafManager.delete(getApplication(), path)
                withContext(Dispatchers.Main) {
                    if (success) {
                        invalidateDirectoryCache(currentPath)
                        loadFiles(currentPath, isManualRefresh = true, showLoading = false)
                    } else {
                        errorMessage = "Couldn't delete from cloud storage."
                    }
                }
            }
            return
        }

        if (SafManager.isRestrictedPath(path)) {
            val canRecycle = useRecycleBin && !permanently && ShizukuManager.isAvailable() && ShizukuManager.hasPermission()
            if (canRecycle) {
                dev.narayan.rose.filejob.FileJobService.startRecycle(getApplication(), listOf(path), listOf(fileItem.name))
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                val success = if (SafManager.hasPermission(getApplication(), path)) {
                    SafManager.delete(getApplication(), path)
                } else if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                    ShizukuManager.delete(path)
                } else false

                withContext(Dispatchers.Main) {
                    if (success) {
                        invalidateDirectoryCache(currentPath)
                        loadFiles(currentPath, isManualRefresh = true, showLoading = false)
                    } else {
                        errorMessage = "Couldn't delete. Access restricted."
                    }
                }
            }
            return
        }

        if (useRecycleBin && !permanently && !SafManager.isRestrictedPath(path)) {
            dev.narayan.rose.filejob.FileJobService.startRecycle(getApplication(), listOf(path), listOf(fileItem.name))
        } else {
            dev.narayan.rose.filejob.FileJobService.startDelete(getApplication(), listOf(path), listOf(fileItem.name))
        }
    }

    fun resetFiles() {
        categories.clear()
        val oldZip = currentZipFile
        cleanupActiveArchiveCache(oldZip)
        currentZipFile = null
        cachedZipPassword = null
        currentZipSourcePath = null
        currentZipEntryPath = ""
        storage.clearStorageRemovedEvent()
        isRefreshing = false
        exitSelectionMode()
        safNavStack.clear()
        if (currentPath.isEmpty()) {
            currentPath = Environment.getExternalStorageDirectory().absolutePath
        }
    }

    private fun cleanupActiveArchiveCache(zipFile: File?) {
        if (zipFile != null && zipFile.absolutePath.contains("view_archives")) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val sessionDir = zipFile.parentFile
                    if (sessionDir != null && sessionDir.parentFile?.name == "view_archives") {
                        sessionDir.deleteRecursively()
                    } else {
                        zipFile.delete()
                    }
                }
            }
        }
    }

    fun closeArchive() {
        val oldZip = currentZipFile
        cleanupActiveArchiveCache(oldZip)
        currentZipFile = null
        cachedZipPassword = null
        currentZipSourcePath = null
        currentZipEntryPath = ""
        files = emptyList()
    }

    fun renameFile(fileItem: FileItem, newName: String) {
        val path = fileItem.file.absolutePath

        if (useRoot && (path == "/" || RootManager.isRootPath(path))) {
            viewModelScope.launch(Dispatchers.IO) {
                val parent = fileItem.file.parent ?: ""
                val target = if (parent == "/") "/$newName" else "$parent/$newName"
                val success = RootManager.rename(path, target)
                withContext(Dispatchers.Main) {
                    if (success) {
                        invalidateDirectoryCache(currentPath)
                        loadFiles(currentPath, isManualRefresh = true)
                    } else {
                        errorMessage = "Couldn't rename. Root permission required or path in use."
                    }
                }
            }
            return
        }

        if (SafManager.isRestrictedPath(path)) {
            viewModelScope.launch(Dispatchers.IO) {
                val success = if (SafManager.hasPermission(getApplication(), path)) {
                    SafManager.rename(getApplication(), path, newName)
                } else if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                    ShizukuManager.rename(path, newName)
                } else false

                withContext(Dispatchers.Main) {
                    if (success) {
                        invalidateDirectoryCache(currentPath)
                        loadFiles(currentPath, isManualRefresh = true)
                    } else {
                        errorMessage = "Couldn't rename. Access restricted."
                    }
                }
            }
            return
        }

        val parent = fileItem.file.parentFile ?: if (currentPath.isNotEmpty()) File(currentPath) else null
        if (parent == null) {
            errorMessage = "Cannot rename root directory."
            return
        }
        val newFile = File(parent, newName)
        if (newFile.exists()) {
            errorMessage = "A file or folder with that name already exists."
            return
        }
        if (fileItem.file.renameTo(newFile)) {
            invalidateDirectoryCache(currentPath)
            val updatedItem = FileItem(newFile)
            rescanForMediaStore(getApplication(), fileItem.file, newFile)

            files = files.map {
                if (it.file.absolutePath == fileItem.file.absolutePath) updatedItem else it
            }

            recentFiles = recentFiles.map {
                if (it.file.absolutePath == fileItem.file.absolutePath) updatedItem else it
            }
            saveCachedRecentFiles(recentFiles)

            categories.replaceItem(fileItem.file.absolutePath, updatedItem)

            search.replaceItem(fileItem.file.absolutePath, updatedItem)

            if (categories.categoryFilterType == null && currentPath.isNotEmpty()) {
                files = sortFileList(files)
            }

            favourites.onFileRenamed(fileItem.file.absolutePath, newFile.absolutePath)
        } else {
            errorMessage = "Couldn't rename folder. It might be in use or restricted."
        }
    }

    fun createFolder(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return

        if (useRoot && (currentPath == "/" || RootManager.isRootPath(currentPath))) {
            viewModelScope.launch(Dispatchers.IO) {
                val path = if (currentPath == "/") "/$trimmedName" else "$currentPath/$trimmedName"
                val success = RootManager.createFolder(path)
                withContext(Dispatchers.Main) {
                    if (success) {
                        invalidateDirectoryCache(currentPath)
                        val folderFile = File(path)
                        if (clipboardFiles.isNotEmpty()) {
                            navigateTo(folderFile, isActuallyDirectory = true)
                        } else {
                            highlightedFile = FileItem(folderFile)
                            loadFiles(currentPath, isManualRefresh = true)
                        }
                    } else {
                        errorMessage = "Couldn't create folder in root partition."
                    }
                }
            }
            return
        }

        if (SafManager.isRestrictedPath(currentPath)) {
            viewModelScope.launch(Dispatchers.IO) {
                val path = "${currentPath.trimEnd('/')}/$trimmedName"
                val success = if (SafManager.hasPermission(getApplication(), currentPath)) {
                    val safResult = SafManager.createDirectory(getApplication(), path)
                    if (!safResult && ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                        ShizukuManager.createFolder(currentPath, trimmedName)
                    } else safResult
                } else if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                    ShizukuManager.createFolder(currentPath, trimmedName)
                } else false

                withContext(Dispatchers.Main) {
                    if (success) {
                        invalidateDirectoryCache(currentPath)
                        val folderFile = File(path)
                        if (clipboardFiles.isNotEmpty()) {
                            navigateTo(folderFile, isActuallyDirectory = true)
                        } else {
                            highlightedFile = FileItem(folderFile)
                            loadFiles(currentPath, isManualRefresh = true)
                        }
                    } else {
                        errorMessage = "Couldn't create folder. Access restricted."
                    }
                }
            }
            return
        }

        val newFolder = File(currentPath, trimmedName)
        if (newFolder.mkdir()) {
            invalidateDirectoryCache(currentPath)
            if (clipboardFiles.isNotEmpty()) {
                navigateTo(newFolder, isActuallyDirectory = true)
            } else {
                highlightedFile = FileItem(newFolder)
                loadFiles(currentPath, isManualRefresh = true)
            }
        } else {
            errorMessage = "Couldn't create folder. Name may already exist."
        }
    }

    fun createFile(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val finalName = if (!trimmed.contains('.')) "$trimmed.txt" else trimmed

        if (useRoot && (currentPath == "/" || RootManager.isRootPath(currentPath))) {
            viewModelScope.launch(Dispatchers.IO) {
                val path = if (currentPath == "/") "/$finalName" else "$currentPath/$finalName"
                val success = RootManager.createFile(path)
                withContext(Dispatchers.Main) {
                    if (success) {
                        invalidateDirectoryCache(currentPath)
                        val createdFile = File(path)
                        highlightedFile = FileItem(createdFile)
                        loadFiles(currentPath, isManualRefresh = true)
                    } else {
                        errorMessage = "Couldn't create file in root partition."
                    }
                }
            }
            return
        }

        if (SafManager.isRestrictedPath(currentPath)) {
            viewModelScope.launch(Dispatchers.IO) {
                val path = "${currentPath.trimEnd('/')}/$finalName"
                val success = if (SafManager.hasPermission(getApplication(), currentPath)) {
                    val doc = SafManager.createFileDocument(getApplication(), path)
                    if (doc == null && ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                        ShizukuManager.createFile(currentPath, finalName)
                    } else doc != null
                } else if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                    ShizukuManager.createFile(currentPath, finalName)
                } else false

                withContext(Dispatchers.Main) {
                    if (success) {
                        invalidateDirectoryCache(currentPath)
                        val createdFile = File(path)
                        highlightedFile = FileItem(createdFile)
                        loadFiles(currentPath, isManualRefresh = true)
                    } else {
                        errorMessage = "Couldn't create file. Access restricted."
                    }
                }
            }
            return
        }

        val newFile = File(currentPath, finalName)
        try {
            if (newFile.createNewFile()) {
                invalidateDirectoryCache(currentPath)
                highlightedFile = FileItem(newFile)
                loadFiles(currentPath, isManualRefresh = true)
            } else {
                errorMessage = "Couldn't create file. Name may already exist."
            }
        } catch (e: Exception) {
            errorMessage = "Couldn't create file: ${e.message}"
        }
    }

    fun saveSharedFiles(uris: List<Uri>, destPath: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            var success = true
            var highlightFile: FileItem? = null
            try {
                val restrictedDest = SafManager.isRestrictedPath(destPath)
                uris.forEachIndexed { index, uri ->
                    val fileName = getFileNameFromUri(uri) ?: "shared_file_${System.currentTimeMillis()}"
                    val resolver = getApplication<Application>().contentResolver
                    val file = File(destPath, fileName)
                    resolver.openInputStream(uri)?.use { input ->
                        val output = if (restrictedDest) {
                            SafManager.openOutputStreamForNewFile(getApplication(), "${destPath.trimEnd('/')}/$fileName")
                        } else {
                            file.outputStream()
                        }
                        output?.use { out -> input.copyTo(out) } ?: run { success = false }
                    } ?: run { success = false }

                    if (success && index == 0) {
                        highlightFile = FileItem(file)
                    }
                }
            } catch (e: Exception) {
                success = false
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    if (success && highlightFile != null) {
                        highlightedFile = highlightFile
                    }
                    onComplete(success)
                    if (currentPath == destPath) loadFiles(destPath)
                }
            }
        }
    }

    fun extractSharedZip(uri: Uri, destPath: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            var success = true
            var tempFile: File? = null
            try {
                tempFile = File(getApplication<Application>().cacheDir, "temp_extract_${System.currentTimeMillis()}.zip")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val fileName = getFileNameFromUri(uri) ?: "extracted"
                val folderName = fileName.substringBeforeLast(".")
                val finalDestPath = if (folderName.isNotEmpty()) "${destPath.trimEnd('/')}/$folderName" else destPath

                val restrictedDest = SafManager.isRestrictedPath(finalDestPath)

                if (restrictedDest) {
                    SafManager.createDirectory(getApplication(), finalDestPath)
                    ArchiveManager.extractAll(tempFile) { name, isDirectory, copyTask ->
                        val entryPath = "${finalDestPath.trimEnd('/')}/${name.trimEnd('/')}"
                        if (isDirectory) {
                            SafManager.createDirectory(getApplication(), entryPath)
                        } else {
                            val parentPath = entryPath.substringBeforeLast("/")
                            SafManager.createDirectory(getApplication(), parentPath)
                            SafManager.openOutputStreamForNewFile(getApplication(), entryPath)?.use { output ->
                                copyTask(output)
                            }
                        }
                    }
                } else {
                    val destDir = File(finalDestPath)
                    if (!destDir.exists()) destDir.mkdirs()
                    val destCanonicalPath = destDir.canonicalPath

                    ArchiveManager.extractAll(tempFile) { name, isDirectory, copyTask ->
                        val entryFile = File(destDir, name)
                        val entryCanonicalPath = entryFile.canonicalPath
                        if (entryCanonicalPath == destCanonicalPath ||
                            entryCanonicalPath.startsWith(destCanonicalPath + File.separator)
                        ) {
                            if (isDirectory) {
                                entryFile.mkdirs()
                            } else {
                                entryFile.parentFile?.mkdirs()
                                entryFile.outputStream().use { output ->
                                    copyTask(output)
                                }
                            }
                        }
                    }
                }
                tempFile.delete()
            } catch (e: Exception) {
                success = false
            } finally {
                tempFile?.let { runCatching { it.delete() } }
                withContext(Dispatchers.Main) {
                    isLoading = false
                    onComplete(success)
                    if (currentPath == destPath) loadFiles(destPath)
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content" && !DocumentsContract.isTreeUri(uri)) {
            try {
                getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1) result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    var isRecentLoading by mutableStateOf(false)
        private set

    var offlineFiles: Set<String> by mutableStateOf(settings.offlineFiles)
    val activeJobs = JobManager.activeJobs

    private fun offlineFileName(fileItem: FileItem): String =
        SafManager.ensureFileExtension(fileItem.name, fileItem.mimeType)

    fun toggleOfflineStatus(fileItem: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = fileItem.file.absolutePath
            val fileName = offlineFileName(fileItem)
            if (isFileOffline(path)) {
                val localFile = File(getApplication<Application>().getExternalFilesDir("offline"), fileName)
                if (localFile.exists()) localFile.delete()

                val normalized = SafManager.normalizeSafPath(path)
                val updated = offlineFiles.filterNot {
                    it == path || it == normalized || SafManager.normalizeSafPath(it) == normalized
                }.toSet()
                withContext(Dispatchers.Main) {
                    offlineFiles = updated
                    settings.offlineFiles = updated
                    files = files.toList()
                }
            } else {
                try {
                    val offlineDir = getApplication<Application>().getExternalFilesDir("offline") ?: return@launch
                    if (!offlineDir.exists()) offlineDir.mkdirs()

                    val localFile = File(offlineDir, fileName)

                    dev.narayan.rose.filejob.FileJobService.startDownload(
                        getApplication(),
                        path,
                        fileName,
                        localFile.absolutePath
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Failed to save offline: ${e.message}"
                    }
                }
            }
        }
    }

    fun isFileOffline(path: String): Boolean {
        if (offlineFiles.contains(path)) return true
        val normalized = SafManager.normalizeSafPath(path)
        if (offlineFiles.contains(normalized)) return true
        if (path.startsWith("/") && offlineFiles.contains(path.removePrefix("/"))) return true
        if (!path.startsWith("/") && offlineFiles.contains("/$path")) return true
        return false
    }

    fun getLocalOfflineFile(fileItem: FileItem): File? {
        if (!isFileOffline(fileItem.file.absolutePath)) return null
        val offlineDir = getApplication<Application>().getExternalFilesDir("offline") ?: return null
        val localFile = File(offlineDir, offlineFileName(fileItem))
        return if (localFile.exists()) localFile else null
    }

    override fun onCleared() {
        super.onCleared()
        unregisterMediaObserver()
        debouncedMediaRefreshJob?.cancel()
        stopWatchingDirectory()
        updates.clear()
    }
}
