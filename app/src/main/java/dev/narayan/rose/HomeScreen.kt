package dev.narayan.rose

import androidx.compose.runtime.Composable
import dev.narayan.rose.ui.icons.AppIcons
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.ui.explorer.*
import dev.narayan.rose.ui.home.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: RoseViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenPath: (String, FileItem?) -> Unit,
    onOpenQuickAccess: ((String) -> Unit)? = null,
    onOpenCategory: (FileType, String) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenFavourites: () -> Unit = {},
    onOpenFtpServer: () -> Unit = {},
    onFileClick: (FileItem) -> Unit,
    onOpenWithClick: (FileItem) -> Unit,
    onShareClick: (FileItem) -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onRecycleBinClick: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit = {},
    canSwipeToRecent: Boolean = true
) {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.storage.loadStorageInfo()
        viewModel.categories.loadCategoryCounts(force = false)
        viewModel.loadRecentFiles()
        viewModel.storage.loadStorageDevices()
        viewModel.preLoadRoot()
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                (listState.isScrollInProgress || listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
            ) {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            } else null
        }.collect { pos ->
            if (pos != null) {
                viewModel.homeScrollIndex = pos.first
                viewModel.homeScrollOffset = pos.second
            }
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.homeScrollIndex != 0 || viewModel.homeScrollOffset != 0) {
            val listStateWasReset = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            if (listStateWasReset) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { count ->
                    if (count > viewModel.homeScrollIndex) {
                        listState.scrollToItem(viewModel.homeScrollIndex, viewModel.homeScrollOffset)
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }
        }
    }

    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        viewModel.hasRunEntranceAnimation = true
    }

    val dividerScale by animateFloatAsState(
        targetValue = if (viewModel.hasRunDividerAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "DividerAnimation"
    )

    LaunchedEffect(Unit) {
        if (!viewModel.hasRunDividerAnimation) {
            kotlinx.coroutines.delay(100)
            viewModel.hasRunDividerAnimation = true
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(isSearching) {
        onSearchActiveChange(isSearching)
    }

    LaunchedEffect(Unit) {
        viewModel.categories.exitCategoryMode()
        viewModel.resetFiles()
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.categories.exitCategoryMode()
                viewModel.resetFiles()
                viewModel.storage.loadStorageInfo()
                viewModel.categories.loadCategoryCounts(force = true)
                viewModel.loadRecentFiles()
                viewModel.storage.loadStorageDevices()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10000)
            if (!isSearching) {
                viewModel.loadRecentFiles()
                viewModel.categories.loadCategoryCounts(force = true)
                viewModel.storage.loadStorageInfo()
            }
        }
    }
    var searchQuery by remember { mutableStateOf("") }

    BackHandler(enabled = isSearching) {
        isSearching = false
        searchQuery = ""
        viewModel.searchFiles(query = "", filterType = null, bucketId = null, scopePath = null)
        viewModel.search.activeSearchFilter = SearchFilter.ALL
    }

    var fileForRename by remember { mutableStateOf<FileItem?>(null) }
    var fileForDelete by remember { mutableStateOf<FileItem?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(shadowElevation = 0.dp) {
                Column {
                    if (isSearching) {
                        SearchTopBar(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                viewModel.searchFiles(query = it, filterType = null, bucketId = null, scopePath = null)
                            },
                            onBack = {
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles(query = "", filterType = null, bucketId = null, scopePath = null)
                                viewModel.search.activeSearchFilter = SearchFilter.ALL
                            }
                        )
                        SearchFilterChipsRow(
                            selectedFilter = viewModel.search.activeSearchFilter,
                            onFilterSelected = { viewModel.search.activeSearchFilter = it },
                            counts = viewModel.search.searchFilterCounts
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_launcher_foreground_base),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { isSearching = true }) {
                                    Icon(AppIcons.Search, contentDescription = stringResource(R.string.content_desc_search))
                                }
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.content_desc_more_options))
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        shape = RoundedCornerShape(16.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_settings), modifier = Modifier.padding(vertical = 4.dp)) },
                                            onClick = { showMenu = false; onSettingsClick() },
                                            leadingIcon = { Icon(AppIcons.Settings, null) },
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_about), modifier = Modifier.padding(vertical = 4.dp)) },
                                            onClick = { showMenu = false; onAboutClick() },
                                            leadingIcon = { Icon(AppIcons.Info, null) },
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .graphicsLayer(scaleX = dividerScale, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                }
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
                                    .alpha(0.5f)
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
                                    .alpha(0.5f)
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            if (isSearching && searchQuery.isNotEmpty()) {
                val currentSearchResults = viewModel.search.filteredSearchResults
                item(key = "search_spacer") { Spacer(modifier = Modifier.height(4.dp)) }

                if (currentSearchResults.isEmpty()) {
                    if (viewModel.search.isRecursiveSearching) {
                        item(key = "search_loading") {
                            SearchLoadingView(
                                query = searchQuery,
                                categoryName = if (viewModel.search.activeSearchFilter != SearchFilter.ALL) viewModel.search.activeSearchFilter.label else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 48.dp, bottom = 32.dp)
                            )
                        }
                    } else {
                        item(key = "no_results") {
                            SearchEmptyStateView(
                                query = searchQuery,
                                filter = viewModel.search.activeSearchFilter,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 48.dp, bottom = 32.dp)
                            )
                        }
                    }
                } else {
                    itemsIndexed(currentSearchResults, key = { _, item -> item.file.absolutePath }) { index, item ->
                        SearchResultItem(
                            item = item,
                            modifier = Modifier.animateItem(),
                            onClick = {
                                if (item.isDirectory || item.fileType == FileType.ZIP) {
                                    onOpenPath(item.file.absolutePath, null)
                                } else {
                                    onFileClick(item)
                                }
                            },
                            onOpenLocation = { onOpenPath(item.file.parent ?: item.file.absolutePath, item) },
                            onCopy = {
                                viewModel.copySingle(item)
                                coroutineScope.launch {
                                    kotlinx.coroutines.yield()
                                    onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                }
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles("")
                                viewModel.searchFiles(query = "", filterType = null, bucketId = null, scopePath = null)
                                viewModel.search.activeSearchFilter = SearchFilter.ALL
                            },
                            onCut = {
                                viewModel.moveSingle(item)
                                coroutineScope.launch {
                                    kotlinx.coroutines.yield()
                                    onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                }
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles("")
                                viewModel.searchFiles(query = "", filterType = null, bucketId = null, scopePath = null)
                                viewModel.search.activeSearchFilter = SearchFilter.ALL
                            },
                            onExtract = {
                                viewModel.prepareExtraction(item.file)
                                android.widget.Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onOpenArchive = { onOpenPath(item.file.absolutePath, null) },
                            viewModel = viewModel,
                            onShare = { onShareClick(item) },
                            onDelete = { fileForDelete = item },
                            onRename = { fileForRename = item },
                            onProperties = { viewModel.showProperties(item) }
                        )
                        if (viewModel.showListDividers && index != currentSearchResults.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 12.dp),
                                thickness = 0.6.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            } else {
                item(key = "storage_capsule") {
                    Spacer(modifier = Modifier.height(16.dp))
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 12 }
                    ) {
                        StorageCapsule(
                            viewModel = viewModel,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onClick = { onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null) }
                        )
                    }
                }

                item(key = "category_grid") {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(300, delayMillis = 50)) + slideInVertically(tween(300, delayMillis = 50)) { it / 12 }
                    ) {
                        CategoryGrid(
                            counts = viewModel.categories.categoryCounts,
                            favouriteCount = viewModel.favourites.favouriteFiles.size,
                            isLoading = viewModel.categories.isCategoryCountsLoading,
                            hasRunCountAnimation = viewModel.hasRunCategoryCountAnimation,
                            onAnimationFinished = { viewModel.hasRunCategoryCountAnimation = true },
                            onCategoryClick = { category ->
                                val now = System.currentTimeMillis()
                                if (now - viewModel.lastNavigationTimestamp < 200L || animatedVisibilityScope.transition.isRunning) {
                                    return@CategoryGrid
                                }
                                viewModel.lastNavigationTimestamp = now
                                viewModel.hasRunCategoryCountAnimation = true
                                if (category.isFavourites) {
                                    onOpenFavourites()
                                } else if (category.isFtp) {
                                    onOpenFtpServer()
                                } else if (category.type != null) {
                                    onOpenCategory(category.type, context.getString(category.labelRes))
                                }
                            },
                            onOpenRecent = onOpenRecent,
                            canSwipeToRecent = canSwipeToRecent,
                            categoryPageIndex = viewModel.categoryPageIndex,
                            onCategoryPageChange = { viewModel.categoryPageIndex = it },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                }

                if (viewModel.showRecentFiles) {
                    item(key = "recent_files") {
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(300, delayMillis = 100)) + slideInVertically(tween(300, delayMillis = 100)) { it / 12 }
                        ) {
                            RecentFilesSection(
                                viewModel = viewModel,
                                recentFiles = viewModel.recentFiles,
                                showDividers = viewModel.showListDividers,
                                onOpenRecent = onOpenRecent,
                                onOpenPath = onOpenPath,
                                onFileClick = onFileClick,
                                onOpenWithClick = onOpenWithClick,
                                onShareClick = onShareClick,
                                onRenameClick = { fileForRename = it },
                                onDeleteClick = { fileForDelete = it },
                                onPropertiesClick = { viewModel.showProperties(it) },
                                onExtractClick = {
                                    viewModel.prepareExtraction(it.file)
                                    android.widget.Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }

                item(key = "quick_access") {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(300, delayMillis = 130)) + slideInVertically(tween(300, delayMillis = 130)) { it / 12 }
                    ) {
                        QuickAccessSection(
                            viewModel = viewModel,
                            onOpenPath = { path ->
                                if (onOpenQuickAccess != null) {
                                    onOpenQuickAccess(path)
                                } else {
                                    onOpenPath(path, null)
                                }
                            }
                        )
                    }
                }

                if (viewModel.showExternalStorage) {
                    item(key = "external_storage") {
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(300, delayMillis = 160)) + slideInVertically(tween(300, delayMillis = 160)) { it / 12 }
                        ) {
                            StorageSection(
                                viewModel = viewModel,
                                onOpenPath = { path -> onOpenPath(path, null) }
                            )
                        }
                    }
                }

                item(key = "recycle_bin") {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(300, delayMillis = 190)) + slideInVertically(tween(300, delayMillis = 190)) { it / 12 }
                    ) {
                        RecycleBinSection(onClick = onRecycleBinClick)
                    }
                }

            }
        }
    }

    viewModel.propertiesFile?.let { item ->
        PropertiesDialog(
            fileItem = item,
            onDismiss = { viewModel.closeProperties() },
            viewModel = viewModel,
            onOpenLocation = { targetPath, targetItem ->
                viewModel.closeProperties()
                onOpenPath(targetPath, targetItem)
            }
        )
    }

    fileForRename?.let { item ->
        RenameDialog(
            initialName = item.name,
            onDismiss = { fileForRename = null },
            onRename = { newName ->
                viewModel.renameFile(item, newName)
                fileForRename = null
            }
        )
    }

    fileForDelete?.let { item ->
        var permanently by remember { mutableStateOf(!viewModel.useRecycleBin) }
        AlertDialog(
            onDismissRequest = { fileForDelete = null },
            icon = {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (permanently) AppIcons.DeleteForever else AppIcons.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val titleText = if (item.name.length <= 26) {
                        stringResource(R.string.delete_single_confirm, item.name)
                    } else if (item.isDirectory) {
                        stringResource(R.string.delete_folder_title)
                    } else {
                        stringResource(R.string.delete_file_title)
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (item.name.length > 26) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    val statusText = if (viewModel.useRecycleBin && !permanently) {
                        stringResource(R.string.delete_moved_to_bin_short)
                    } else {
                        stringResource(R.string.delete_cannot_be_undone_short)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (viewModel.useRecycleBin) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            onClick = { permanently = !permanently },
                            shape = RoundedCornerShape(12.dp),
                            color = if (permanently) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Checkbox(
                                    checked = permanently,
                                    onCheckedChange = { permanently = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.error,
                                        checkmarkColor = MaterialTheme.colorScheme.onError
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.delete_permanently),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (permanently) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (permanently) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFile(item, permanently)
                        fileForDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = if (permanently) AppIcons.DeleteForever else AppIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.action_delete),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { fileForDelete = null },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}
