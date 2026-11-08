package dev.narayan.rose.ui.home

import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.ui.explorer.*
import dev.narayan.rose.ui.icons.AppIcons
import kotlinx.coroutines.launch

@Composable
internal fun SearchResultItem(
    item: FileItem,
    viewModel: RoseViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onOpenLocation: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
    onExtract: () -> Unit = {},
    onOpenArchive: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    item.file.parent ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(item.size)} | ${formatItemDate(item.lastModified)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        leadingContent = {
            Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                FileIcon(fileItem = item, viewModel = (androidx.compose.ui.platform.LocalContext.current as? androidx.activity.ComponentActivity)?.let { (it as? MainActivity)?.viewModel })
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(AppIcons.MoreVert, null)
                }
                WindowsFileDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    fileItem = item,
                    viewModel = viewModel,
                    isVirtual = false,
                    onCopy = onCopy,
                    onCut = onCut,
                    onDelete = onDelete,
                    onShare = onShare,
                    onProperties = onProperties,
                    onRenameRequest = onRename,
                    onOpenLocation = onOpenLocation,
                    onExtract = if (item.fileType == FileType.ZIP) { onExtract } else null,
                    offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecentFilesSection(
    viewModel: RoseViewModel,
    recentFiles: List<FileItem>,
    showDividers: Boolean,
    onOpenRecent: () -> Unit,
    onOpenPath: (String, FileItem?) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onOpenWithClick: (FileItem) -> Unit,
    onShareClick: (FileItem) -> Unit,
    onRenameClick: (FileItem) -> Unit = {},
    onDeleteClick: (FileItem) -> Unit = {},
    onExtractClick: (FileItem) -> Unit = {},
    onPropertiesClick: (FileItem) -> Unit = {}
) {
    val recents = recentFiles.take(5)
    if (recents.isEmpty()) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
                        viewModel.setRecentFilesExpanded(!viewModel.recentFilesExpanded)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.home_recent_files),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = { viewModel.setRecentFilesExpanded(!viewModel.recentFilesExpanded) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (viewModel.recentFilesExpanded) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
                    contentDescription = if (viewModel.recentFilesExpanded) stringResource(R.string.content_desc_collapse) else stringResource(R.string.content_desc_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        androidx.compose.animation.AnimatedVisibility(
            visible = viewModel.recentFilesExpanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                recents.forEachIndexed { index, item ->
                    Column {
                        var showFileMenu by remember { mutableStateOf(false) }
                        val color = recentFileIconFor(item.fileType).second

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (System.currentTimeMillis() - viewModel.lastSwipeToHomeTimestamp > 500L) {
                                        onFileClick(item)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                val isFav = remember(item.file.absolutePath, viewModel.favourites.favouriteFiles) {
                                    if (item.isDirectory) false else viewModel.favourites.isFileInFavourites(item.file.absolutePath)
                                }
                                FileIcon(
                                    fileItem = item,
                                    iconSize = 48.dp,
                                    folderTint = color,
                                    viewModel = viewModel,
                                    showFavouriteBadge = isFav
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(R.string.item_size_and_date, formatFileSize(item.size), formatItemDate(item.lastModified)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { showFileMenu = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        AppIcons.MoreVert,
                                        contentDescription = stringResource(R.string.content_desc_file_actions),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                WindowsFileDropdownMenu(
                                    expanded = showFileMenu,
                                    onDismissRequest = { showFileMenu = false },
                                    fileItem = item,
                                    viewModel = viewModel,
                                    isVirtual = false,
                                    onCopy = {
                                        viewModel.copySingle(item)
                                        coroutineScope.launch {
                                            kotlinx.coroutines.yield()
                                            onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                        }
                                    },
                                    onCut = {
                                        viewModel.moveSingle(item)
                                        coroutineScope.launch {
                                            kotlinx.coroutines.yield()
                                            onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                        }
                                    },
                                    onDelete = { onDeleteClick(item) },
                                    onShare = { onShareClick(item) },
                                    onProperties = { onPropertiesClick(item) },
                                    onRenameRequest = { onRenameClick(item) },
                                    onOpenWith = { onOpenWithClick(item) },
                                    onOpenLocation = { onOpenPath(item.file.parent ?: item.file.absolutePath, item) },
                                    onExtract = if (item.fileType == FileType.ZIP) { { onExtractClick(item) } } else null,
                                    offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                                )
                            }
                        }
                        if (showDividers && index != recents.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                                thickness = 0.6.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                    thickness = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecent() }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.action_see_all),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = stringResource(R.string.action_see_all),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
}

@Composable
internal fun recentFileIconFor(type: FileType): Pair<ImageVector, Color> = when (type) {
    FileType.IMAGE -> AppIcons.Image to Color(0xFF4C8DFF)
    FileType.VIDEO -> AppIcons.VideoLibrary to Color(0xFF9C6ADE)
    FileType.AUDIO -> AppIcons.MusicNote to Color(0xFFE0904C)
    FileType.DOCUMENT -> AppIcons.Description to Color(0xFF4C9A72)
    FileType.APK -> AppIcons.Android to Color(0xFF4CAF7D)
    FileType.ZIP -> AppIcons.Archive to Color(0xFFC98A2E)
    FileType.PDF -> AppIcons.PictureAsPdf to Color(0xFFC1554C)
    FileType.FOLDER -> AppIcons.Folder to folderIconFallbackColor
    else -> AppIcons.InsertDriveFile to Color(0xFF8A8F98)
}
