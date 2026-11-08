package dev.narayan.rose.ui.home

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.ui.explorer.*
import dev.narayan.rose.ui.icons.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderPickerDialog(
    viewModel: RoseViewModel,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }
    val isCurrentDirAdded = viewModel.favourites.isFolderInQuickAccess(currentDir.absolutePath)

    val subDirs = remember(currentDir) {
        currentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { dir ->
                FileItem(
                    file = dir,
                    isDirectory = true,
                    name = dir.name,
                    size = 0,
                    lastModified = dir.lastModified(),
                    extension = "",
                    itemCount = dir.list { _, name -> !name.startsWith(".") }?.size ?: 0
                )
            }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler {
            if (currentDir != rootDir) currentDir.parentFile?.let { currentDir = it } else onDismiss()
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (currentDir == rootDir) stringResource(R.string.storage_internal) else currentDir.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isCurrentDirAdded) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Text(
                                        text = stringResource(R.string.tag_added),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentDir != rootDir) currentDir.parentFile?.let { currentDir = it } else onDismiss()
                        }) {
                            Icon(
                                if (currentDir != rootDir) AppIcons.ArrowBack else AppIcons.Close,
                                contentDescription = stringResource(R.string.content_desc_back)
                            )
                        }
                    },
                    actions = {
                        if (currentDir != rootDir) {
                            IconButton(onClick = { currentDir = rootDir }) {
                                Icon(AppIcons.Home, contentDescription = stringResource(R.string.content_desc_go_to_root))
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                        Button(
                            onClick = {
                                if (!isCurrentDirAdded) {
                                    onFolderSelected(currentDir.absolutePath)
                                }
                            },
                            enabled = !isCurrentDirAdded,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(AppIcons.Check, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isCurrentDirAdded) stringResource(R.string.folder_already_added)
                                else stringResource(R.string.action_select_this_folder),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        ) { padding ->
            if (subDirs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.folder_picker_no_subfolders),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                val animatedItemKeys = remember(currentDir) { androidx.compose.runtime.mutableStateSetOf<String>() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(subDirs, key = { _, item -> item.file.absolutePath }) { index, item ->
                        val isAdded = viewModel.favourites.isFolderInQuickAccess(item.file.absolutePath)
                        FolderPickerRow(
                            item = item,
                            index = index,
                            scrollResetKey = currentDir,
                            hasAnimatedBefore = animatedItemKeys.contains(item.file.absolutePath),
                            onAnimationStart = { animatedItemKeys.add(item.file.absolutePath) },
                            viewModel = viewModel,
                            isAdded = isAdded,
                            onClick = { currentDir = item.file }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun FolderPickerRow(
    item: FileItem,
    index: Int,
    scrollResetKey: Any,
    hasAnimatedBefore: Boolean,
    onAnimationStart: () -> Unit,
    viewModel: RoseViewModel?,
    isAdded: Boolean = false,
    onClick: () -> Unit
) {
    val animatedProgress = remember(scrollResetKey, item.file.absolutePath) {
        Animatable(if (hasAnimatedBefore) 1f else 0f)
    }
    val density = LocalDensity.current
    LaunchedEffect(scrollResetKey, item.file.absolutePath) {
        if (!hasAnimatedBefore) {
            onAnimationStart()
            kotlinx.coroutines.delay((index % 8 * 12).toLong())
            animatedProgress.animateTo(1f, tween(durationMillis = 200, easing = LinearOutSlowInEasing))
        }
    }
    
    ListItem(
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    item.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isAdded) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.tag_added),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        supportingContent = {
            val count = item.itemCount ?: 0
            val dateStr = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.lastModified))
            Text(stringResource(R.string.item_count_and_date, count, dateStr))
        },
        leadingContent = {
            FileIcon(
                fileItem = item,
                iconSize = 40.dp,
                folderTint = MaterialTheme.colorScheme.primary,
                viewModel = viewModel
            )
        },
        trailingContent = {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        },
        modifier = Modifier
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = (1f - animatedProgress.value) * with(density) { 40.dp.toPx() }
            }
            .clickable { onClick() }
    )
}
