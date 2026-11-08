package dev.narayan.rose.ui.home

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.ui.explorer.*
import dev.narayan.rose.ui.icons.AppIcons
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun StorageCapsule(
    viewModel: RoseViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit
) {
    val storageInfo = viewModel.storage.storageInfo

    val storageFraction = storageInfo?.usedFraction ?: 0f
    val targetBarColor = when {
        storageFraction >= 0.90f -> Color(0xFFF43F5E)
        storageFraction >= 0.75f -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.primary
    }
    val barColor by animateColorAsState(
        targetValue = targetBarColor,
        animationSpec = tween(durationMillis = 600),
        label = "StorageHealthColor"
    )

    var animationStarted by remember { mutableStateOf(viewModel.hasRunStorageAnimation) }
    val animatedFraction by animateFloatAsState(
        targetValue = if (animationStarted) storageFraction else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "StorageProgress"
    )

    LaunchedEffect(Unit) {
        if (!viewModel.hasRunStorageAnimation) {
            kotlinx.coroutines.delay(500)
            animationStarted = true
            viewModel.hasRunStorageAnimation = true
        }
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "all_files_capsule"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                    )
                }
            )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.SdStorage, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    with(sharedTransitionScope) {
                        Text(
                            stringResource(R.string.category_all_files),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState(key = "all_files_title"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (storageInfo != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatFileSize(storageInfo.usedBytes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.storage_used_of_total, formatFileSize(storageInfo.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.height(32.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (animatedFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(barColor)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StorageSection(
    viewModel: RoseViewModel,
    onOpenPath: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val allDevices = viewModel.storage.storageDevices

    val sdDevices = allDevices.filterIsInstance<StorageDevice.Physical>().filter { it.isSdCard && !it.isUsb }

    val usbDevices = allDevices.filterIsInstance<StorageDevice.Physical>().filter { it.isUsb }

    val rootDevice = if (viewModel.useRoot) {
        allDevices.firstOrNull { it is StorageDevice.Root }
    } else null

    val treeDevices = allDevices.filterIsInstance<StorageDevice.DocumentTree>()

    val hasAnyStorages = sdDevices.isNotEmpty() || usbDevices.isNotEmpty() || rootDevice != null || treeDevices.isNotEmpty()

    var storagePendingRemoval by remember { mutableStateOf<StorageDevice.DocumentTree?>(null) }
    var pendingStorageUriForLock by remember { mutableStateOf<android.net.Uri?>(null) }

    val openDocumentTreeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val auth = uri.authority ?: ""
            val uriStr = uri.toString()
            val isGoogleDrive = uriStr.startsWith("gdrive://") ||
                    auth.contains("docs.storage") ||
                    auth.contains("com.google.android.apps.docs")

            val activity = context as? androidx.fragment.app.FragmentActivity
            if (isGoogleDrive && activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                pendingStorageUriForLock = uri
            } else {
                viewModel.storage.addCustomStorage(uri)
                Toast.makeText(context, context.getString(R.string.toast_storage_added), Toast.LENGTH_SHORT).show()
            }
        }
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
                        viewModel.setExternalStorageExpanded(!viewModel.externalStorageExpanded)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.storage_storages_and_drives),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = { viewModel.setExternalStorageExpanded(!viewModel.externalStorageExpanded) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (viewModel.externalStorageExpanded) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
                    contentDescription = if (viewModel.externalStorageExpanded) stringResource(R.string.content_desc_collapse) else stringResource(R.string.content_desc_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))

        androidx.compose.animation.AnimatedVisibility(
            visible = viewModel.externalStorageExpanded,
            enter = androidx.compose.animation.expandVertically(animationSpec = tween(250)) + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.shrinkVertically(animationSpec = tween(200)) + androidx.compose.animation.fadeOut(tween(150))
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!hasAnyStorages) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = AppIcons.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.storage_no_external_devices),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.storage_no_external_devices_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    if (sdDevices.isNotEmpty()) {
                        sdDevices.forEach { device ->
                            val used = device.totalBytes - device.availableBytes
                            val subtitle = if (device.totalBytes > 0) {
                                stringResource(R.string.storage_amount_used_of_total, formatFileSize(used), formatFileSize(device.totalBytes))
                            } else {
                                stringResource(R.string.storage_sd_card)
                            }
                            StorageItemRow(
                                title = device.name,
                                subtitle = subtitle,
                                icon = AppIcons.SdCard,
                                iconColor = Color(0xFF3B82F6),
                                badgeText = stringResource(R.string.storage_badge_sd),
                                badgeColor = Color(0xFF3B82F6),
                                onClick = { onOpenPath(device.path) }
                            )
                            if (viewModel.showListDividers) {
                                StorageDivider()
                            }
                        }
                    }

                    if (usbDevices.isNotEmpty()) {
                        usbDevices.forEach { device ->
                            val used = device.totalBytes - device.availableBytes
                            val subtitle = if (device.totalBytes > 0) {
                                stringResource(R.string.storage_amount_used_of_total, formatFileSize(used), formatFileSize(device.totalBytes))
                            } else {
                                "OTG Storage Device"
                            }
                            StorageItemRow(
                                title = device.name,
                                subtitle = subtitle,
                                icon = AppIcons.Usb,
                                iconColor = Color(0xFF0D9488),
                                badgeText = stringResource(R.string.storage_badge_otg),
                                badgeColor = Color(0xFF0D9488),
                                onClick = { onOpenPath(device.path) }
                            )
                            if (viewModel.showListDividers) {
                                StorageDivider()
                            }
                        }
                    }

                    if (rootDevice != null) {
                        StorageItemRow(
                            title = stringResource(R.string.storage_root),
                            subtitle = stringResource(R.string.storage_root_desc),
                            icon = AppIcons.Tag,
                            iconColor = Color(0xFFF59E0B),
                            badgeText = "SU",
                            badgeColor = Color(0xFFF59E0B),
                            onClick = { onOpenPath(rootDevice.path) }
                        )
                        if (viewModel.showListDividers) {
                            StorageDivider()
                        }
                    }

                    if (treeDevices.isNotEmpty()) {
                        treeDevices.forEach { device ->
                            val (itemIcon, itemColor, itemBadgeText) = when (device.storageType) {
                                CustomStorageType.GOOGLE_DRIVE -> Triple(AppIcons.Cloud, Color(0xFF0F9D58), "Google Drive")
                                CustomStorageType.DOCUMENT_TREE -> if (device.isCloud) {
                                    Triple(AppIcons.Cloud, Color(0xFF0F9D58), "Google Drive")
                                } else {
                                    Triple(AppIcons.Storage, Color(0xFF8B5CF6), stringResource(R.string.storage_badge_custom))
                                }
                                else -> if (device.isCloud) {
                                    Triple(AppIcons.Cloud, Color(0xFF0F9D58), "Google Drive")
                                } else {
                                    Triple(AppIcons.Storage, Color(0xFF8B5CF6), stringResource(R.string.storage_badge_custom))
                                }
                            }
                            val subtitle = device.providerName ?: itemBadgeText
                            val isLocked = viewModel.storage.isStorageLocked(device.path) || device.isLocked
                            var showItemMenu by remember { mutableStateOf(false) }
                            StorageItemRow(
                                title = device.name,
                                subtitle = subtitle,
                                icon = itemIcon,
                                iconColor = itemColor,
                                badgeText = itemBadgeText,
                                badgeColor = itemColor,
                                isLocked = isLocked,
                                onClick = {
                                    if (isLocked) {
                                        val activity = context as? androidx.fragment.app.FragmentActivity
                                        if (activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                                            BiometricSecurityManager.authenticate(
                                                activity = activity,
                                                title = "Unlock ${device.name}",
                                                subtitle = "Scan fingerprint to access this folder",
                                                onSuccess = {
                                                    onOpenPath(device.path)
                                                },
                                                onError = { err ->
                                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        } else {
                                            onOpenPath(device.path)
                                        }
                                    } else {
                                        onOpenPath(device.path)
                                    }
                                },
                                onLongClick = { storagePendingRemoval = device },
                                trailingContent = {
                                    Box {
                                        IconButton(
                                            onClick = { showItemMenu = true },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = AppIcons.MoreVert,
                                                contentDescription = stringResource(R.string.content_desc_more_options),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showItemMenu,
                                            onDismissRequest = { showItemMenu = false },
                                            shape = RoundedCornerShape(14.dp),
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            val isGdriveOrCloud = device.storageType == CustomStorageType.GOOGLE_DRIVE || device.isCloud || isLocked
                                            if (isGdriveOrCloud) {
                                                DropdownMenuItem(
                                                    text = { Text(if (isLocked) "Remove Fingerprint Lock" else "Lock with Fingerprint") },
                                                    onClick = {
                                                        showItemMenu = false
                                                        val activity = context as? androidx.fragment.app.FragmentActivity
                                                        if (activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                                                            BiometricSecurityManager.authenticate(
                                                                activity = activity,
                                                                title = if (isLocked) "Unlock ${device.name}" else "Lock ${device.name}",
                                                                subtitle = "Authenticate fingerprint to confirm",
                                                                onSuccess = {
                                                                    viewModel.storage.setStorageLocked(device.path, !isLocked)
                                                                    Toast.makeText(
                                                                        context,
                                                                        if (isLocked) "Fingerprint lock removed" else "Folder locked with fingerprint",
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                },
                                                                onError = { err ->
                                                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                                }
                                                            )
                                                        } else {
                                                            viewModel.storage.setStorageLocked(device.path, !isLocked)
                                                            Toast.makeText(
                                                                context,
                                                                if (isLocked) "Lock removed" else "Folder locked",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            if (isLocked) AppIcons.LockOpen else AppIcons.Fingerprint,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                )
                                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_remove)) },
                                                onClick = {
                                                    showItemMenu = false
                                                    storagePendingRemoval = device
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        AppIcons.Delete,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                            if (viewModel.showListDividers) {
                                StorageDivider()
                            }
                        }
                    }

                    StorageDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openDocumentTreeLauncher.launch(null)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(R.string.storage_add_cloud_or_network),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    storagePendingRemoval?.let { dev ->
        AlertDialog(
            onDismissRequest = { storagePendingRemoval = null },
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
                        imageVector = AppIcons.Delete,
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
                    Text(
                        text = stringResource(R.string.dialog_remove_storage_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_remove_storage_message, dev.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.storage.removeCustomStorage(dev.uriString)
                        storagePendingRemoval = null
                        Toast.makeText(context, context.getString(R.string.toast_storage_removed), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Delete,
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
                    onClick = { storagePendingRemoval = null },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    pendingStorageUriForLock?.let { uri ->
        val rawDocName = remember(uri) {
            try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
            } catch (e: Exception) {
                null
            } ?: "Folder"
        }
        val docName = rawDocName.trim()

        Dialog(
            onDismissRequest = {
                viewModel.storage.addCustomStorage(uri)
                pendingStorageUriForLock = null
                Toast.makeText(context, context.getString(R.string.toast_storage_added), Toast.LENGTH_SHORT).show()
            }
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                                    )
                                ),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                shape = RoundedCornerShape(24.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "Lock Folder?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                        border = BorderStroke(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color(0xFF0F9D58).copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = AppIcons.Cloud,
                                    contentDescription = null,
                                    tint = Color(0xFF0F9D58),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = docName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Google Drive Folder",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Require fingerprint authentication each time you open this folder to protect your files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            val activity = context as? androidx.fragment.app.FragmentActivity
                            if (activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                                BiometricSecurityManager.authenticate(
                                    activity = activity,
                                    title = "Lock $docName",
                                    subtitle = "Confirm fingerprint to lock this folder",
                                    onSuccess = {
                                        viewModel.storage.addCustomStorage(uri)
                                        viewModel.storage.setStorageLocked(uri.toString(), true)
                                        pendingStorageUriForLock = null
                                        Toast.makeText(context, "Folder added and locked with fingerprint", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } else {
                                viewModel.storage.addCustomStorage(uri)
                                viewModel.storage.setStorageLocked(uri.toString(), true)
                                pendingStorageUriForLock = null
                                Toast.makeText(context, "Folder added and locked", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Lock with Fingerprint",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    FilledTonalButton(
                        onClick = {
                            viewModel.storage.addCustomStorage(uri)
                            pendingStorageUriForLock = null
                            Toast.makeText(context, context.getString(R.string.toast_storage_added), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "No, Keep Unlocked",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

}

@Composable
internal fun StorageDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StorageItemRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    badgeText: String? = null,
    badgeColor: Color = iconColor,
    isLocked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(iconColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLocked) {
                    Icon(
                        imageVector = AppIcons.Lock,
                        contentDescription = "Locked with fingerprint",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (badgeText != null) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.14f),
                        contentColor = badgeColor,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailingContent != null) {
            trailingContent()
        } else {
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
