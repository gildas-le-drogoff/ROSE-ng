package dev.narayan.rose.ui.explorer

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.ui.icons.AppIcons
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    fileItem: FileItem,
    isSelected: Boolean,
    showDetails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showExtension: Boolean = true,
    iconSize: Dp = 64.dp,
    isVirtual: Boolean = false,
    index: Int = 0,
    scrollResetKey: Any = Unit,
    hasAnimatedBefore: Boolean = true,
    onAnimationStart: () -> Unit = {},
    isHighlighted: Boolean = false,
    viewModel: RoseViewModel? = null
) {
    val animatedProgress = remember(scrollResetKey, fileItem.file.absolutePath) {
        Animatable(if (hasAnimatedBefore) 1f else 0f)
    }
    val density = LocalDensity.current
    LaunchedEffect(scrollResetKey, fileItem.file.absolutePath) {
        if (!hasAnimatedBefore) {
            onAnimationStart()
            val delay = (index % 8 * 12).toLong()
            kotlinx.coroutines.delay(delay)
            animatedProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing)
            )
        }
    }

    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

    val pulseAlpha by if (isHighlighted) {
        val infiniteTransition = rememberInfiniteTransition(label = "GridPulseAlphaTransition")
        infiniteTransition.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GridPulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            isSelected -> selectionColor
            isHighlighted -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
            else -> Color.Transparent
        },
        animationSpec = if (isHighlighted) snap() else tween(600, easing = FastOutSlowInEasing),
        label = "GridItemSelection"
    )

    val isHidden = fileItem.name.startsWith(".")
    val itemAlpha = if (isHidden) 0.55f else 1f

    Column(
        modifier = modifier
            .graphicsLayer {
                alpha = animatedProgress.value * itemAlpha
                translationY = with(density) { (1f - animatedProgress.value) * 40.dp.toPx() }
            }
            .padding(8.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(backgroundColor)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val vm = viewModel ?: (LocalContext.current as? androidx.activity.ComponentActivity)?.let { (it as? MainActivity)?.viewModel }
        val isFav = remember(fileItem.file.absolutePath, vm?.favourites?.favouriteFiles) {
            if (fileItem.isDirectory || vm?.storage?.isCloudStorage(fileItem.file.absolutePath) == true) false else vm?.favourites?.isFileInFavourites(fileItem.file.absolutePath) == true
        }
        Box(contentAlignment = Alignment.Center) {
            FileIcon(fileItem, iconSize = iconSize, isVirtual = isVirtual, viewModel = vm, showFavouriteBadge = isFav)
            if (isSelected) {
                Box(
                    modifier = Modifier.size(iconSize * 0.5f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.Check, null, tint = Color.White)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            displayNameFor(fileItem, showExtension),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (fileItem.isDirectory) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (showDetails) {
            if (fileItem.isDirectory) {
                val count = fileItem.itemCount ?: 0
                val text = if (vm?.showFolderSize == true) {
                    stringResource(R.string.items_count_and_size, count, formatFileSize(fileItem.size))
                } else {
                    stringResource(R.string.items_count, count)
                }
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    formatFileSize(fileItem.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun RowScope.WindowsMenuHeaderAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    enabled: Boolean = true
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(19.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun WindowsFileDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    fileItem: FileItem,
    viewModel: RoseViewModel,
    isVirtual: Boolean = false,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onProperties: () -> Unit,
    onRenameRequest: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null,
    onOpenLocation: (() -> Unit)? = null,
    onExtract: (() -> Unit)? = null,
    offset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isCloud = viewModel.storage.isCloudStorage(fileItem.file.absolutePath)
    val canSaveOffline = !isVirtual && !fileItem.isDirectory && viewModel.currentZipFile == null && isCloud

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        offset = offset,
        modifier = Modifier.widthIn(min = 220.dp, max = 250.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            WindowsMenuHeaderAction(
                icon = AppIcons.ContentCut,
                label = stringResource(R.string.action_cut),
                enabled = !isVirtual,
                onClick = {
                    onDismissRequest()
                    onCut()
                }
            )
            VerticalDivider(
                modifier = Modifier.height(22.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
            WindowsMenuHeaderAction(
                icon = AppIcons.ContentCopy,
                label = stringResource(R.string.action_copy),
                enabled = true,
                onClick = {
                    onDismissRequest()
                    onCopy()
                }
            )
            VerticalDivider(
                modifier = Modifier.height(22.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
            WindowsMenuHeaderAction(
                icon = AppIcons.Share,
                label = stringResource(R.string.action_share),
                enabled = !fileItem.isDirectory,
                onClick = {
                    onDismissRequest()
                    onShare()
                }
            )
            VerticalDivider(
                modifier = Modifier.height(22.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
            WindowsMenuHeaderAction(
                icon = AppIcons.DeleteOutline,
                label = stringResource(R.string.action_delete),
                isDestructive = true,
                enabled = !isVirtual,
                onClick = {
                    onDismissRequest()
                    onDelete()
                }
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.5.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        if (onOpenWith != null && !fileItem.isDirectory) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_with), style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    onDismissRequest()
                    onOpenWith()
                },
                leadingIcon = { Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(19.dp)) },
                trailingIcon = {
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 38.dp)
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.5.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }

        if (onOpenLocation != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_location), style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    onDismissRequest()
                    onOpenLocation()
                },
                leadingIcon = { Icon(AppIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(19.dp)) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 38.dp)
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.5.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }

        if (!isVirtual && onRenameRequest != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename), style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    onDismissRequest()
                    onRenameRequest.invoke()
                },
                leadingIcon = { Icon(AppIcons.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(19.dp)) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 38.dp)
            )
        }

        val hasMiddleGroup = (!isCloud && !isVirtual && !fileItem.isDirectory && viewModel.currentZipFile == null) ||
                canSaveOffline ||
                (fileItem.isDirectory && viewModel.currentZipFile == null && !isVirtual && !viewModel.favourites.isFolderInQuickAccess(fileItem.file.absolutePath)) ||
                ((fileItem.fileType == FileType.ZIP || isVirtual) && !isVirtual && onExtract != null)

        if (hasMiddleGroup) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.5.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            if (!isCloud && !isVirtual && !fileItem.isDirectory && viewModel.currentZipFile == null) {
                val isFav = viewModel.favourites.isFileInFavourites(fileItem.file.absolutePath)
                DropdownMenuItem(
                    text = {
                        Text(
                            if (isFav) stringResource(R.string.action_remove_from_favourites)
                            else stringResource(R.string.action_add_to_favourites),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onDismissRequest()
                        if (isFav) {
                            viewModel.favourites.removeFavouriteFile(fileItem.file.absolutePath)
                            Toast.makeText(context, context.getString(R.string.toast_removed_from_favourites), Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.favourites.addFavouriteFile(fileItem.file.absolutePath)
                            Toast.makeText(context, context.getString(R.string.toast_added_to_favourites), Toast.LENGTH_SHORT).show()
                        }
                    },
                    leadingIcon = {
                        Icon(
                            if (isFav) AppIcons.Star else AppIcons.StarOutline,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = if (isFav) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 38.dp)
                )
            }

            if (canSaveOffline) {
                val isOffline = viewModel.isFileOffline(fileItem.file.absolutePath)
                DropdownMenuItem(
                    text = {
                        Text(
                            if (isOffline) stringResource(R.string.action_remove_offline)
                            else stringResource(R.string.action_save_offline),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onDismissRequest()
                        viewModel.toggleOfflineStatus(fileItem)
                        val msg = if (isOffline) {
                            context.getString(R.string.toast_removed_from_offline)
                        } else {
                            context.getString(R.string.toast_saving_offline)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    leadingIcon = {
                        Icon(
                            if (isOffline) AppIcons.CloudDone else AppIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = if (isOffline) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 38.dp)
                )
            }

            if (fileItem.isDirectory && viewModel.currentZipFile == null && !isVirtual) {
                val isAlreadyInQuickAccess = viewModel.favourites.isFolderInQuickAccess(fileItem.file.absolutePath)
                if (!isAlreadyInQuickAccess) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_add_to_quick_access), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            onDismissRequest()
                            viewModel.favourites.addQuickAccessFolder(fileItem.file.absolutePath)
                            Toast.makeText(context, context.getString(R.string.toast_added_to_quick_access), Toast.LENGTH_SHORT).show()
                        },
                        leadingIcon = { Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(19.dp)) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 38.dp)
                    )
                }
            }

            if ((fileItem.fileType == FileType.ZIP || isVirtual) && !isVirtual && onExtract != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_extract), style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onDismissRequest()
                        onExtract()
                    },
                    leadingIcon = { Icon(AppIcons.UnarchiveOutlined, contentDescription = null, modifier = Modifier.size(19.dp)) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 38.dp)
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.5.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_properties), style = MaterialTheme.typography.bodyMedium) },
            onClick = {
                onDismissRequest()
                onProperties()
            },
            leadingIcon = { Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.size(19.dp)) },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 38.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    fileItem: FileItem,
    isSelected: Boolean,
    showDetails: Boolean,
    isVirtual: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onRenameRequest: () -> Unit = {},
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onProperties: () -> Unit,
    viewModel: RoseViewModel,
    modifier: Modifier = Modifier,
    showExtension: Boolean = true,
    showListDividers: Boolean = true,
    clipboardHasFiles: Boolean = false,
    isSelectionMode: Boolean = false,
    isHighlighted: Boolean = false,
    onOpenWith: (() -> Unit)? = null,
    onOpenLocation: (() -> Unit)? = null,
    onExtract: (() -> Unit)? = null,
    onOpenArchive: (() -> Unit)? = null,
    index: Int = 0,
    scrollResetKey: Any = Unit,
    hasAnimatedBefore: Boolean = true,
    onAnimationStart: () -> Unit = {},
    dragSelectState: CheckboxDragSelectState? = null,
    isDividerVisible: Boolean = false
) {
    val animatedProgress = remember(scrollResetKey, fileItem.file.absolutePath) {
        Animatable(if (hasAnimatedBefore) 1f else 0f)
    }
    val density = LocalDensity.current
    LaunchedEffect(scrollResetKey, fileItem.file.absolutePath) {
        if (!hasAnimatedBefore) {
            onAnimationStart()
            val delay = (index % 8 * 12).toLong()
            kotlinx.coroutines.delay(delay)
            animatedProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing)
            )
        }
    }

    val context = LocalContext.current

    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

    val pulseAlpha by if (isHighlighted) {
        val infiniteTransition = rememberInfiniteTransition(label = "ItemPulseAlphaTransition")
        infiniteTransition.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ItemPulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            isSelected -> selectionColor
            isHighlighted -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
            else -> Color.Transparent
        },
        animationSpec = if (isHighlighted) snap() else tween(600, easing = FastOutSlowInEasing),
        label = "ItemHighlight"
    )

    val isHidden = fileItem.name.startsWith(".")
    val itemAlpha = if (isHidden) 0.55f else 1f

    Column(
        modifier = modifier
            .graphicsLayer {
                alpha = animatedProgress.value * itemAlpha
                translationY = with(density) { (1f - animatedProgress.value) * 40.dp.toPx() }
            }
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayNameFor(fileItem, showExtension),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (fileItem.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                        style = if (fileItem.isDirectory) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            },
            supportingContent = {
                val dateStr = formatItemDate(fileItem.lastModified)
                if (fileItem.isDirectory) {
                    val count = fileItem.itemCount ?: 0
                    if (viewModel.showFolderSize) {
                        Text(stringResource(R.string.item_folder_size_count_and_date, count, formatFileSize(fileItem.size), dateStr))
                    } else {
                        Text(stringResource(R.string.item_count_and_date, count, dateStr))
                    }
                } else if (showDetails) {
                    Text(stringResource(R.string.item_size_and_date, formatFileSize(fileItem.size), dateStr))
                }
            },
            leadingContent = {
                val isFav = remember(fileItem.file.absolutePath, viewModel?.favourites?.favouriteFiles) {
                    if (fileItem.isDirectory || viewModel?.storage?.isCloudStorage(fileItem.file.absolutePath) == true) false else viewModel?.favourites?.isFileInFavourites(fileItem.file.absolutePath) == true
                }
                Box(contentAlignment = Alignment.Center) {
                    FileIcon(fileItem, iconSize = 44.dp, isVirtual = isVirtual, viewModel = viewModel, showFavouriteBadge = isFav)
                }
            },
            trailingContent = {
                if (isSelectionMode) {
                    val boundsModifier = if (dragSelectState != null) {
                        DisposableEffect(fileItem.file.absolutePath) {
                            onDispose {
                                dragSelectState.itemBounds.remove(fileItem.file.absolutePath)
                            }
                        }
                        Modifier.onGloballyPositioned { coords ->
                            dragSelectState.itemBounds[fileItem.file.absolutePath] = coords.boundsInWindow()
                        }
                    } else Modifier

                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = boundsModifier,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )
                } else {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                AppIcons.MoreVert,
                                contentDescription = stringResource(R.string.content_desc_more_options),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        WindowsFileDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            fileItem = fileItem,
                            viewModel = viewModel,
                            isVirtual = isVirtual,
                            onCopy = onCopy,
                            onCut = onCut,
                            onDelete = onDelete,
                            onShare = onShare,
                            onProperties = onProperties,
                            onRenameRequest = onRenameRequest,
                            onOpenWith = onOpenWith,
                            onOpenLocation = onOpenLocation,
                            onExtract = onExtract
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 0.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(backgroundColor)
        )
        if (isDividerVisible) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )
        }
    }
}
