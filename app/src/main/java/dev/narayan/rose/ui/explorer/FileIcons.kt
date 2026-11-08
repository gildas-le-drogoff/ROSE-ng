package dev.narayan.rose.ui.explorer

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.ui.icons.AppIcons
import java.io.File
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun getFolderSpecialSymbol(name: String, path: String? = null): ImageVector? {
    val cleanName = name.trim().lowercase()
    val cleanPath = path?.trimEnd('/', '\\')?.lowercase() ?: ""
    return when {
        cleanName == "download" || cleanName == "downloads" || cleanPath.endsWith("/download") || cleanPath.endsWith("/downloads") ->
            AppIcons.Download
        cleanName == "documents" || cleanName == "document" || cleanName == "doc" || cleanName == "docs" || cleanPath.endsWith("/documents") ->
            AppIcons.Description
        cleanName == "dcim" || cleanName == "camera" || cleanPath.endsWith("/dcim") || cleanPath.endsWith("/dcim/camera") || cleanPath.endsWith("/camera") ->
            AppIcons.CameraAlt
        cleanName == "pictures" || cleanName == "photos" || cleanName == "screenshots" || cleanPath.endsWith("/pictures") ->
            AppIcons.Image
        cleanName == "music" || cleanName == "audio" || cleanPath.endsWith("/music") ->
            AppIcons.MusicNote
        cleanName == "movies" || cleanName == "videos" || cleanName == "video" || cleanPath.endsWith("/movies") || cleanPath.endsWith("/video") ->
            AppIcons.Movie
        else -> null
    }
}

