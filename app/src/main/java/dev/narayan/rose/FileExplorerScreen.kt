package dev.narayan.rose

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.narayan.rose.ui.icons.AppIcons
import dev.narayan.rose.ui.navigation.ExplorerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.ui.explorer.*

internal const val LARGE_FILE_THRESHOLD = 500 * 1024 * 1024L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FileExplorerScreen(
    viewModel: RoseViewModel = viewModel(),
    startPath: String? = null,
    startCategory: Pair<FileType, String>? = null,
    startRecent: Boolean = false,
    startFavourites: Boolean = false,
    fromHome: Boolean = false,
    highlightFile: FileItem? = null,
    isFromAllFiles: Boolean = false,
    isFromQuickAccess: Boolean = false,
    resumePath: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onExitToHome: (() -> Unit)? = null,
    onFileClick: (FileItem) -> Unit = {},
    onOpenWithClick: (FileItem) -> Unit = {},
    onShareClick: (List<FileItem>) -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    backHandlerEnabled: Boolean = true,
    onSearchActiveChange: (Boolean) -> Unit = {},
    onOpenPath: ((String, FileItem?) -> Unit)? = null,
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0)
) {
    val context = LocalContext.current

    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<FileItem?>(null) }
    var showExtractionDialog by remember { mutableStateOf<FileItem?>(null) }
    var pendingDelete: PendingDelete? by remember { mutableStateOf<PendingDelete?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(isSearching) {
        onSearchActiveChange(isSearching)
    }
    var currentView by remember(startPath, startCategory, startRecent, startFavourites) {
        mutableStateOf(
            when {
                startCategory != null -> ExplorerView.Category
                startRecent -> ExplorerView.Recent
                startFavourites -> ExplorerView.Favourites
                else -> ExplorerView.Files
            }
        )
    }

    var activeScreen by remember { mutableStateOf("Main") }
    var lastNonZipView by remember { mutableStateOf(currentView) }
    val screenEnterTime = remember { System.currentTimeMillis() }
    var lastBackClickTime by remember { mutableLongStateOf(0L) }

    val canProcessItemClick: () -> Boolean = {
        val now = System.currentTimeMillis()
        val notTransitioning = animatedVisibilityScope == null || !animatedVisibilityScope.transition.isRunning
        notTransitioning &&
            (now - screenEnterTime > 200L) &&
            (now - viewModel.lastNavigationTimestamp > 200L) &&
            (now - viewModel.lastSwipeToHomeTimestamp > 200L)
    }

    val coroutineScope = rememberCoroutineScope()
    val isDocumentCategory = currentView == ExplorerView.Category && viewModel.categories.categoryFilterType == FileType.DOCUMENT
    val docCategoryCounts = remember(viewModel.categories.categoryFiles, isDocumentCategory) {
        if (isDocumentCategory) computeDocumentCategoryCounts(viewModel.categories.categoryFiles) else emptyMap()
    }
    val orderedDocCategories = remember(viewModel.categories.categoryFiles, docCategoryCounts, isDocumentCategory) {
        if (isDocumentCategory) getOrderedDocumentCategories(viewModel.categories.categoryFiles, docCategoryCounts) else emptyList()
    }
    val docPagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { if (orderedDocCategories.isNotEmpty()) orderedDocCategories.size else 1 }
    )
    val activeDocCategory = remember(docPagerState.currentPage, orderedDocCategories) {
        orderedDocCategories.getOrElse(docPagerState.currentPage.coerceIn(0, (orderedDocCategories.size - 1).coerceAtLeast(0))) {
            DocumentTypeCategory.ALL
        }
    }

    val lifecycleOwner: androidx.lifecycle.LifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event: androidx.lifecycle.Lifecycle.Event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (viewModel.isLoading || viewModel.isRefreshing || viewModel.currentZipFile != null) return@LifecycleEventObserver

                when {
                    currentView == ExplorerView.Recent -> viewModel.loadRecentFiles()
                    currentView == ExplorerView.Category -> {
                        val type = viewModel.categories.categoryFilterType
                        val title = viewModel.categories.categoryTitle
                        val bucketId = viewModel.categories.categoryBucketId
                        if (type != null && title != null) viewModel.categories.browseCategory(type, title, bucketId = bucketId)
                    }
                    else -> {
                        if (viewModel.currentPath.isNotEmpty()) {
                            viewModel.loadFiles(viewModel.currentPath, showLoading = false)
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(startPath, startCategory, startRecent, startFavourites, resumePath) {
        if (highlightFile != null) {
            viewModel.highlightedFile = highlightFile
        }
        if (resumePath != null) {
            if (viewModel.currentPath != resumePath || viewModel.files.isEmpty()) {
                viewModel.loadFiles(resumePath)
            } else {
                viewModel.loadFiles(resumePath, isManualRefresh = true, showLoading = false)
            }
            return@LaunchedEffect
        }
        when {
            startCategory != null -> {
                if (viewModel.categories.categoryFilterType != startCategory.first || (viewModel.categories.categoryBucketId == null && viewModel.categories.categoryTitle != startCategory.second) || (viewModel.categories.categoryFiles.isEmpty() && !viewModel.categories.isCategoryLoading)) {
                    viewModel.categories.browseCategory(startCategory.first, startCategory.second)
                }
            }
            startRecent -> {
                viewModel.clearScrollPosition("recent")
                viewModel.loadRecentFiles()
                listState.scrollToItem(0, 0)
                gridState.scrollToItem(0, 0)
            }
            startFavourites -> {
                viewModel.clearScrollPosition("favourites")
                listState.scrollToItem(0, 0)
                gridState.scrollToItem(0, 0)
            }
            startPath != null -> {
                if (SafManager.isSafUri(startPath)) {
                    viewModel.loadFiles(startPath)
                } else {
                    val file = File(startPath)
                    val archiveExtensions = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk", "xapk", "apks")
                    val isArchive = file.extension.lowercase() in archiveExtensions ||
                            file.name.lowercase().let { name -> archiveExtensions.any { name.endsWith(".$it") } }

                    val isDirectory = file.isDirectory || SafManager.isRestrictedPath(startPath) || (viewModel.useRoot && RootManager.isRootPath(startPath))

                    if (file.isFile && isArchive) {
                        viewModel.openArchive(file)
                    } else if (isDirectory || startPath != viewModel.currentPath || viewModel.files.isEmpty()) {
                        viewModel.loadFiles(startPath)
                    }
                }
            }
        }
    }

    LaunchedEffect(viewModel.storage.storageRemovedEvent) {
        val ejectedPath = viewModel.storage.storageRemovedEvent ?: return@LaunchedEffect
        viewModel.storage.clearStorageRemovedEvent()
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.toast_usb_drive_removed),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
    }

    BackHandler(enabled = backHandlerEnabled) {
        val now = System.currentTimeMillis()
        if (now - lastBackClickTime < 350L) return@BackHandler
        lastBackClickTime = now

        if (viewModel.propertiesFile != null) {
            viewModel.closeProperties()
        } else if (activeScreen != "Main") {
            activeScreen = "Main"
        } else if (viewModel.isSelectionMode) {
            viewModel.exitSelectionMode()
        } else if (isSearching) {
            isSearching = false
            searchQuery = ""
            viewModel.searchFiles("")
            viewModel.search.activeSearchFilter = SearchFilter.ALL
        } else if (viewModel.currentZipFile != null) {
            if (viewModel.currentZipEntryPath.isNotEmpty()) {
                viewModel.navigateZipUp()
            } else {
                if (fromHome && (viewModel.currentZipFile?.absolutePath == startPath || viewModel.currentZipSourcePath == startPath)) {
                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                } else if (lastNonZipView != ExplorerView.Files) {
                    currentView = lastNonZipView
                    if (currentView == ExplorerView.Recent) viewModel.loadRecentFiles()
                    else if (currentView == ExplorerView.Category) {
                        val type = viewModel.categories.categoryFilterType
                        val title = viewModel.categories.categoryTitle
                        if (type != null && title != null) viewModel.categories.browseCategory(type, title, bucketId = viewModel.categories.categoryBucketId)
                    }
                    viewModel.closeArchive()
                } else {
                    viewModel.closeArchive()
                    viewModel.loadFiles(viewModel.currentPath)
                }
            }
        } else if (currentView == ExplorerView.Category) {
            if (viewModel.categories.categoryBucketId != null) {
                viewModel.categories.browseCategory(FileType.IMAGE, context.getString(R.string.category_photos))
            } else {
                viewModel.categories.exitCategoryMode()
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            }
        } else if (currentView is ExplorerView.Recent || currentView is ExplorerView.Favourites) {
            viewModel.resetFiles()
            viewModel.categories.exitCategoryMode()
            onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
        } else {
            if (fromHome && viewModel.currentPath == startPath) {
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            } else if (!viewModel.navigateUp()) {
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            }
        }
    }

    val displayedFiles = if (isSearching && searchQuery.isNotBlank()) {
        if (currentView == ExplorerView.Category) viewModel.search.searchResults
        else if (currentView == ExplorerView.Favourites) viewModel.sortFileList(viewModel.favourites.getFavouriteFileItems().filter { it.name.contains(searchQuery, ignoreCase = true) })
        else viewModel.search.filteredSearchResults
    } else if (currentView == ExplorerView.Recent) {
        viewModel.recentFiles
    } else if (currentView == ExplorerView.Favourites) {
        viewModel.sortFileList(viewModel.favourites.getFavouriteFileItems())
    } else if (currentView == ExplorerView.Category) {
        viewModel.categories.categoryFiles
    } else {
        viewModel.files
    }

    val effectiveDisplayedFiles = if (isDocumentCategory) {
        filterDocumentFiles(displayedFiles, activeDocCategory)
    } else {
        displayedFiles
    }

    val activeScrollKey = when (currentView) {
        ExplorerView.Files -> if (viewModel.currentZipFile != null) {
            "zip://${viewModel.currentZipFile?.absolutePath}:${viewModel.currentZipEntryPath}"
        } else {
            viewModel.currentPath
        }
        ExplorerView.Category -> "category:${viewModel.categories.categoryTitle ?: ""}:${viewModel.categories.categoryBucketId ?: ""}"
        ExplorerView.Recent -> "recent"
        else -> currentView.key
    }

    var refreshAnimationToken by remember { mutableStateOf(0) }
    var isPullRefreshing by remember { mutableStateOf(false) }

    val scrollResetKey = if (isSearching) {
        "search_${searchQuery}_${viewModel.search.activeSearchFilter}_$currentView"
    } else when (currentView) {
        ExplorerView.Files -> "${activeScrollKey}_${viewModel.sortBy}_${viewModel.sortOrder}_$refreshAnimationToken"
        ExplorerView.Category -> "${viewModel.categories.categoryTitle ?: ""}_${viewModel.categories.categoryBucketId ?: ""}_${viewModel.sortBy}_${viewModel.sortOrder}_$refreshAnimationToken"
        else -> "${currentView}_${viewModel.sortBy}_${viewModel.sortOrder}_$refreshAnimationToken"
    }
    var lastScrollResetKey by remember { mutableStateOf(scrollResetKey) }
    var pendingScrollReset by remember { mutableStateOf(false) }

    if (scrollResetKey != lastScrollResetKey) {
        pendingScrollReset = true
    }

    LaunchedEffect(scrollResetKey) {
        pendingScrollReset = true
    }
    LaunchedEffect(displayedFiles, pendingScrollReset) {
        if (pendingScrollReset && displayedFiles.isNotEmpty()) {
            val savedPos = if (activeScrollKey == "recent") null else viewModel.getScrollPosition(activeScrollKey)
            if (savedPos != null) {
                val targetIndex = savedPos.first.coerceIn(0, (displayedFiles.size - 1).coerceAtLeast(0))
                listState.scrollToItem(targetIndex, savedPos.second)
                gridState.scrollToItem(targetIndex, savedPos.second)
            } else {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
            }
            pendingScrollReset = false
            lastScrollResetKey = scrollResetKey
        }
    }

    val currentIsGridView = if (currentView.usesCategoryGrid) viewModel.isCategoryGridView else viewModel.isGridView

    var pathForSaving by remember { mutableStateOf(activeScrollKey) }
    LaunchedEffect(displayedFiles, viewModel.isLoading, activeScrollKey) {
        if (!viewModel.isLoading && displayedFiles.isNotEmpty()) {
            pathForSaving = activeScrollKey
        }
    }

    LaunchedEffect(listState, gridState, pathForSaving, currentView, currentIsGridView) {
        if (pathForSaving.isNotEmpty()) {
            snapshotFlow {
                if (currentIsGridView) {
                    if (gridState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                        gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                    } else null
                } else {
                    if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    } else null
                }
            }.collect { pos ->
                if (pos != null && pathForSaving == activeScrollKey) {
                    viewModel.saveScrollPosition(pathForSaving, pos.first, pos.second)
                }
            }
        }
    }

    val animatedItemKeys = remember(scrollResetKey) { androidx.compose.runtime.mutableStateSetOf<String>() }

    val checkboxDragSelectState = rememberCheckboxDragSelectState()

    val autoScrollDensity = LocalDensity.current
    val latestDisplayedFiles by rememberUpdatedState(displayedFiles)
    LaunchedEffect(autoScrollDensity, listState, gridState, currentIsGridView) {
        val threshold = with(autoScrollDensity) { 100.dp.toPx() }
        val maxScrollAmount = with(autoScrollDensity) { 26.dp.toPx() }
        snapshotFlow { checkboxDragSelectState.dragPosition != null }.collectLatest { dragging ->
            if (!dragging) return@collectLatest
            while (true) {
                withFrameNanos { }
                val pos = checkboxDragSelectState.dragPosition ?: break
                val bounds = checkboxDragSelectState.containerBounds
                if (bounds != null) {
                    val factor = when {
                        pos.y < bounds.top + threshold -> -((bounds.top + threshold - pos.y) / threshold).coerceIn(0f, 1.2f)
                        pos.y > bounds.bottom - threshold -> ((pos.y - (bounds.bottom - threshold)) / threshold).coerceIn(0f, 1.2f)
                        else -> 0f
                    }
                    if (factor != 0f) {
                        val amount = maxScrollAmount * factor
                        if (currentIsGridView) {
                            gridState.scrollBy(amount)
                        } else {
                            listState.scrollBy(amount)
                        }

                        performDragSelect(checkboxDragSelectState, latestDisplayedFiles, viewModel)
                    }
                }
            }
        }
    }

    var hasLeftQuickAccessRoot by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.currentPath) {
        if (viewModel.currentPath.isNotEmpty() && startPath != null && viewModel.currentPath != startPath) {
            hasLeftQuickAccessRoot = true
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        hasLeftQuickAccessRoot = true
    }

    val isAtInitialQuickAccessRoot = isFromQuickAccess && !hasLeftQuickAccessRoot && (viewModel.currentPath == startPath || viewModel.currentPath.isEmpty())
    val skipEntranceAnimation = currentView == ExplorerView.Recent || isAtInitialQuickAccessRoot

    var lastTopKey by remember(scrollResetKey) { mutableStateOf<String?>(null) }
    var lastListSize by remember(scrollResetKey) { mutableStateOf(0) }
    LaunchedEffect(displayedFiles.firstOrNull()?.file?.absolutePath, scrollResetKey) {
        val newTopKey = displayedFiles.firstOrNull()?.file?.absolutePath
        val previousTopKey = lastTopKey
        val previousSize = lastListSize
        val newSize = displayedFiles.size
        lastTopKey = newTopKey
        lastListSize = newSize
        if (previousTopKey != null && newTopKey != null && newTopKey != previousTopKey &&
            newSize > previousSize &&
            !pendingScrollReset && listState.firstVisibleItemIndex == 0 && !listState.isScrollInProgress
        ) {
            listState.animateScrollToItem(0)
            gridState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(viewModel.highlightedFile, viewModel.highlightedPaths, effectiveDisplayedFiles) {
        val hasHighlight = viewModel.highlightedFile != null || viewModel.highlightedPaths.isNotEmpty()
        if (hasHighlight && effectiveDisplayedFiles.isNotEmpty()) {
            val index = effectiveDisplayedFiles.indexOfFirst {
                viewModel.isPathHighlighted(it.file.absolutePath)
            }
            if (index != -1) {
                kotlinx.coroutines.delay(100)
                val scrollIndex = if (!currentIsGridView) index + 1 else index
                if (currentIsGridView) {
                    gridState.animateScrollToItem(scrollIndex)
                } else {
                    listState.animateScrollToItem(scrollIndex)
                }
                kotlinx.coroutines.delay(1500)
                viewModel.clearHighlight()
            }
        }
    }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    val isCategoryOrFavourites = (startCategory != null || startFavourites) && fromHome
    val categoryTransitionKey = remember(startCategory, startFavourites) {
        when {
            startCategory != null -> "category_card_${startCategory.first.name}"
            startFavourites -> "category_card_FAVOURITES"
            else -> null
        }
    }
    val categoryBoundsModifier = if (isCategoryOrFavourites && categoryTransitionKey != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = categoryTransitionKey),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(tween(260)),
                exit = fadeOut(tween(220)),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
            )
        }
    } else Modifier

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(categoryBoundsModifier),
        color = MaterialTheme.colorScheme.background
    ) {
        Crossfade(targetState = activeScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            "Settings" -> {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { activeScreen = "Main" }
                )
            }
            "About" -> {
                AboutScreen(
                    viewModel = viewModel,
                    onBack = { activeScreen = "Main" }
                )
            }
            else -> {
                val homeBottomPadding = if (!viewModel.isSelectionMode) {
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                } else {
                    0.dp
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = contentWindowInsets,
                    topBar = {
                        Surface(
                            shadowElevation = 3.dp,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .animateContentSize()
                                .then(
                                    if (isFromAllFiles && sharedTransitionScope != null && animatedVisibilityScope != null) {
                                        with(sharedTransitionScope) {
                                            Modifier.sharedBounds(
                                                rememberSharedContentState(key = "all_files_capsule"),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                enter = fadeIn(),
                                                exit = fadeOut(),
                                                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                                            )
                                        }
                                    } else Modifier
                                )
                        ) {
                            AnimatedContent(
                                targetState = if (viewModel.isSelectionMode) "Selection" else if (isSearching) "Search" else "Main",
                                transitionSpec = {
                                    (fadeIn(tween(220, easing = FastOutSlowInEasing)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 4 })
                                        .togetherWith(fadeOut(tween(120)))
                                },
                                label = "TopBarTransition"
                            ) { target ->
                                when (target) {
                                    "Selection" -> {
                                        SelectionTopBar(
                                            viewModel = viewModel,
                                            isAllSelected = viewModel.selectedFiles.size == effectiveDisplayedFiles.size && effectiveDisplayedFiles.isNotEmpty(),
                                            onSelectAllClick = {
                                                if (viewModel.selectedFiles.size == effectiveDisplayedFiles.size) {
                                                    viewModel.exitSelectionMode()
                                                } else {
                                                    viewModel.selectAll(effectiveDisplayedFiles)
                                                }
                                            }
                                        )
                                    }
                                    "Search" -> {
                                        Column {
                                            SearchTopBar(searchQuery, onQueryChange = {
                                                searchQuery = it
                                                viewModel.searchFiles(it)
                                            }, onBack = {
                                                isSearching = false
                                                searchQuery = ""
                                                viewModel.searchFiles("")
                                                viewModel.search.activeSearchFilter = SearchFilter.ALL
                                            })
                                            if (currentView != ExplorerView.Category) {
                                                SearchFilterChipsRow(
                                                    selectedFilter = viewModel.search.activeSearchFilter,
                                                    onFilterSelected = { viewModel.search.activeSearchFilter = it },
                                                    counts = viewModel.search.searchFilterCounts
                                                )
                                            } else if (isDocumentCategory) {
                                                DocumentFilterChipsRow(
                                                    categories = orderedDocCategories,
                                                    selectedCategory = activeDocCategory,
                                                    onCategorySelected = { cat ->
                                                        val targetIndex = orderedDocCategories.indexOf(cat)
                                                        if (targetIndex >= 0) {
                                                            coroutineScope.launch {
                                                                docPagerState.animateScrollToPage(targetIndex)
                                                            }
                                                        }
                                                    },
                                                    counts = docCategoryCounts
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        val handleBack = handleBackLabel@{
                                            val now = System.currentTimeMillis()
                                            if (now - lastBackClickTime < 350L) return@handleBackLabel
                                            lastBackClickTime = now

                                            if (viewModel.propertiesFile != null) {
                                                viewModel.closeProperties()
                                            } else if (activeScreen != "Main") {
                                                activeScreen = "Main"
                                            } else if (viewModel.isSelectionMode) {
                                                viewModel.exitSelectionMode()
                                            } else if (isSearching) {
                                                isSearching = false
                                                searchQuery = ""
                                                viewModel.searchFiles("")
                                                viewModel.search.activeSearchFilter = SearchFilter.ALL
                                            } else if (viewModel.currentZipFile != null) {
                                                if (viewModel.currentZipEntryPath.isNotEmpty()) {
                                                    viewModel.navigateZipUp()
                                                } else if (fromHome && (viewModel.currentZipFile?.absolutePath == startPath || viewModel.currentZipSourcePath == startPath)) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                } else if (lastNonZipView != ExplorerView.Files) {
                                                    currentView = lastNonZipView
                                                    if (currentView == ExplorerView.Recent) viewModel.loadRecentFiles()
                                                    else if (currentView == ExplorerView.Category) {
                                                        val type = viewModel.categories.categoryFilterType
                                                        val title = viewModel.categories.categoryTitle
                                                        if (type != null && title != null) viewModel.categories.browseCategory(type, title, bucketId = viewModel.categories.categoryBucketId)
                                                    }
                                                    viewModel.closeArchive()
                                                } else {
                                                    viewModel.closeArchive()
                                                    viewModel.loadFiles(viewModel.currentPath)
                                                }
                                            } else if (currentView == ExplorerView.Category) {
                                                if (viewModel.categories.categoryBucketId != null) {
                                                    viewModel.categories.browseCategory(FileType.IMAGE, context.getString(R.string.category_photos))
                                                } else {
                                                    viewModel.categories.exitCategoryMode()
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                }
                                            } else if (currentView is ExplorerView.Recent || currentView is ExplorerView.Favourites) {
                                                viewModel.resetFiles()
                                                viewModel.categories.exitCategoryMode()
                                                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                            } else {
                                                if (fromHome && viewModel.currentPath == startPath) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                } else if (!viewModel.navigateUp()) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                }
                                            }
                                        }

                                        val shouldGroupTop = currentView == ExplorerView.Recent && searchQuery.isBlank()

                                        Column {
                                            MainTopBar(
                                                title = when (currentView) {
                                                    ExplorerView.Recent -> stringResource(R.string.home_section_recent_files)
                                                    ExplorerView.Category -> viewModel.categories.categoryTitle ?: stringResource(R.string.category_files)
                                                    ExplorerView.Favourites -> stringResource(R.string.title_favourite_files)
                                                    else -> stringResource(R.string.category_all_files)
                                                },
                                                path = if (currentView == ExplorerView.Files) viewModel.currentPath else null,
                                                archiveName = viewModel.currentZipFile?.name?.let { zipName ->
                                                    val subPath = viewModel.currentZipEntryPath.trimEnd('/')
                                                    if (subPath.isEmpty()) zipName else "$zipName/$subPath"
                                                },
                                                viewModel = viewModel,
                                                currentView = currentView,
                                                totalItems = if (shouldGroupTop) displayedFiles.size else null,
                                                sharedTransitionScope = sharedTransitionScope,
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                onSearchClick = { isSearching = true },
                                                onRefreshClick = {
                                                    when (currentView) {
                                                        ExplorerView.Recent -> viewModel.loadRecentFiles()
                                                        ExplorerView.Category -> {
                                                            val type = viewModel.categories.categoryFilterType
                                                            val title = viewModel.categories.categoryTitle
                                                            if (type != null && title != null) viewModel.categories.browseCategory(type, title, bucketId = viewModel.categories.categoryBucketId, forceRefresh = true)
                                                        }
                                                        else -> viewModel.loadFiles(viewModel.currentPath)
                                                    }
                                                },
                                                onNewFileClick = { showCreateFileDialog = true },
                                                onNewFolderClick = { showCreateFolderDialog = true },
                                                onSettingsClick = { activeScreen = "Settings" },
                                                onNavigate = { viewModel.navigateTo(it, isActuallyDirectory = true) },
                                                onBack = { handleBack() }
                                            )
                                            if (isDocumentCategory) {
                                                DocumentFilterChipsRow(
                                                    categories = orderedDocCategories,
                                                    selectedCategory = activeDocCategory,
                                                    onCategorySelected = { cat ->
                                                        val targetIndex = orderedDocCategories.indexOf(cat)
                                                        if (targetIndex >= 0) {
                                                            coroutineScope.launch {
                                                                docPagerState.animateScrollToPage(targetIndex)
                                                            }
                                                        }
                                                    },
                                                    counts = docCategoryCounts
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        val isInsideHomePager = fromHome && startRecent
                        AnimatedVisibility(
                            visible = viewModel.isSelectionMode,
                            enter = slideInVertically(
                                animationSpec = tween(
                                    durationMillis = 350,
                                    delayMillis = if (isInsideHomePager) 140 else 0,
                                    easing = FastOutSlowInEasing
                                )
                            ) { it },
                            exit = slideOutVertically(
                                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                            ) { it }
                        ) {
                            SelectionBottomBar(
                                viewModel = viewModel,
                                currentView = currentView,
                                onShare = { onShareClick(viewModel.selectedFiles.toList()) },
                                onDeleteClick = {
                                    val totalSize = viewModel.selectedFiles.sumOf { it.size }
                                    if (viewModel.confirmBeforeDelete || totalSize > LARGE_FILE_THRESHOLD) {
                                        pendingDelete = PendingDelete.Selection(viewModel.selectedFiles.size, totalSize)
                                    } else {
                                        viewModel.deleteSelected()
                                    }
                                },
                                onCompressClick = { showCompressDialog = true },
                                onRenameClick = {
                                    showRenameDialog = viewModel.selectedFiles.firstOrNull()
                                },
                                onExtractClick = {
                                    val fileItem = viewModel.selectedFiles.firstOrNull()
                                    if (fileItem != null) {
                                        if (currentView.isVirtual) {
                                            viewModel.prepareExtraction(fileItem.file)
                                            viewModel.exitSelectionMode()
                                            Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), Toast.LENGTH_SHORT).show()
                                        } else {
                                            showExtractionDialog = fileItem
                                        }
                                    }
                                },
                                isAllSelected = viewModel.selectedFiles.size == effectiveDisplayedFiles.size && effectiveDisplayedFiles.isNotEmpty(),
                                onSelectAllClick = {
                                    if (viewModel.selectedFiles.size == effectiveDisplayedFiles.size) {
                                        viewModel.exitSelectionMode()
                                    } else {
                                        viewModel.selectAll(effectiveDisplayedFiles)
                                    }
                                },
                                onOpenArchiveClick = { fileItem ->
                                    if (isSearching) {
                                        isSearching = false
                                        searchQuery = ""
                                        viewModel.searchFiles("")
                                    }
                                    lastNonZipView = currentView
                                    viewModel.openArchive(fileItem.file)
                                    currentView = ExplorerView.Files
                                },
                                onOpenPath = onOpenPath
                            )
                        }
                    },
                    floatingActionButton = {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp, end = 4.dp)
                        ) {
                            AnimatedVisibility(
                                visible = viewModel.extractionSource != null,
                                enter = scaleIn(animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeIn(),
                                exit = scaleOut(animationSpec = tween(300)) + fadeOut()
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    tonalElevation = 4.dp,
                                    shadowElevation = 6.dp
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.clearExtraction() },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                AppIcons.Close,
                                                contentDescription = stringResource(R.string.action_cancel),
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        VerticalDivider(
                                            modifier = Modifier
                                                .height(24.dp)
                                                .padding(horizontal = 4.dp),
                                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.1f)
                                        )
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .then(
                                                    if (currentView.isVirtual || viewModel.currentZipFile != null) {
                                                        Modifier.alpha(0.5f)
                                                    } else {
                                                        Modifier.clickable {
                                                            val dest = File(viewModel.currentPath, viewModel.extractionSource!!.nameWithoutExtension)
                                                            viewModel.startExtraction(viewModel.extractionSource!!, dest)
                                                        }
                                                    }
                                                )
                                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                AppIcons.UnarchiveOutlined,
                                                null,
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                stringResource(R.string.action_extract_here),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = viewModel.clipboardFiles.isNotEmpty(),
                                enter = scaleIn(animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeIn(),
                                exit = scaleOut(animationSpec = tween(300)) + fadeOut()
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    tonalElevation = 4.dp,
                                    shadowElevation = 6.dp
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.clearClipboard() },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                AppIcons.Close,
                                                contentDescription = stringResource(R.string.action_cancel),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        VerticalDivider(
                                            modifier = Modifier
                                                .height(24.dp)
                                                .padding(horizontal = 4.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                                        )
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .then(
                                                    if (currentView.isVirtual || viewModel.currentZipFile != null) {
                                                        Modifier.alpha(0.5f)
                                                    } else {
                                                        Modifier.clickable {
                                                            viewModel.pasteFiles()
                                                        }
                                                    }
                                                )
                                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                AppIcons.ContentPaste,
                                                null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                if (viewModel.isCopyOperation) stringResource(R.string.action_paste_here) else stringResource(R.string.action_move_here),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                ) { paddingValues ->
                    val refreshScope = rememberCoroutineScope()
                    val pullToRefreshState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = isPullRefreshing,
                        onRefresh = {
                            if (viewModel.isSelectionMode || viewModel.isLoading || isPullRefreshing) return@PullToRefreshBox
                            isPullRefreshing = true
                            refreshScope.launch {
                                val startTime = System.currentTimeMillis()
                                try {
                                    when {
                                        currentView == ExplorerView.Recent -> {
                                            viewModel.loadRecentFiles()
                                        }
                                        currentView == ExplorerView.Favourites -> {
                                            refreshAnimationToken++
                                        }
                                        currentView == ExplorerView.Category -> {
                                            val type = viewModel.categories.categoryFilterType
                                            val title = viewModel.categories.categoryTitle
                                            if (type != null && title != null) {
                                                viewModel.categories.browseCategory(type, title, bucketId = viewModel.categories.categoryBucketId, forceRefresh = true)
                                            }
                                        }
                                        viewModel.currentZipFile != null -> {
                                            viewModel.openArchive(viewModel.currentZipFile!!, viewModel.currentZipEntryPath)
                                        }
                                        else -> {
                                            viewModel.loadFiles(viewModel.currentPath, isManualRefresh = true)
                                        }
                                    }
                                    while (viewModel.isLoading || viewModel.categories.isCategoryLoading || viewModel.isRecentLoading) {
                                        kotlinx.coroutines.delay(40)
                                    }
                                } finally {
                                    val elapsed = System.currentTimeMillis() - startTime
                                    val minSpinDuration = 650L
                                    if (elapsed < minSpinDuration) {
                                        kotlinx.coroutines.delay(minSpinDuration - elapsed)
                                    }
                                    isPullRefreshing = false
                                    viewModel.isRefreshing = false
                                    refreshAnimationToken++
                                }
                            }
                        },
                        state = pullToRefreshState,
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pullToRefreshState,
                                isRefreshing = isPullRefreshing,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        },
                        modifier = Modifier.padding(top = paddingValues.calculateTopPadding()).fillMaxSize()
                    ) {
                        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { coords ->
                            checkboxDragSelectState.containerBounds = coords.boundsInWindow()
                        }.then(
                            if (viewModel.isSelectionMode) {
                                Modifier.pointerInput(checkboxDragSelectState, effectiveDisplayedFiles) {
                                    detectCheckboxDragSelect(
                                        dragSelectState = checkboxDragSelectState,
                                        displayedFiles = effectiveDisplayedFiles,
                                        viewModel = viewModel
                                    )
                                }
                            } else Modifier
                        )) {
                            val isRestricted = (viewModel.currentPath.contains("/Android/data") || viewModel.currentPath.contains("/Android/obb"))
                            val hasShizuku = ShizukuManager.isAvailable() && ShizukuManager.hasPermission()
                            val hasSaf = SafManager.hasPermission(LocalContext.current, viewModel.currentPath)

                            val isRemote = viewModel.isRemotePath(viewModel.currentPath)

                            val accessDenied = viewModel.accessDenied && currentView == ExplorerView.Files && viewModel.categories.categoryFilterType == null && !isRemote

                            if (accessDenied && !viewModel.isLoading) {
                                val isSystemRestricted = isRestricted
                                RestrictedFolderView(
                                    path = viewModel.currentPath,
                                    isShizukuAuthorized = hasShizuku,
                                    title = if (isSystemRestricted) stringResource(R.string.restricted_system_folder_title) else stringResource(R.string.drive_access_required_title),
                                    description = if (isSystemRestricted) stringResource(R.string.restricted_system_folder_desc)
                                    else stringResource(R.string.drive_access_required_desc),
                                    onGrantShizuku = {
                                        if (ShizukuManager.isAvailable()) {
                                            viewModel.onShizukuResult(false, viewModel.currentPath)
                                            viewModel.retryShizuku(viewModel.currentPath)
                                        } else {
                                            ShizukuManager.requestBinder(context)
                                            val launchIntent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            if (launchIntent != null) {
                                                context.startActivity(launchIntent)
                                            }
                                            Toast.makeText(context, context.getString(R.string.toast_shizuku_not_running), Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    onGrantSaf = {
                                        viewModel.retrySaf(viewModel.currentPath)
                                    },
                                    onRefresh = {
                                        ShizukuManager.requestBinder(context)
                                        viewModel.loadFiles(viewModel.currentPath, isManualRefresh = true)
                                    }
                                )
                            } else {
                                val isInitialLoad = (currentView == ExplorerView.Category && viewModel.categories.categoryFilterType == null) ||
                                        (currentView == ExplorerView.Recent && viewModel.recentFiles.isEmpty() && viewModel.categories.categoryFilterType == null)
                                val isContentLoading = if (isSearching && searchQuery.isNotBlank()) {
                                    false
                                } else when (currentView) {
                                    ExplorerView.Category -> (viewModel.categories.isCategoryLoading || viewModel.isRefreshing || isInitialLoad) && displayedFiles.isEmpty()
                                    ExplorerView.Recent -> (viewModel.isRecentLoading || viewModel.isRefreshing || isInitialLoad) && displayedFiles.isEmpty()
                                    else -> (viewModel.isLoading || viewModel.isRefreshing) && displayedFiles.isEmpty()
                                }

                                var showCenteredLoading by remember { mutableStateOf(false) }
                                LaunchedEffect(isContentLoading) {
                                    if (isContentLoading) {
                                        kotlinx.coroutines.delay(150)
                                        showCenteredLoading = true
                                    } else {
                                        showCenteredLoading = false
                                    }
                                }

                                AnimatedVisibility(
                                    visible = showCenteredLoading,
                                    enter = fadeIn(tween(150)),
                                    exit = fadeOut(tween(100))
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(36.dp),
                                            strokeWidth = 3.5.dp,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                val explorerContext = remember(currentView, viewModel.categories.categoryTitle, viewModel.categories.categoryBucketId) {
                                    ExplorerContext(currentView, viewModel.categories.categoryTitle, "")
                                }

                                if (!isContentLoading) {
                                    AnimatedContent(
                                        targetState = explorerContext,
                                        transitionSpec = {
                                            if (targetState.view == initialState.view) {
                                                fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                                            } else {
                                                (fadeIn(tween(220, easing = LinearOutSlowInEasing)) +
                                                        scaleIn(initialScale = 0.92f, animationSpec = tween(220, easing = LinearOutSlowInEasing)))
                                                    .togetherWith(fadeOut(tween(120, easing = FastOutLinearInEasing)) +
                                                            scaleOut(targetScale = 1.05f, animationSpec = tween(120, easing = FastOutLinearInEasing)))
                                            }
                                        },
                                        label = "FolderTransition",
                                        modifier = Modifier.fillMaxSize()
                                    ) { state ->
                                        val displayedFilesFinal = if (isSearching && searchQuery.isNotBlank()) {
                                            if (state.view == ExplorerView.Category) viewModel.search.searchResults
                                            else if (state.view == ExplorerView.Favourites) viewModel.sortFileList(viewModel.favourites.getFavouriteFileItems().filter { it.name.contains(searchQuery, ignoreCase = true) })
                                            else viewModel.search.filteredSearchResults
                                        } else if (state.view == ExplorerView.Recent) {
                                            viewModel.recentFiles
                                        } else if (state.view == ExplorerView.Favourites) {
                                            viewModel.sortFileList(viewModel.favourites.getFavouriteFileItems())
                                        } else if (state.view == ExplorerView.Category) {
                                            viewModel.categories.categoryFiles
                                        } else {
                                            viewModel.files
                                        }

                                        val shouldGroup = state.view == ExplorerView.Recent && searchQuery.isBlank()

                                        val indexByPath = remember(displayedFilesFinal) {
                                            HashMap<String, Int>(displayedFilesFinal.size * 2).also { map ->
                                                displayedFilesFinal.forEachIndexed { i, item -> map[item.file.absolutePath] = i }
                                            }
                                        }

                                        val groupedItems = remember(displayedFilesFinal, shouldGroup) {
                                            if (!shouldGroup) emptyList<ListItemType>()
                                            else {
                                                val list = mutableListOf<ListItemType>()
                                                displayedFilesFinal.groupBy { formatDateHeader(context, it.lastModified) }
                                                    .forEach { (header, files) ->
                                                        list.add(ListItemType.Header(header, files.size))
                                                        files.forEach { list.add(ListItemType.File(it)) }
                                                    }
                                                list
                                            }
                                        }

                                        val isCurrentlyLoading = when (state.view) {
                                            ExplorerView.Category -> viewModel.categories.isCategoryLoading || isPullRefreshing
                                            ExplorerView.Recent -> viewModel.isRecentLoading || isPullRefreshing
                                            ExplorerView.Favourites -> false
                                            else -> viewModel.isLoading || isPullRefreshing
                                        }

                                        val isNavigatingOrResetting = pendingScrollReset || isPullRefreshing || (scrollResetKey != lastScrollResetKey)

                                        if (isSearching && searchQuery.isNotBlank() && displayedFilesFinal.isEmpty()) {
                                            if (viewModel.search.isRecursiveSearching) {
                                                SearchLoadingView(
                                                    query = searchQuery,
                                                    categoryName = if (state.view == ExplorerView.Category) viewModel.categories.categoryTitle else null,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                SearchEmptyStateView(
                                                    query = searchQuery,
                                                    filter = viewModel.search.activeSearchFilter,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        } else if (displayedFilesFinal.isEmpty() && !isCurrentlyLoading) {
                                            if (state.view == ExplorerView.Favourites) {
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.padding(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = AppIcons.FavoriteBorder,
                                                            contentDescription = null,
                                                            tint = Color(0xFFE11D48).copy(alpha = 0.5f),
                                                            modifier = Modifier.size(64.dp)
                                                        )
                                                        Spacer(modifier = Modifier.height(16.dp))
                                                        Text(
                                                            text = stringResource(R.string.empty_favourite_files),
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(
                                                            text = stringResource(R.string.empty_favourite_files_subtitle),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                        )
                                                    }
                                                }
                                            } else {
                                                if (isRemote && searchQuery.isEmpty()) {
                                                    RemoteStorageEmptyView(
                                                        path = viewModel.currentPath,
                                                        onRefresh = { viewModel.loadFiles(viewModel.currentPath, isManualRefresh = true) }
                                                    )
                                                } else {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Text(if (searchQuery.isEmpty()) stringResource(R.string.no_files_found) else stringResource(R.string.no_results_for_query, searchQuery))
                                                    }
                                                }
                                            }
                                        } else if (isDocumentCategory) {
                                            HorizontalPager(
                                                state = docPagerState,
                                                modifier = Modifier.fillMaxSize()
                                            ) { pageIndex ->
                                                val pageCat = orderedDocCategories.getOrElse(pageIndex) { DocumentTypeCategory.ALL }
                                                val pageFiles = remember(displayedFilesFinal, pageCat) {
                                                    filterDocumentFiles(displayedFilesFinal, pageCat)
                                                }
                                                val pageGridState = if (pageIndex == 0) gridState else remember(pageCat.id) { androidx.compose.foundation.lazy.grid.LazyGridState() }
                                                val pageListState = if (pageIndex == 0) listState else remember(pageCat.id) { androidx.compose.foundation.lazy.LazyListState() }

                                                if (pageFiles.isEmpty() && !isCurrentlyLoading) {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                            Icon(
                                                                imageVector = pageCat.icon,
                                                                contentDescription = null,
                                                                tint = pageCat.accentColor.copy(alpha = 0.5f),
                                                                modifier = Modifier.size(48.dp)
                                                            )
                                                            Spacer(modifier = Modifier.height(8.dp))
                                                            Text(
                                                                text = if (pageCat == DocumentTypeCategory.ALL) {
                                                                    if (searchQuery.isEmpty()) stringResource(R.string.no_files_found) else stringResource(R.string.no_results_for_query, searchQuery)
                                                                } else {
                                                                    "No ${pageCat.label} files found"
                                                                },
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                 } else if (viewModel.isCategoryGridView) {
                                                    LazyVerticalGrid(
                                                        columns = GridCells.Adaptive(minSize = viewModel.gridItemSize.cellMinSize),
                                                        state = pageGridState,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentPadding = PaddingValues(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 8.dp + homeBottomPadding)
                                                    ) {
                                                        itemsIndexed(pageFiles, key = { _, item -> item.file.absolutePath }, contentType = { _, _ -> "grid_item" }) { index, fileItem ->
                                                            val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                            FileGridItem(
                                                                fileItem = fileItem,
                                                                isSelected = viewModel.isSelected(fileItem),
                                                                isHighlighted = isHighlighted,
                                                                showDetails = viewModel.showDetails,
                                                                showExtension = viewModel.showFileExtensions,
                                                                iconSize = viewModel.gridItemSize.iconSize,
                                                                isVirtual = false,
                                                                index = index,
                                                                scrollResetKey = scrollResetKey,
                                                                hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                                onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                                viewModel = viewModel,
                                                                modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                    Modifier
                                                                } else {
                                                                    Modifier.animateItem(
                                                                        fadeInSpec = null,
                                                                        fadeOutSpec = null,
                                                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    if (viewModel.isSelectionMode) {
                                                                        viewModel.toggleSelection(fileItem)
                                                                    } else if (canProcessItemClick()) {
                                                                        onFileClick(fileItem)
                                                                    }
                                                                },
                                                                onLongClick = {
                                                                    viewModel.toggleSelection(fileItem)
                                                                }
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    LazyColumn(
                                                        modifier = Modifier.fillMaxSize(),
                                                        state = pageListState,
                                                        contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp + homeBottomPadding)
                                                    ) {
                                                        itemsIndexed(pageFiles, key = { _, item -> item.file.absolutePath }, contentType = { _, _ -> "list_item" }) { index, fileItem ->
                                                            val isSelected = viewModel.isSelected(fileItem)
                                                            val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                            FileListItem(
                                                                fileItem = fileItem,
                                                                isSelected = isSelected,
                                                                isHighlighted = isHighlighted,
                                                                isSelectionMode = viewModel.isSelectionMode,
                                                                showDetails = viewModel.showDetails,
                                                                showExtension = viewModel.showFileExtensions,
                                                                showListDividers = viewModel.showListDividers,
                                                                isVirtual = false,
                                                                clipboardHasFiles = viewModel.clipboardFiles.isNotEmpty(),
                                                                index = index,
                                                                scrollResetKey = scrollResetKey,
                                                                hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                                onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                                modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                    Modifier
                                                                } else {
                                                                    Modifier.animateItem(
                                                                        fadeInSpec = null,
                                                                        fadeOutSpec = null,
                                                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    if (viewModel.isSelectionMode) {
                                                                        viewModel.toggleSelection(fileItem)
                                                                    } else if (canProcessItemClick()) {
                                                                        onFileClick(fileItem)
                                                                    }
                                                                },
                                                                onLongClick = {
                                                                    viewModel.toggleSelection(fileItem)
                                                                },
                                                                onDelete = {
                                                                    val totalSize = fileItem.size
                                                                    if (viewModel.confirmBeforeDelete || totalSize > LARGE_FILE_THRESHOLD) {
                                                                        pendingDelete = PendingDelete.Single(fileItem)
                                                                    } else {
                                                                        viewModel.deleteFile(fileItem)
                                                                    }
                                                                },
                                                                onRename = { newName -> viewModel.renameFile(fileItem, newName) },
                                                                onRenameRequest = { showRenameDialog = fileItem },
                                                                onOpenWith = { onOpenWithClick(fileItem) },
                                                                onShare = { onShareClick(listOf(fileItem)) },
                                                                onCopy = {
                                                                    viewModel.copySingle(fileItem)
                                                                    if (currentView.isVirtual) {
                                                                        coroutineScope.launch {
                                                                            kotlinx.coroutines.yield()
                                                                            onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                        }
                                                                    }
                                                                },
                                                                onCut = {
                                                                    viewModel.moveSingle(fileItem)
                                                                    if (currentView.isVirtual) {
                                                                        coroutineScope.launch {
                                                                            kotlinx.coroutines.yield()
                                                                            onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                        }
                                                                    }
                                                                },
                                                                onOpenLocation = null,
                                                                onExtract = {
                                                                    viewModel.prepareExtraction(fileItem.file)
                                                                    Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), Toast.LENGTH_SHORT).show()
                                                                },
                                                                onProperties = { viewModel.showProperties(fileItem) },
                                                                viewModel = viewModel,
                                                                dragSelectState = checkboxDragSelectState,
                                                                isDividerVisible = viewModel.showListDividers && index != pageFiles.lastIndex && !isSelected && !viewModel.isSelected(pageFiles.getOrNull(index + 1))
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else if (if (currentView.usesCategoryGrid) viewModel.isCategoryGridView else viewModel.isGridView) {
                                            LazyVerticalGrid(
                                                columns = GridCells.Adaptive(minSize = viewModel.gridItemSize.cellMinSize),
                                                state = gridState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 8.dp + homeBottomPadding)
                                            ) {
                                                if (shouldGroup) {
                                                    items(
                                                        items = groupedItems,
                                                        key = { item ->
                                                            when (item) {
                                                                is ListItemType.Header -> "header_${item.title}"
                                                                is ListItemType.File -> item.fileItem.file.absolutePath
                                                            }
                                                        },
                                                        span = { item ->
                                                            if (item is ListItemType.Header) GridItemSpan(maxLineSpan)
                                                            else GridItemSpan(1)
                                                        }
                                                    ) { item ->
                                                        when (item) {
                                                            is ListItemType.Header -> DateHeader(item.title, item.count)
                                                            is ListItemType.File -> {
                                                                val fileItem = item.fileItem
                                                                val indexInFullList = indexByPath[fileItem.file.absolutePath] ?: -1
                                                                val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                                FileGridItem(
                                                                    fileItem = fileItem,
                                                                    isSelected = viewModel.isSelected(fileItem),
                                                                    isHighlighted = isHighlighted,
                                                                    showDetails = viewModel.showDetails,
                                                                    showExtension = viewModel.showFileExtensions,
                                                                    iconSize = viewModel.gridItemSize.iconSize,
                                                                    isVirtual = viewModel.currentZipFile != null,
                                                                    index = indexInFullList,
                                                                    scrollResetKey = scrollResetKey,
                                                                    hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                                    onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                                    viewModel = viewModel,
                                                                    modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                        Modifier
                                                                    } else {
                                                                        Modifier.animateItem(
                                                                            fadeInSpec = null,
                                                                            fadeOutSpec = null,
                                                                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                        )
                                                                    },
                                                                    onClick = {
                                                                        if (viewModel.isSelectionMode) {
                                                                            viewModel.toggleSelection(fileItem)
                                                                        } else if (!canProcessItemClick()) {
                                                                        } else {
                                                                            if (fileItem.isDirectory) {
                                                                                if (isSearching) {
                                                                                    isSearching = false
                                                                                    searchQuery = ""
                                                                                    viewModel.searchFiles("")
                                                                                }
                                                                                val isAlbum = (fileItem.bucketId != null || fileItem.file.path.contains("album:")) && currentView == ExplorerView.Category
                                                                                if (isAlbum) {
                                                                                    val bucketId = fileItem.bucketId ?: fileItem.file.path.substringAfter("album:")
                                                                                    viewModel.categories.browseCategory(FileType.IMAGE, fileItem.name, bucketId)
                                                                                } else {
                                                                                    if (viewModel.currentZipFile != null && fileItem.zipEntryPath != null) {
                                                                                        viewModel.navigateZipInto(fileItem.zipEntryPath)
                                                                                    } else {
                                                                                        viewModel.navigateTo(fileItem.file, fileItem.isDirectory)
                                                                                    }
                                                                                    currentView = ExplorerView.Files
                                                                                }
                                                                            } else if (fileItem.fileType == FileType.ZIP) {
                                                                                if (isSearching) {
                                                                                    isSearching = false
                                                                                    searchQuery = ""
                                                                                    viewModel.searchFiles("")
                                                                                }
                                                                                lastNonZipView = currentView
                                                                                viewModel.openArchive(fileItem.file)
                                                                                currentView = ExplorerView.Files
                                                                            } else {
                                                                                onFileClick(fileItem)
                                                                            }
                                                                        }
                                                                    },
                                                                    onLongClick = {
                                                                        viewModel.toggleSelection(fileItem)
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    itemsIndexed(displayedFilesFinal, key = { _, item -> item.file.absolutePath }, contentType = { _, _ -> "grid_item" }) { index, fileItem ->
                                                        val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                        FileGridItem(
                                                            fileItem = fileItem,
                                                            isSelected = viewModel.isSelected(fileItem),
                                                            isHighlighted = isHighlighted,
                                                            showDetails = viewModel.showDetails,
                                                            showExtension = viewModel.showFileExtensions,
                                                            iconSize = viewModel.gridItemSize.iconSize,
                                                            isVirtual = viewModel.currentZipFile != null,
                                                            index = index,
                                                            scrollResetKey = scrollResetKey,
                                                            hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                            onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                            viewModel = viewModel,
                                                            modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                Modifier
                                                            } else {
                                                                Modifier.animateItem(
                                                                    fadeInSpec = null,
                                                                    fadeOutSpec = null,
                                                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                )
                                                            },
                                                            onClick = {
                                                                if (viewModel.isSelectionMode) {
                                                                    viewModel.toggleSelection(fileItem)
                                                                } else if (!canProcessItemClick()) {
                                                                } else {
                                                                    if (fileItem.isDirectory) {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        val isAlbum = (fileItem.bucketId != null || fileItem.file.path.contains("album:")) && currentView == ExplorerView.Category
                                                                        if (isAlbum) {
                                                                            val bucketId = fileItem.bucketId ?: fileItem.file.path.substringAfter("album:")
                                                                            viewModel.categories.browseCategory(FileType.IMAGE, fileItem.name, bucketId)
                                                                        } else {
                                                                            if (viewModel.currentZipFile != null && fileItem.zipEntryPath != null) {
                                                                                viewModel.navigateZipInto(fileItem.zipEntryPath)
                                                                            } else {
                                                                                viewModel.navigateTo(fileItem.file, fileItem.isDirectory)
                                                                            }
                                                                            currentView = ExplorerView.Files
                                                                        }
                                                                    } else if (fileItem.fileType == FileType.ZIP) {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        lastNonZipView = currentView
                                                                        viewModel.openArchive(fileItem.file)
                                                                        currentView = ExplorerView.Files
                                                                    } else {
                                                                        onFileClick(fileItem)
                                                                    }
                                                                }
                                                            },
                                                            onLongClick = {
                                                                viewModel.toggleSelection(fileItem)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                state = listState,
                                                contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp + homeBottomPadding)
                                            ) {
                                                if (shouldGroup) {
                                                    items(
                                                        groupedItems,
                                                        key = { item ->
                                                            when (item) {
                                                                is ListItemType.Header -> "header_${item.title}"
                                                                is ListItemType.File -> item.fileItem.file.absolutePath
                                                            }
                                                        }
                                                    ) { item ->
                                                        when (item) {
                                                            is ListItemType.Header -> DateHeader(item.title, item.count)
                                                            is ListItemType.File -> {
                                                                val fileItem = item.fileItem
                                                                val isSelected = viewModel.isSelected(fileItem)
                                                                val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                                val indexInFullList = indexByPath[fileItem.file.absolutePath] ?: -1
                                                                val isRoot = viewModel.currentPath == Environment.getExternalStorageDirectory().absolutePath
                                                                FileListItem(
                                                                    fileItem = fileItem,
                                                                    isSelected = isSelected,
                                                                    isHighlighted = isHighlighted,
                                                                    isSelectionMode = viewModel.isSelectionMode,
                                                                    showDetails = if (fileItem.isDirectory && isRoot) false else viewModel.showDetails,
                                                                    showExtension = viewModel.showFileExtensions,
                                                                    showListDividers = viewModel.showListDividers,
                                                                    isVirtual = viewModel.currentZipFile != null,
                                                                    clipboardHasFiles = viewModel.clipboardFiles.isNotEmpty(),
                                                                    index = indexInFullList,
                                                                    scrollResetKey = scrollResetKey,
                                                                    hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                                    onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                                    modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                        Modifier
                                                                    } else {
                                                                        Modifier.animateItem(
                                                                            fadeInSpec = null,
                                                                            fadeOutSpec = null,
                                                                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                        )
                                                                    },
                                                                    onClick = {
                                                                        if (viewModel.isSelectionMode) {
                                                                            viewModel.toggleSelection(fileItem)
                                                                        } else if (!canProcessItemClick()) {
                                                                        } else {
                                                                            if (fileItem.isDirectory) {
                                                                                if (isSearching) {
                                                                                    isSearching = false
                                                                                    searchQuery = ""
                                                                                    viewModel.searchFiles("")
                                                                                }
                                                                                val isAlbum = (fileItem.bucketId != null || fileItem.file.path.contains("album:")) && currentView == ExplorerView.Category
                                                                                if (isAlbum) {
                                                                                    val bucketId = fileItem.bucketId ?: fileItem.file.path.substringAfter("album:")
                                                                                    viewModel.categories.browseCategory(FileType.IMAGE, fileItem.name, bucketId)
                                                                                } else {
                                                                                    if (viewModel.currentZipFile != null && fileItem.zipEntryPath != null) {
                                                                                        viewModel.navigateZipInto(fileItem.zipEntryPath)
                                                                                    } else {
                                                                                        viewModel.navigateTo(fileItem.file, fileItem.isDirectory)
                                                                                    }
                                                                                    currentView = ExplorerView.Files
                                                                                }
                                                                            } else if (fileItem.fileType == FileType.ZIP) {
                                                                                if (isSearching) {
                                                                                    isSearching = false
                                                                                    searchQuery = ""
                                                                                    viewModel.searchFiles("")
                                                                                }
                                                                                lastNonZipView = currentView
                                                                                viewModel.openArchive(fileItem.file)
                                                                                currentView = ExplorerView.Files
                                                                            } else {
                                                                                onFileClick(fileItem)
                                                                            }
                                                                        }
                                                                    },
                                                                    onLongClick = {
                                                                        viewModel.toggleSelection(fileItem)
                                                                    },
                                                                    onDelete = {
                                                                        if (viewModel.confirmBeforeDelete || fileItem.size > LARGE_FILE_THRESHOLD) {
                                                                            pendingDelete = PendingDelete.Single(fileItem)
                                                                        } else {
                                                                            viewModel.deleteFile(fileItem)
                                                                        }
                                                                    },
                                                                    onRename = { newName -> viewModel.renameFile(fileItem, newName) },
                                                                    onRenameRequest = { showRenameDialog = fileItem },
                                                                    onOpenWith = { onOpenWithClick(fileItem) },
                                                                    onShare = { onShareClick(listOf(fileItem)) },
                                                                    onCopy = {
                                                                        viewModel.copySingle(fileItem)
                                                                        if (currentView.isVirtual) {
                                                                            coroutineScope.launch {
                                                                                kotlinx.coroutines.yield()
                                                                                onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                            }
                                                                        }
                                                                    },
                                                                    onCut = {
                                                                        viewModel.moveSingle(fileItem)
                                                                        if (currentView.isVirtual) {
                                                                            coroutineScope.launch {
                                                                                kotlinx.coroutines.yield()
                                                                                onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                            }
                                                                        }
                                                                    },
                                                                    onOpenLocation = if (searchQuery.isNotEmpty() || currentView is ExplorerView.Recent || currentView is ExplorerView.Favourites) {
                                                                        {
                                                                            val parent = fileItem.file.parentFile
                                                                            if (parent != null) {
                                                                                isSearching = false
                                                                                searchQuery = ""
                                                                                if ((currentView is ExplorerView.Recent || currentView is ExplorerView.Favourites) && onOpenPath != null) {
                                                                                    onOpenPath(parent.absolutePath, fileItem)
                                                                                } else {
                                                                                    viewModel.navigateTo(parent, true)
                                                                                    viewModel.highlightedFile = fileItem
                                                                                    currentView = ExplorerView.Files
                                                                                }
                                                                            }
                                                                        }
                                                                    } else null,
                                                                    onExtract = {
                                                                        val entries = if (fileItem.zipEntryPath != null) listOf(fileItem.zipEntryPath) else null
                                                                        if (currentView.isVirtual) {
                                                                            viewModel.prepareExtraction(fileItem.file, entries)
                                                                            Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), Toast.LENGTH_SHORT).show()
                                                                        } else {
                                                                            if (entries != null) {
                                                                                viewModel.prepareExtraction(fileItem.file, entries)
                                                                            }
                                                                            showExtractionDialog = fileItem
                                                                        }
                                                                    },
                                                                    onOpenArchive = {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        lastNonZipView = currentView
                                                                        viewModel.openArchive(fileItem.file)
                                                                        currentView = ExplorerView.Files
                                                                    },
                                                                    onProperties = { viewModel.showProperties(fileItem) },
                                                                    viewModel = viewModel,
                                                                    dragSelectState = checkboxDragSelectState,
                                                                    isDividerVisible = viewModel.showListDividers && indexInFullList != displayedFilesFinal.lastIndex && !isSelected && !viewModel.isSelected(displayedFilesFinal.getOrNull(indexInFullList + 1))
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    item(key = "top_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
                                                    itemsIndexed(displayedFilesFinal, key = { _, item -> item.file.absolutePath }, contentType = { _, _ -> "list_item" }) { index, fileItem ->
                                                        val isSelected = viewModel.isSelected(fileItem)
                                                        val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                        val isRoot = viewModel.currentPath == Environment.getExternalStorageDirectory().absolutePath
                                                        FileListItem(
                                                            fileItem = fileItem,
                                                            isSelected = isSelected,
                                                            isHighlighted = isHighlighted,
                                                            isSelectionMode = viewModel.isSelectionMode,
                                                            showDetails = if (fileItem.isDirectory && isRoot) false else viewModel.showDetails,
                                                            showExtension = viewModel.showFileExtensions,
                                                            showListDividers = viewModel.showListDividers,
                                                            isVirtual = viewModel.currentZipFile != null,
                                                            clipboardHasFiles = viewModel.clipboardFiles.isNotEmpty(),
                                                            index = index,
                                                            scrollResetKey = scrollResetKey,
                                                            hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                            onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                            modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                Modifier
                                                            } else {
                                                                Modifier.animateItem(
                                                                    fadeInSpec = null,
                                                                    fadeOutSpec = null,
                                                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                )
                                                            },
                                                            onClick = {
                                                                if (viewModel.isSelectionMode) {
                                                                    viewModel.toggleSelection(fileItem)
                                                                } else if (!canProcessItemClick()) {
                                                                } else {
                                                                    if (fileItem.isDirectory) {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        val isAlbum = (fileItem.bucketId != null || fileItem.file.path.contains("album:")) && currentView == ExplorerView.Category
                                                                        if (isAlbum) {
                                                                            val bucketId = fileItem.bucketId ?: fileItem.file.path.substringAfter("album:")
                                                                            viewModel.categories.browseCategory(FileType.IMAGE, fileItem.name, bucketId)
                                                                        } else {
                                                                            if (viewModel.currentZipFile != null && fileItem.zipEntryPath != null) {
                                                                                viewModel.navigateZipInto(fileItem.zipEntryPath)
                                                                            } else {
                                                                                viewModel.navigateTo(fileItem.file, fileItem.isDirectory)
                                                                            }
                                                                            currentView = ExplorerView.Files
                                                                        }
                                                                    } else if (fileItem.fileType == FileType.ZIP) {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        lastNonZipView = currentView
                                                                        viewModel.openArchive(fileItem.file)
                                                                        currentView = ExplorerView.Files
                                                                    } else {
                                                                        onFileClick(fileItem)
                                                                    }
                                                                }
                                                            },
                                                            onLongClick = {
                                                                viewModel.toggleSelection(fileItem)
                                                            },
                                                            onDelete = {
                                                                if (viewModel.confirmBeforeDelete || fileItem.size > LARGE_FILE_THRESHOLD) {
                                                                    pendingDelete = PendingDelete.Single(fileItem)
                                                                } else {
                                                                    viewModel.deleteFile(fileItem)
                                                                }
                                                            },
                                                            onRename = { newName -> viewModel.renameFile(fileItem, newName) },
                                                            onRenameRequest = { showRenameDialog = fileItem },
                                                            onOpenWith = { onOpenWithClick(fileItem) },
                                                            onShare = { onShareClick(listOf(fileItem)) },
                                                            onCopy = {
                                                                viewModel.copySingle(fileItem)
                                                                if (currentView.isVirtual) {
                                                                    coroutineScope.launch {
                                                                        kotlinx.coroutines.yield()
                                                                        onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                    }
                                                                }
                                                            },
                                                            onCut = {
                                                                viewModel.moveSingle(fileItem)
                                                                if (currentView.isVirtual) {
                                                                    coroutineScope.launch {
                                                                        kotlinx.coroutines.yield()
                                                                        onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                    }
                                                                }
                                                            },
                                                            onOpenLocation = if (searchQuery.isNotEmpty() || currentView is ExplorerView.Recent || currentView is ExplorerView.Favourites) {
                                                                {
                                                                    val parent = fileItem.file.parentFile
                                                                    if (parent != null) {
                                                                        isSearching = false
                                                                        searchQuery = ""
                                                                        if ((currentView is ExplorerView.Recent || currentView is ExplorerView.Favourites) && onOpenPath != null) {
                                                                            onOpenPath(parent.absolutePath, fileItem)
                                                                        } else {
                                                                            viewModel.navigateTo(parent, true)
                                                                            viewModel.highlightedFile = fileItem
                                                                            currentView = ExplorerView.Files
                                                                        }
                                                                    }
                                                                }
                                                            } else null,
                                                            onExtract = {
                                                                if (currentView.isVirtual) {
                                                                    viewModel.prepareExtraction(fileItem.file)
                                                                    Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), Toast.LENGTH_SHORT).show()
                                                                } else {
                                                                    showExtractionDialog = fileItem
                                                                }
                                                            },
                                                            onOpenArchive = {
                                                                if (isSearching) {
                                                                    isSearching = false
                                                                    searchQuery = ""
                                                                    viewModel.searchFiles("")
                                                                }
                                                                lastNonZipView = currentView
                                                                viewModel.openArchive(fileItem.file)
                                                                currentView = ExplorerView.Files
                                                            },
                                                            onProperties = { viewModel.showProperties(fileItem) },
                                                            viewModel = viewModel,
                                                            dragSelectState = checkboxDragSelectState,
                                                            isDividerVisible = viewModel.showListDividers && index != displayedFilesFinal.lastIndex && !isSelected && !viewModel.isSelected(displayedFilesFinal.getOrNull(index + 1))
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (viewModel.propertiesFile != null) {
                                    val propItem = viewModel.propertiesFile!!
                                    PropertiesDialog(
                                        fileItem = propItem,
                                        onDismiss = { viewModel.closeProperties() },
                                        viewModel = viewModel,
                                        onOpenLocation = { targetPath, targetItem ->
                                            viewModel.closeProperties()
                                            val targetFile = File(targetPath)
                                            isSearching = false
                                            searchQuery = ""
                                            if ((currentView is ExplorerView.Recent || currentView is ExplorerView.Favourites) && onOpenPath != null) {
                                                onOpenPath(targetFile.absolutePath, targetItem)
                                            } else {
                                                viewModel.navigateTo(targetFile, true)
                                                if (targetItem != null) {
                                                    viewModel.highlightedFile = targetItem
                                                }
                                                currentView = ExplorerView.Files
                                            }
                                        }
                                    )
                                }

                                showExtractionDialog?.let { fileItem ->
                                    ExtractionDialog(
                                        item = fileItem,
                                        onDismiss = { showExtractionDialog = null },
                                        onExtractHere = {
                                            val destDir = if (viewModel.currentZipFile != null || fileItem.zipEntryPath != null) {
                                                File(viewModel.currentPath)
                                            } else {
                                                File(fileItem.file.parent ?: Environment.getExternalStorageDirectory().absolutePath, fileItem.file.nameWithoutExtension)
                                            }
                                            val entries = if (fileItem.zipEntryPath != null) listOf(fileItem.zipEntryPath) else null
                                            viewModel.startExtraction(fileItem.file, destDir, entries)
                                            showExtractionDialog = null
                                            viewModel.exitSelectionMode()
                                        },
                                        onSelectLocation = {
                                            val entries = if (fileItem.zipEntryPath != null) listOf(fileItem.zipEntryPath) else null
                                            viewModel.prepareExtraction(fileItem.file, entries)
                                            showExtractionDialog = null
                                            viewModel.exitSelectionMode()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

    if (showCreateFileDialog) {
        CreateFileDialog(
            onDismiss = { showCreateFileDialog = false },
            onCreate = { name ->
                viewModel.createFile(name)
                showCreateFileDialog = false
            }
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            }
        )
    }

    if (showCompressDialog) {
        CompressDialog(
            itemCount = viewModel.selectedFiles.size,
            onDismiss = { showCompressDialog = false },
            onConfirm = { name, format, password ->
                viewModel.compressSelected(name, format, password)
                showCompressDialog = false
            }
        )
    }

    showRenameDialog?.let { fileItem ->
        RenameDialog(
            initialName = fileItem.name,
            onDismiss = { showRenameDialog = null },
            onRename = { newName ->
                viewModel.renameFile(fileItem, newName)
                showRenameDialog = null
                viewModel.exitSelectionMode()
            }
        )
    }

    pendingDelete?.let { pending ->
        val isOnRemovable = when (pending) {
            is PendingDelete.Single -> viewModel.storage.isRemovableStorage(pending.fileItem.file.absolutePath)
            is PendingDelete.Selection -> viewModel.storage.isCurrentPathRemovable()
        }
        val isCloud = when (pending) {
            is PendingDelete.Single -> viewModel.storage.isCloudStorage(pending.fileItem.file.absolutePath) || SafManager.isCloudUri(pending.fileItem.file.absolutePath)
            is PendingDelete.Selection -> viewModel.storage.isCloudStorage(viewModel.currentPath) || SafManager.isCloudUri(viewModel.currentPath)
        }
        DeleteConfirmationDialog(
            useRecycleBin = viewModel.useRecycleBin && !isCloud,
            pending = pending,
            onDismiss = { pendingDelete = null },
            onConfirm = { permanently ->
                when (pending) {
                    is PendingDelete.Single -> viewModel.deleteFile(pending.fileItem, permanently || isCloud)
                    is PendingDelete.Selection -> viewModel.deleteSelected(permanently || isCloud)
                }
                pendingDelete = null
            },
            isOnRemovableStorage = isOnRemovable || isCloud
        )
    }
}
