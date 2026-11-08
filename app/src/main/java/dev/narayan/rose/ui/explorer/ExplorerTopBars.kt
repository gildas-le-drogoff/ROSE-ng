package dev.narayan.rose.ui.explorer

import android.net.Uri
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.ui.icons.AppIcons
import dev.narayan.rose.ui.navigation.ExplorerView
import java.io.File
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MainTopBar(
    title: String,
    path: String?,
    archiveName: String?,
    viewModel: RoseViewModel,
    currentView: ExplorerView,
    totalItems: Int? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onSearchClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onNewFileClick: () -> Unit,
    onNewFolderClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigate: (File) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Column {
        TopAppBar(
            title = {
                val isAlbum = viewModel.categories.categoryBucketId != null
                Column {
                    if (title == stringResource(R.string.category_all_files) && sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.sharedBounds(
                                    rememberSharedContentState(key = "all_files_title"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                                )
                            )
                        }
                    } else {
                        Text(
                            title,
                            style = if (isAlbum) MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp) else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (totalItems != null) {
                        Text(
                            stringResource(R.string.total_items_in_total, totalItems),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                }
            },
            actions = {
                if (currentView != ExplorerView.Recent) {
                    IconButton(onClick = onSearchClick) { Icon(AppIcons.Search, null) }
                    IconButton(onClick = { showSortMenu = true }) { Icon(AppIcons.Sort, null) }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) { Icon(AppIcons.MoreVert, null) }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                        ) {
                            val currentCustomStorage = viewModel.storage.storageDevices.filterIsInstance<StorageDevice.DocumentTree>().firstOrNull { dev ->
                                path != null && (path == dev.path || path.startsWith(dev.path) || path.replace("://", ":/").startsWith(dev.path.replace("://", ":/")))
                            }
                            if (currentCustomStorage != null) {
                                val isCurrentStorageLocked = viewModel.storage.isStorageLocked(currentCustomStorage.path) || currentCustomStorage.isLocked
                                val isGdriveOrCloud = currentCustomStorage.storageType == CustomStorageType.GOOGLE_DRIVE || currentCustomStorage.isCloud || isCurrentStorageLocked
                                if (isGdriveOrCloud) {
                                    DropdownMenuItem(
                                        text = { Text(if (isCurrentStorageLocked) "Remove Fingerprint Lock" else "Lock This Folder", modifier = Modifier.padding(vertical = 4.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            val activity = context as? androidx.fragment.app.FragmentActivity
                                            if (activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                                                BiometricSecurityManager.authenticate(
                                                    activity = activity,
                                                    title = if (isCurrentStorageLocked) "Unlock ${currentCustomStorage.name}" else "Lock ${currentCustomStorage.name}",
                                                    subtitle = "Authenticate fingerprint to confirm",
                                                    onSuccess = {
                                                        viewModel.storage.setStorageLocked(currentCustomStorage.path, !isCurrentStorageLocked)
                                                        Toast.makeText(
                                                            context,
                                                            if (isCurrentStorageLocked) "Fingerprint lock removed" else "Folder locked with fingerprint",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            } else {
                                                viewModel.storage.setStorageLocked(currentCustomStorage.path, !isCurrentStorageLocked)
                                                Toast.makeText(
                                                    context,
                                                    if (isCurrentStorageLocked) "Lock removed" else "Folder locked",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (isCurrentStorageLocked) AppIcons.LockOpen else AppIcons.Fingerprint,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                }
                            }

                            if (currentView != ExplorerView.Category && viewModel.currentZipFile == null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_new_file), modifier = Modifier.padding(vertical = 4.dp)) },
                                    onClick = { onNewFileClick(); showMoreMenu = false },
                                    leadingIcon = { Icon(AppIcons.NoteAdd, null) },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_new_folder), modifier = Modifier.padding(vertical = 4.dp)) },
                                    onClick = { onNewFolderClick(); showMoreMenu = false },
                                    leadingIcon = { Icon(AppIcons.CreateNewFolder, null) },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            }
                            DropdownMenuItem(
                                text = { Text(if (viewModel.showHiddenFiles) stringResource(R.string.action_hide_hidden_files) else stringResource(R.string.action_show_hidden_files), modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { viewModel.toggleHiddenFiles(); showMoreMenu = false },
                                leadingIcon = { Icon(if (viewModel.showHiddenFiles) AppIcons.VisibilityOff else AppIcons.Visibility, null) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_refresh), modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { onRefreshClick(); showMoreMenu = false },
                                leadingIcon = { Icon(AppIcons.Refresh, null) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_settings), modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { onSettingsClick(); showMoreMenu = false },
                                leadingIcon = { Icon(AppIcons.Settings, null) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        SortMenu(expanded = showSortMenu, viewModel = viewModel, currentView = currentView, onDismiss = { showSortMenu = false })
                    }
                }
            }
        )
        if (path != null) {
            val breadcrumbPath = if (archiveName != null) "$path > [$archiveName]" else path
            Breadcrumbs(
                path = breadcrumbPath,
                storageDevices = viewModel.storage.storageDevices,
                viewModel = viewModel,
                onNavigate = onNavigate
            )
        }
    }
}

@Composable
fun Breadcrumbs(
    path: String,
    storageDevices: List<StorageDevice> = emptyList(),
    viewModel: RoseViewModel? = null,
    onNavigate: (File) -> Unit
) {
    val context = LocalContext.current
    val internalRootPath = Environment.getExternalStorageDirectory().absolutePath
    val internalStorageLabel = stringResource(R.string.breadcrumbs_internal_storage)
    val rootLabel = stringResource(R.string.breadcrumbs_root)
    val isInternal = path == internalRootPath || path.startsWith("$internalRootPath/")
    val isRootBrowsing = !isInternal && (path == "/" || dev.narayan.rose.RootManager.isRootPath(path))

    val isSaf = SafManager.isSafUri(path)
    val knownTreeDevice = if (!isInternal && !isRootBrowsing) {
        storageDevices.filterIsInstance<StorageDevice.DocumentTree>().firstOrNull { dev ->
            val devClean = dev.path.replace("://", ":/").trimEnd('/')
            val pathClean = path.replace("://", ":/").trimEnd('/')
            path == dev.path || path.startsWith(dev.path) || pathClean == devClean || pathClean.startsWith("$devClean/")
        }
    } else null

    val knownPhysicalDevice = if (!isInternal && !isRootBrowsing && knownTreeDevice == null) {
        storageDevices.filterIsInstance<StorageDevice.Physical>().firstOrNull { dev ->
            dev.isSdCard && (path == dev.path || path.startsWith("${dev.path}/"))
        }
    } else null

    val storageVolumeRoot = if (!isInternal && !isRootBrowsing && knownTreeDevice == null && knownPhysicalDevice == null && path.startsWith("/storage/")) {
        val segment = path.removePrefix("/storage/").substringBefore('/')
        if (segment.isNotEmpty() && segment != "emulated" && segment != "self") {
            "/storage/$segment"
        } else null
    } else null

    val (activeStorageRootPath, activeStorageLabel) = when {
        isInternal -> internalRootPath to internalStorageLabel
        isRootBrowsing -> "/" to rootLabel
        knownTreeDevice != null -> knownTreeDevice.path to knownTreeDevice.name
        knownPhysicalDevice != null -> knownPhysicalDevice.path to knownPhysicalDevice.name
        storageVolumeRoot != null -> storageVolumeRoot to File(storageVolumeRoot).name
        isSaf -> path to (Uri.parse(path).lastPathSegment ?: "Drive")
        else -> "" to ""
    }

    val relativePath = when {
        isInternal -> internalStorageLabel + path.removePrefix(internalRootPath)
        isRootBrowsing -> if (path == "/") rootLabel else "$rootLabel$path"
        knownTreeDevice != null -> {
            val devClean = knownTreeDevice.path.replace("://", ":/").trimEnd('/')
            val pathClean = path.replace("://", ":/").trimEnd('/')
            if (path == knownTreeDevice.path || pathClean == devClean) {
                knownTreeDevice.name
            } else {
                val subPath = pathClean.removePrefix(devClean).trimStart('/')
                "${knownTreeDevice.name}/$subPath"
            }
        }
        knownPhysicalDevice != null -> activeStorageLabel + path.removePrefix(knownPhysicalDevice.path)
        storageVolumeRoot != null -> activeStorageLabel + path.removePrefix(storageVolumeRoot)
        isSaf -> activeStorageLabel
        else -> path
    }

    val parts = relativePath.split("/").filter { it.isNotEmpty() }
    val scrollState = rememberScrollState()
    var showStorageDropdown by remember { mutableStateOf(false) }

    val hasMultipleStorages = storageDevices.size > 1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEachIndexed { index, part ->
            if (index == 0 && hasMultipleStorages) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showStorageDropdown = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = part,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (parts.size == 1) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (parts.size == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                        )
                        val isCurrentTreeLocked = knownTreeDevice != null && ((viewModel?.storage?.isStorageLocked(knownTreeDevice.path) == true) || knownTreeDevice.isLocked)
                        if (isCurrentTreeLocked) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = AppIcons.Lock,
                                contentDescription = "Locked with fingerprint",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            AppIcons.ArrowDropDown,
                            contentDescription = "Switch storage",
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(if (showStorageDropdown) 180f else 0f),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    DropdownMenu(
                        expanded = showStorageDropdown,
                        onDismissRequest = { showStorageDropdown = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)
                    ) {
                        storageDevices.forEachIndexed { devIndex, dev ->
                            val isCurrent = when (dev) {
                                is StorageDevice.Root -> isRootBrowsing
                                is StorageDevice.DocumentTree -> path == dev.path || path.startsWith(dev.path)
                                is StorageDevice.Physical -> {
                                    if (dev.isSdCard) {
                                        path == dev.path || path.startsWith("${dev.path}/") ||
                                                (storageVolumeRoot != null && dev.path == storageVolumeRoot)
                                    } else {
                                        isInternal
                                    }
                                }
                            }
                            val isDevLocked = (viewModel?.storage?.isStorageLocked(dev.path) == true) || dev.isLocked
                            val isRootDev = dev is StorageDevice.Root
                            val isSd = dev is StorageDevice.Physical && dev.isSdCard
                            val isUsb = dev is StorageDevice.Physical && dev.isUsb
                            val isDocTree = dev is StorageDevice.DocumentTree
                            val devIcon = when {
                                isRootDev -> AppIcons.Tag
                                isUsb -> AppIcons.Usb
                                isSd -> AppIcons.SdCard
                                isDocTree -> if (dev.isCloud) AppIcons.Cloud else AppIcons.Storage
                                else -> AppIcons.PhoneAndroid
                            }
                            val devColor = when {
                                isRootDev -> Color(0xFFF59E0B)
                                isUsb -> Color(0xFF0D9488)
                                isSd -> Color(0xFF3B82F6)
                                isDocTree -> if (dev.isCloud) Color(0xFF4285F4) else Color(0xFF8B5CF6)
                                else -> MaterialTheme.colorScheme.primary
                            }
                            val devDisplayName = when {
                                isRootDev -> stringResource(R.string.storage_root)
                                isSd || isUsb -> dev.name
                                isDocTree -> dev.name
                                else -> stringResource(R.string.breadcrumbs_internal_storage)
                            }

                            DropdownMenuItem(
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(devColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(devIcon, contentDescription = null, tint = devColor, modifier = Modifier.size(18.dp))
                                    }
                                },
                                text = {
                                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = devDisplayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isCurrent) devColor else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isDevLocked) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = AppIcons.Lock,
                                                    contentDescription = "Locked with fingerprint",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }

                                            if (isRootDev) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                                                    contentColor = Color(0xFFF59E0B),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "SU",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        val subtitle = when {
                                            isRootDev -> stringResource(R.string.storage_root_desc)
                                            dev.totalBytes > 0L -> {
                                                val freeStr = Formatter.formatFileSize(context, dev.availableBytes)
                                                val totalStr = Formatter.formatFileSize(context, dev.totalBytes)
                                                stringResource(R.string.storage_free_of_total, freeStr, totalStr)
                                            }
                                            dev is StorageDevice.DocumentTree -> dev.providerName ?: if (dev.isCloud) {
                                                stringResource(R.string.storage_cloud)
                                            } else {
                                                stringResource(R.string.storage_badge_custom)
                                            }
                                            SafManager.isSafUri(dev.path) -> dev.name
                                            else -> dev.path
                                        }
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingIcon = if (isCurrent) {
                                    {
                                        Icon(
                                            AppIcons.Check,
                                            contentDescription = "Selected",
                                            tint = devColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    showStorageDropdown = false
                                    if (!isCurrent) {
                                        if (isDevLocked) {
                                            val activity = context as? androidx.fragment.app.FragmentActivity
                                            if (activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                                                BiometricSecurityManager.authenticate(
                                                    activity = activity,
                                                    title = "Unlock $devDisplayName",
                                                    subtitle = "Touch fingerprint sensor to open folder",
                                                    onSuccess = {
                                                        onNavigate(File(dev.path))
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            } else {
                                                onNavigate(File(dev.path))
                                            }
                                        } else {
                                            onNavigate(File(dev.path))
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            )
                            if (devIndex < storageDevices.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 58.dp, end = 14.dp),
                                    thickness = 0.6.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = part,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val targetPath = when {
                            index == 0 && activeStorageRootPath.isNotEmpty() -> File(activeStorageRootPath)
                            isSaf -> File(activeStorageRootPath)
                            isInternal -> {
                                val subParts = parts.subList(1, index + 1)
                                if (subParts.isEmpty()) File(internalRootPath)
                                else File(internalRootPath, subParts.joinToString("/"))
                            }
                            isRootBrowsing -> {
                                val subParts = parts.subList(1, index + 1)
                                if (subParts.isEmpty()) File("/")
                                else File("/" + subParts.joinToString("/"))
                            }
                            knownPhysicalDevice != null -> {
                                val subParts = parts.subList(1, index + 1)
                                if (subParts.isEmpty()) File(knownPhysicalDevice.path)
                                else File(knownPhysicalDevice.path, subParts.joinToString("/"))
                            }
                            storageVolumeRoot != null -> {
                                val subParts = parts.subList(1, index + 1)
                                if (subParts.isEmpty()) File(storageVolumeRoot)
                                else File(storageVolumeRoot, subParts.joinToString("/"))
                            }
                            else -> File("/" + parts.subList(0, index + 1).joinToString("/"))
                        }
                        onNavigate(targetPath)
                    }
                )
            }
            if (index < parts.lastIndex) {
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(horizontal = 4.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    LaunchedEffect(parts.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    viewModel: RoseViewModel,
    isAllSelected: Boolean,
    onSelectAllClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.selection_count, viewModel.selectedFiles.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        },
        navigationIcon = {
            IconButton(onClick = { viewModel.exitSelectionMode() }) {
                Icon(AppIcons.Close, null)
            }
        },
        actions = {
            IconButton(onClick = onSelectAllClick) {
                Icon(
                    imageVector = if (isAllSelected) AppIcons.Deselect else AppIcons.Checklist,
                    contentDescription = if (isAllSelected) stringResource(R.string.action_unselect_all) else stringResource(R.string.action_select_all)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onBack: () -> Unit) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_files_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        },
        navigationIcon = {
            IconButton(onClick = { onBack() }) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

@Composable
fun SortMenu(expanded: Boolean, viewModel: RoseViewModel, currentView: ExplorerView, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
    ) {
        SortMenuItem(stringResource(R.string.sort_name), RoseViewModel.SortBy.NAME, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem(stringResource(R.string.sort_type), RoseViewModel.SortBy.TYPE, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem(stringResource(R.string.sort_size), RoseViewModel.SortBy.SIZE, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem(stringResource(R.string.sort_last_modified), RoseViewModel.SortBy.DATE, viewModel)

        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))

        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.sort_folders_first),
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            onClick = {
                viewModel.setFoldersFirst(!viewModel.foldersFirst)
            },
            leadingIcon = {
                Icon(
                    AppIcons.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (viewModel.foldersFirst) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                Switch(
                    checked = viewModel.foldersFirst,
                    onCheckedChange = { viewModel.setFoldersFirst(it) },
                    modifier = Modifier.scale(0.8f)
                )
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))

        val isGridView = if (currentView.usesCategoryGrid) viewModel.isCategoryGridView else viewModel.isGridView

        DropdownMenuItem(
            text = { Text(if (isGridView) stringResource(R.string.view_list) else stringResource(R.string.view_grid)) },
            onClick = {
                if (currentView.usesCategoryGrid) {
                    viewModel.setCategoryGridView(!isGridView)
                } else {
                    viewModel.setGridView(!isGridView)
                }
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    if (isGridView) AppIcons.ViewList else AppIcons.ViewModule,
                    contentDescription = null
                )
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun SortMenuItem(label: String, sortBy: RoseViewModel.SortBy, viewModel: RoseViewModel) {
    val isSelected = viewModel.sortBy == sortBy
    DropdownMenuItem(
        text = { Text(label, modifier = Modifier.padding(vertical = 4.dp)) },
        onClick = { viewModel.setSortOrder(sortBy) },
        trailingIcon = {
            if (isSelected) {
                Icon(
                    if (viewModel.sortOrder == RoseViewModel.SortOrder.ASCENDING) AppIcons.ArrowUpward else AppIcons.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    )
}