@Composable
fun FileIcon(
    fileItem: FileItem,
    iconSize: Dp = 52.dp,
    isVirtual: Boolean = false,
    folderTint: Color = Color(0xFF70C1FF),
    viewModel: RoseViewModel? = null,
    showFavouriteBadge: Boolean = false
) {
    val context = LocalContext.current
    var isThumbnailLoaded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            when (fileItem.fileType) {
                FileType.IMAGE, FileType.VIDEO -> {
                    val placeholderIcon = if (fileItem.fileType == FileType.IMAGE) AppIcons.Image else AppIcons.Movie

                if (isVirtual && fileItem.virtualZipSource != null) {
                    val zipThumb by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = fileItem.file.absolutePath, key2 = viewModel?.cachedZipPassword) {
                        value = withContext(Dispatchers.IO) {
                            val entryPath = fileItem.zipEntryPath ?: fileItem.name
                            val bytes = ArchiveManager.getEntryBytes(fileItem.virtualZipSource, entryPath, passphrase = viewModel?.cachedZipPassword)
                            if (bytes != null) {
                                try {
                                    val bounds = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

                                    val targetPx = 200
                                    var sampleSize = 1
                                    while (bounds.outWidth / sampleSize > targetPx * 2 ||
                                        bounds.outHeight / sampleSize > targetPx * 2
                                    ) {
                                        sampleSize *= 2
                                    }

                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = sampleSize
                                    }
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                        if (value != null) isThumbnailLoaded = true
                    }

                    if (zipThumb != null) {
                        Image(
                            bitmap = zipThumb!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        FileTypeBadge(placeholderIcon, if (fileItem.fileType == FileType.IMAGE) Color(0xFF4C8DFF) else Color(0xFF9C6ADE), iconSize, Modifier.fillMaxSize())
                    }
                } else if (SafManager.isSafUri(fileItem.file.absolutePath)) {
                    val uriString = fileItem.file.absolutePath
                    val normalizedUri = remember(uriString) {
                        val n = if (uriString.startsWith("/content:/")) {
                            uriString.substring(1).replaceFirst("content:/", "content://")
                        } else if (uriString.startsWith("/content://")) {
                            uriString.substring(1)
                        } else {
                            uriString
                        }
                        Uri.parse(n)
                    }

                    val offlineFile = viewModel?.getLocalOfflineFile(fileItem)

                    if (offlineFile != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(offlineFile)
                                .memoryCacheKey("${offlineFile.absolutePath}_${fileItem.lastModified}")
                                .diskCacheKey("${offlineFile.absolutePath}_${fileItem.lastModified}")
                                .size(128)
                                .allowHardware(true)
                                .apply {
                                    if (fileItem.fileType == FileType.VIDEO) {
                                        decoderFactory(VideoFrameDecoder.Factory())
                                        videoFrameMillis(1000)
                                    }
                                }
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = rememberVectorPainter(placeholderIcon),
                            error = rememberVectorPainter(placeholderIcon)
                        )
                    } else {
                        val safThumb by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = uriString) {
                            value = withContext(Dispatchers.IO) {
                                try {
                                    DocumentsContract.getDocumentThumbnail(
                                        context.contentResolver,
                                        normalizedUri,
                                        android.graphics.Point(128, 128),
                                        null
                                    )
                                } catch (e: Exception) { null }
                            }
                        }

                        if (safThumb != null) {
                            Image(
                                bitmap = safThumb!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val authority = normalizedUri.authority ?: ""
                            val isKnownLocalAuthority = authority == "com.android.externalstorage.documents" ||
                                    authority == "com.android.providers.media.documents" ||
                                    authority == "media" ||
                                    authority.endsWith(".provider")

                            if (isKnownLocalAuthority) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(normalizedUri)
                                        .memoryCacheKey("${uriString}_${fileItem.lastModified}")
                                        .diskCacheKey("${uriString}_${fileItem.lastModified}")
                                        .size(128)
                                        .crossfade(true)
                                        .apply {
                                            if (fileItem.fileType == FileType.VIDEO) {
                                                decoderFactory(VideoFrameDecoder.Factory())
                                                videoFrameMillis(1000)
                                            }
                                        }
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    placeholder = rememberVectorPainter(placeholderIcon),
                                    error = rememberVectorPainter(placeholderIcon)
                                )
                            } else {
                                FileTypeBadge(placeholderIcon, if (fileItem.fileType == FileType.IMAGE) Color(0xFF4C8DFF) else Color(0xFF9C6ADE), iconSize, Modifier.fillMaxSize())
                            }
                        }
                    }
                } else if (SafManager.isRestrictedPath(fileItem.file.absolutePath)) {
                    val thumbBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = fileItem.file.absolutePath) {
                        value = RestrictedThumbnailLoader.loadThumbnail(context, fileItem.file.absolutePath, fileItem.fileType)
                    }
                    if (thumbBitmap != null) {
                        Image(
                            bitmap = thumbBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        FileTypeBadge(placeholderIcon, if (fileItem.fileType == FileType.IMAGE) Color(0xFF4C8DFF) else Color(0xFF9C6ADE), iconSize, Modifier.fillMaxSize())
                    }
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(fileItem.file)
                            .memoryCacheKey("${fileItem.file.absolutePath}_${fileItem.lastModified}")
                            .diskCacheKey("${fileItem.file.absolutePath}_${fileItem.lastModified}")
                            .size(128)
                            .allowHardware(true)
                            .apply {
                                if (fileItem.fileType == FileType.VIDEO) {
                                    decoderFactory(VideoFrameDecoder.Factory())
                                }
                            }
                            .crossfade(80)
                            .videoFrameMillis(0)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = rememberVectorPainter(placeholderIcon),
                        error = rememberVectorPainter(placeholderIcon)
                    )
                }
            }
            FileType.APK -> {
                val icon by produceState<Drawable?>(initialValue = apkIconCache.get(fileItem.file.absolutePath), key1 = fileItem.file) {
                    if (!isVirtual) {
                        val cached = apkIconCache.get(fileItem.file.absolutePath)
                        if (cached != null) {
                            value = cached
                        } else if (!SafManager.isSafUri(fileItem.file.absolutePath)) {
                            val loaded = withContext(Dispatchers.IO) { getApkIcon(context, fileItem.file) }
                            if (loaded != null) apkIconCache.put(fileItem.file.absolutePath, loaded)
                            value = loaded
                        }
                    }
                }
                if (icon != null) {
                    Image(
                        bitmap = icon!!.toBitmap().asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(iconSize * 0.15f)
                    )
                } else {
                    FileTypeBadge(AppIcons.Android, FileIconColors.apk, iconSize, Modifier.fillMaxSize())
                }
            }
            FileType.FOLDER -> {
                val appIcon by produceState<Drawable?>(initialValue = packageIconCache.get(fileItem.name), key1 = fileItem.name) {
                    if (fileItem.name.contains(".")) {
                        val cached = packageIconCache.get(fileItem.name)
                        if (cached != null) {
                            value = cached
                        } else {
                            val loaded = withContext(Dispatchers.IO) { getPackageIcon(context, fileItem.name) }
                            if (loaded != null) packageIconCache.put(fileItem.name, loaded)
                            value = loaded
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (fileItem.thumbnailPath != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(fileItem.thumbnailPath)
                                .memoryCacheKey("${fileItem.thumbnailPath}_thumb")
                                .size(256)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = rememberVectorPainter(AppIcons.Folder),
                            error = rememberVectorPainter(AppIcons.Folder)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.1f))
                        )
                    } else {
                        val specialSymbol = getFolderSpecialSymbol(fileItem.name, fileItem.file.absolutePath)
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                AppIcons.Folder,
                                null,
                                modifier = Modifier.fillMaxSize(),
                                tint = folderTint
                            )
                            if (specialSymbol != null) {
                                Icon(
                                    specialSymbol,
                                    contentDescription = null,
                                    tint = Color(0xFF1976D2).copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .size(iconSize * 0.42f)
                                        .offset(y = iconSize * 0.06f)
                                )
                            }
                        }
                    }

                    if (appIcon != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 2.dp, y = 2.dp)
                                .size(iconSize * 0.45f)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                .padding(1.dp)
                        ) {
                            Image(
                                bitmap = appIcon!!.toBitmap().asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
            FileType.ZIP -> FileTypeBadge(AppIcons.Archive, FileIconColors.archive, iconSize, Modifier.fillMaxSize())
            FileType.PDF -> FileTypeBadge(AppIcons.PictureAsPdf, FileIconColors.pdf, iconSize, Modifier.fillMaxSize())
            FileType.AUDIO -> FileTypeBadge(AppIcons.MusicNote, FileIconColors.audio, iconSize, Modifier.fillMaxSize())
            FileType.DOCUMENT -> FileTypeBadge(AppIcons.Description, FileIconColors.document, iconSize, Modifier.fillMaxSize())
            else -> FileTypeBadge(AppIcons.InsertDriveFile, MaterialTheme.colorScheme.secondary, iconSize, Modifier.fillMaxSize())
        }
    }

    if (fileItem.isEncrypted && !isThumbnailLoaded && viewModel?.cachedZipPassword == null) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(iconSize * 0.35f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                .padding(2.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Lock,
                contentDescription = stringResource(R.string.content_desc_encrypted),
                modifier = Modifier.size(iconSize * 0.22f),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showFavouriteBadge && viewModel?.storage?.isCloudStorage(fileItem.file.absolutePath) != true) {
        val badgeAlignment = if (fileItem.isEncrypted && !isThumbnailLoaded && viewModel?.cachedZipPassword == null) {
            Alignment.TopEnd
        } else {
            Alignment.BottomEnd
        }
        val badgeOffsetX = 2.dp
        val badgeOffsetY = if (fileItem.isEncrypted && !isThumbnailLoaded && viewModel?.cachedZipPassword == null) (-2).dp else 2.dp
        val badgeSize = (iconSize * 0.42f).coerceIn(14.dp, 20.dp)
        val heartSize = (iconSize * 0.24f).coerceIn(8.dp, 12.dp)

        Box(
            modifier = Modifier
                .align(badgeAlignment)
                .offset(x = badgeOffsetX, y = badgeOffsetY)
                .size(badgeSize)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(1.5.dp)
                .background(Color(0xFFE11D48), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Favorite,
                contentDescription = null,
                modifier = Modifier.size(heartSize),
                tint = Color.White
            )
        }
    }

    val isOffline = viewModel?.isFileOffline(fileItem.file.absolutePath) == true
    if (isOffline) {
        val offlineBadgeSize = (iconSize * 0.44f).coerceIn(16.dp, 22.dp)
        val iconInnerSize = (iconSize * 0.28f).coerceIn(10.dp, 14.dp)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-3).dp, y = 3.dp)
                .shadow(elevation = 3.dp, shape = CircleShape)
                .size(offlineBadgeSize)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(1.5.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF10B981),
                            Color(0xFF047857)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.CloudDone,
                contentDescription = stringResource(R.string.status_offline_available),
                modifier = Modifier.size(iconInnerSize),
                tint = Color.White
            )
        }
    }
}
}

internal object FileIconColors {
    val archive = Color(0xFFC98A2E)
    val pdf = Color(0xFFC1554C)
    val audio = Color(0xFF5B7FDB)
    val document = Color(0xFF4C9A72)
    val apk = Color(0xFF4CAF7D)
}

@Composable
internal fun FileTypeBadge(icon: ImageVector, color: Color, iconSize: Dp, modifier: Modifier) {
    Box(
        modifier = modifier.background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        val multiplier = if (icon == AppIcons.Android) 0.55f else 0.65f
        Icon(icon, null, tint = color, modifier = Modifier.size(iconSize * multiplier))
    }
}

internal val apkIconCache = LruCache<String, Drawable>(60)

internal val packageIconCache = LruCache<String, Drawable>(100)

fun getApkIcon(context: Context, file: File): Drawable? {
    return try {
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        packageInfo?.applicationInfo?.let {
            it.sourceDir = file.absolutePath
            it.publicSourceDir = file.absolutePath
            it.loadIcon(context.packageManager)
        }
    } catch (e: Exception) { null }
}

fun getPackageIcon(context: Context, packageName: String): Drawable? {
    return try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: Exception) { null }
}
