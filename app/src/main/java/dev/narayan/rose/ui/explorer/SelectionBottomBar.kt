package dev.narayan.rose.ui.explorer

import android.os.Environment
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.ui.icons.AppIcons
import dev.narayan.rose.ui.navigation.ExplorerView
import java.util.*
import kotlinx.coroutines.launch

@Composable
fun SelectionBottomBar(
    viewModel: RoseViewModel,
    currentView: ExplorerView,
    onShare: () -> Unit,
    onDeleteClick: () -> Unit,
    onCompressClick: () -> Unit,
    onRenameClick: () -> Unit,
    onExtractClick: () -> Unit,
    isAllSelected: Boolean,
    onSelectAllClick: () -> Unit,
    onOpenArchiveClick: ((FileItem) -> Unit)? = null,
    onOpenPath: ((String, FileItem?) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
        ) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionBottomBarItem(
                    icon = AppIcons.ShareOutlined,
                    label = stringResource(R.string.action_share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
                SelectionBottomBarItem(
                    icon = AppIcons.DriveFileMoveOutlined,
                    label = stringResource(R.string.action_move),
                    onClick = {
                        val isVirtual = currentView.isVirtual
                        viewModel.moveSelected()
                        if (isVirtual) {
                            coroutineScope.launch {
                                kotlinx.coroutines.yield()
                                onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = viewModel.currentZipFile == null
                )
                SelectionBottomBarItem(
                    icon = AppIcons.DeleteOutline,
                    label = stringResource(R.string.action_delete),
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(1f),
                    enabled = viewModel.currentZipFile == null
                )
                SelectionBottomBarItem(
                    icon = AppIcons.ContentCopyOutlined,
                    label = stringResource(R.string.action_copy),
                    onClick = {
                        val isVirtual = currentView.isVirtual
                        viewModel.copySelected()
                        if (isVirtual) {
                            coroutineScope.launch {
                                kotlinx.coroutines.yield()
                                onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                var showMoreMenu by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    SelectionBottomBarItem(
                        icon = AppIcons.MoreVert,
                        label = stringResource(R.string.action_more),
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (showMoreMenu) {
                        val density = LocalDensity.current
                        val gapPx = with(density) { 8.dp.roundToPx() }
                        val endMarginPx = with(density) { 8.dp.roundToPx() }
                        val popupPositionProvider = remember(gapPx, endMarginPx) {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize
                                ): androidx.compose.ui.unit.IntOffset {
                                    val x = (windowSize.width - popupContentSize.width - endMarginPx).coerceAtLeast(0)
                                    val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        }
                        androidx.compose.ui.window.Popup(
                            popupPositionProvider = popupPositionProvider,
                            onDismissRequest = { showMoreMenu = false },
                            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 3.dp,
                                shadowElevation = 8.dp,
                                modifier = Modifier.width(IntrinsicSize.Max)
                            ) {
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    if (currentView != ExplorerView.Recent && viewModel.currentZipFile == null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_compress)) },
                                            onClick = {
                                                onCompressClick()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = { Icon(AppIcons.Archive, null) }
                                        )
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (isAllSelected) stringResource(R.string.action_unselect_all) else stringResource(R.string.action_select_all)) },
                                        onClick = {
                                            onSelectAllClick()
                                            showMoreMenu = false
                                        },
                                        leadingIcon = { Icon(if (isAllSelected) AppIcons.Deselect else AppIcons.Checklist, null) }
                                    )
                                    if (viewModel.selectedFiles.size == 1 && viewModel.currentZipFile == null) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_rename)) },
                                            onClick = {
                                                onRenameClick()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = { Icon(AppIcons.Edit, null) }
                                        )
                                    }
                                    val fileOnlySelected = viewModel.selectedFiles.filter { !it.isDirectory && !viewModel.storage.isCloudStorage(it.file.absolutePath) }
                                    if (fileOnlySelected.isNotEmpty() && viewModel.currentZipFile == null) {
                                        val allFav = fileOnlySelected.all { viewModel.favourites.isFileInFavourites(it.file.absolutePath) }
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (allFav) stringResource(R.string.action_remove_from_favourites)
                                                    else stringResource(R.string.action_add_to_favourites)
                                                )
                                            },
                                            onClick = {
                                                if (allFav) {
                                                    viewModel.favourites.removeFavourites(fileOnlySelected)
                                                    Toast.makeText(context, context.getString(R.string.toast_removed_from_favourites), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    viewModel.favourites.addFavourites(fileOnlySelected)
                                                    Toast.makeText(context, context.getString(R.string.toast_added_to_favourites), Toast.LENGTH_SHORT).show()
                                                }
                                                viewModel.exitSelectionMode()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (allFav) AppIcons.Favorite else AppIcons.FavoriteBorder,
                                                    null,
                                                    tint = Color(0xFFE11D48)
                                                )
                                            }
                                        )
                                    }
                                    val savableOfflineFiles = fileOnlySelected.filter {
                                        !it.isDirectory && viewModel.storage.isCloudStorage(it.file.absolutePath)
                                    }
                                    if (savableOfflineFiles.isNotEmpty() && viewModel.currentZipFile == null) {
                                        val allOffline = savableOfflineFiles.all { viewModel.isFileOffline(it.file.absolutePath) }
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (allOffline) stringResource(R.string.action_remove_offline)
                                                    else stringResource(R.string.action_save_offline)
                                                )
                                            },
                                            onClick = {
                                                savableOfflineFiles.forEach { viewModel.toggleOfflineStatus(it) }
                                                val msg = if (allOffline) {
                                                    context.getString(R.string.toast_removed_from_offline)
                                                } else {
                                                    context.getString(R.string.toast_saving_offline)
                                                }
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                viewModel.exitSelectionMode()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (allOffline) AppIcons.CloudDone else AppIcons.Download,
                                                    null,
                                                    tint = if (allOffline) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        )
                                    }
                                    if (viewModel.selectedFiles.size == 1) {
                                        val fileItem = viewModel.selectedFiles.first()
                                        if (fileItem.isDirectory && viewModel.currentZipFile == null) {
                                            val isAlreadyInQuickAccess = viewModel.favourites.isFolderInQuickAccess(fileItem.file.absolutePath)
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (isAlreadyInQuickAccess) stringResource(R.string.action_already_in_quick_access)
                                                        else stringResource(R.string.action_add_to_quick_access)
                                                    )
                                                },
                                                enabled = !isAlreadyInQuickAccess,
                                                onClick = {
                                                    if (!isAlreadyInQuickAccess) {
                                                        viewModel.favourites.addQuickAccessFolder(fileItem.file.absolutePath)
                                                        Toast.makeText(context, context.getString(R.string.toast_added_to_quick_access), Toast.LENGTH_SHORT).show()
                                                    }
                                                    viewModel.exitSelectionMode()
                                                    showMoreMenu = false
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        if (isAlreadyInQuickAccess) AppIcons.Check else AppIcons.Add,
                                                        null
                                                    )
                                                }
                                            )
                                        }
                                        if (fileItem.fileType == FileType.ZIP && viewModel.currentZipFile == null) {
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_extract)) },
                                                onClick = {
                                                    onExtractClick()
                                                    showMoreMenu = false
                                                },
                                                leadingIcon = { Icon(AppIcons.UnarchiveOutlined, null) }
                                            )
                                        }
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_properties)) },
                                            onClick = {
                                                viewModel.showProperties(fileItem)
                                                viewModel.exitSelectionMode()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = { Icon(AppIcons.Info, null) }
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
}

@Composable
fun SelectionBottomBarItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true
) {
    val finalTint = if (enabled) tint else tint.copy(alpha = 0.38f)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = finalTint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = finalTint
        )
    }
}
