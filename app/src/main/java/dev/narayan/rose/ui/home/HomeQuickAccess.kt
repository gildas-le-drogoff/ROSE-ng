package dev.narayan.rose.ui.home

import android.os.Environment
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.ui.explorer.*
import dev.narayan.rose.ui.icons.AppIcons
import java.io.File

internal val folderIconFallbackColor = Color(0xFF4C8DFF)

internal data class QuickItem(
    val id: String,
    val label: String,
    @DrawableRes val iconRes: Int,
    val path: String,
    val custom: Boolean,
    @StringRes val labelRes: Int? = null
) {
    val icon: ImageVector @Composable get() = ImageVector.vectorResource(iconRes)
}

internal val defaultQuickAccessFolders = listOf(
    Triple("downloads", Environment.DIRECTORY_DOWNLOADS, R.string.category_downloads),
    Triple("camera", Environment.DIRECTORY_DCIM, R.string.folder_camera),
    Triple("documents", Environment.DIRECTORY_DOCUMENTS, R.string.category_documents)
)

internal val defaultQuickAccessIcons: Map<String, Int> = mapOf(
    "downloads" to R.drawable.ic_download,
    "camera" to R.drawable.ic_photo_camera_fill,
    "documents" to R.drawable.ic_description_fill
)

internal fun getQuickAccessFolderType(item: QuickItem): String? {
    if (item.id == "downloads" || item.id == "camera" || item.id == "documents") {
        return item.id
    }
    val path = item.path.trimEnd('/', '\\')
    val downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath.trimEnd('/', '\\')
    val dcimPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath.trimEnd('/', '\\')
    val documentsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath.trimEnd('/', '\\')
    val name = File(item.path).name

    return when {
        path.equals(downloadsPath, ignoreCase = true) || name.equals("Download", ignoreCase = true) || name.equals("Downloads", ignoreCase = true) -> "downloads"
        path.equals(dcimPath, ignoreCase = true) || path.endsWith("/DCIM/Camera", ignoreCase = true) || name.equals("Camera", ignoreCase = true) -> "camera"
        path.equals(documentsPath, ignoreCase = true) || name.equals("Documents", ignoreCase = true) -> "documents"
        else -> null
    }
}

@Composable
internal fun QuickAccessItemIcon(item: QuickItem) {
    val folderType = getQuickAccessFolderType(item)
    val symbol = when (folderType) {
        "downloads" -> AppIcons.Download
        "camera" -> AppIcons.CameraAlt
        "documents" -> AppIcons.Description
        else -> null
    }
    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = AppIcons.Folder,
            contentDescription = null,
            tint = folderIconFallbackColor,
            modifier = Modifier.fillMaxSize()
        )
        if (symbol != null) {
            Icon(
                imageVector = symbol,
                contentDescription = null,
                tint = Color(0xFF1976D2).copy(alpha = 0.85f),
                modifier = Modifier
                    .size(32.dp * 0.42f)
                    .offset(y = 32.dp * 0.06f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickAccessSection(viewModel: RoseViewModel, onOpenPath: (String) -> Unit) {
    var showAddDialog by remember { mutableStateOf(false) }
    var itemPendingRemoval by remember { mutableStateOf<QuickItem?>(null) }

    val items = remember(viewModel.favourites.quickAccessRemoved, viewModel.favourites.quickAccessCustomPaths) {
        val defaultItems = defaultQuickAccessFolders.mapNotNull { (id, dir, labelRes) ->
            if (viewModel.favourites.quickAccessRemoved.contains(id)) return@mapNotNull null
            var folder = Environment.getExternalStoragePublicDirectory(dir)
            if (id == "camera") {
                val cameraFolder = File(folder, "Camera")
                if (cameraFolder.exists()) {
                    folder = cameraFolder
                }
            }
            if (folder.exists()) QuickItem(id, "", defaultQuickAccessIcons.getValue(id), folder.absolutePath, false, labelRes) else null
        }

        val seenNormalizedPaths = defaultItems.map { viewModel.favourites.normalizePath(it.path) }.toMutableSet()
        val customItems = viewModel.favourites.quickAccessCustomPaths.mapNotNull { path ->
            val folder = File(path)
            val normalized = viewModel.favourites.normalizePath(path)
            if (folder.exists() && folder.isDirectory && seenNormalizedPaths.add(normalized)) {
                QuickItem(path, folder.name, R.drawable.ic_folder_fill, path, true)
            } else null
        }
        defaultItems + customItems
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        viewModel.setQuickAccessExpanded(!viewModel.quickAccessExpanded)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.home_quick_access),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = { viewModel.setQuickAccessExpanded(!viewModel.quickAccessExpanded) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (viewModel.quickAccessExpanded) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
                    contentDescription = if (viewModel.quickAccessExpanded) stringResource(R.string.content_desc_collapse) else stringResource(R.string.content_desc_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))

        androidx.compose.animation.AnimatedVisibility(
            visible = viewModel.quickAccessExpanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 2000.dp),
                    userScrollEnabled = false
                ) {
                    itemsIndexed(items, key = { _, item -> "${item.id}_${item.path}" }) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .combinedClickable(
                                    onClick = { onOpenPath(item.path) },
                                    onLongClick = { itemPendingRemoval = item }
                                )
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QuickAccessItemIcon(item = item)
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                item.labelRes?.let { stringResource(it) } ?: item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (viewModel.showListDividers) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAddDialog = true }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AppIcons.AddCircleOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                stringResource(R.string.action_add_folders),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                AppIcons.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        FolderPickerDialog(
            viewModel = viewModel,
            onDismiss = { showAddDialog = false },
            onFolderSelected = { path ->
                viewModel.favourites.addQuickAccessFolder(path)
                showAddDialog = false
            }
        )
    }

    itemPendingRemoval?.let { item ->
        val itemDisplayName = item.labelRes?.let { stringResource(it) } ?: item.label
        AlertDialog(
            onDismissRequest = { itemPendingRemoval = null },
            icon = {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.FolderDelete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.dialog_remove_folder_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_remove_quick_access_message, itemDisplayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (item.custom) {
                            viewModel.favourites.removeQuickAccessFolder(item.path)
                        } else {
                            viewModel.favourites.removeDefaultQuickAccess(item.id)
                        }
                        itemPendingRemoval = null
                    },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.action_remove),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { itemPendingRemoval = null },
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
