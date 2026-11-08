package dev.narayan.rose.ui.explorer

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.ui.icons.AppIcons
import java.util.*

@Composable
fun RestrictedFolderView(
    path: String,
    isShizukuAuthorized: Boolean = false,
    onGrantShizuku: () -> Unit,
    onGrantSaf: () -> Unit,
    title: String = stringResource(R.string.restricted_folder_default_title),
    description: String = stringResource(R.string.restricted_folder_default_desc),
    onRefresh: (() -> Unit)? = null
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    AppIcons.LockOutlined,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                val isSystemRestricted = path.contains("/Android/data") || path.contains("/Android/obb")

                if (isSystemRestricted) {
                    val isShizukuRunning = ShizukuManager.isAvailable()
                    Button(
                        onClick = onGrantShizuku,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isShizukuRunning) AppIcons.VerifiedUserOutlined else AppIcons.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                if (isShizukuRunning) stringResource(R.string.action_grant_shizuku) else "Start Shizuku Service",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!isShizukuRunning && onRefresh != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRefresh) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check Again", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = { uriHandler.openUri("https://shizuku.rikka.app/download/") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_how_to_setup_shizuku), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(
                        onClick = onGrantSaf,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.FolderSpecialOutlined, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.action_grant_storage_access), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = onGrantShizuku, modifier = Modifier.alpha(0.7f)) {
                        Text(stringResource(R.string.action_use_shizuku_instead), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color
) {
    val annotatedString = remember(text) {
        buildAnnotatedString {
            val lines = text.split("\n")
            lines.forEachIndexed { index, line ->
                var l = line

                if (l.trim().startsWith("- ") || l.trim().startsWith("* ")) {
                    append(" • ")
                    l = l.trim().substring(2)
                }

                val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
                var lastIdx = 0
                boldRegex.findAll(l).forEach { match ->
                    append(l.substring(lastIdx, match.range.first))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[1])
                    }
                    lastIdx = match.range.last + 1
                }
                append(l.substring(lastIdx))

                if (index < lines.size - 1) append("\n")
            }
        }
    }
    Text(annotatedString, style = style, color = color)
}

@Composable
fun RemoteStorageEmptyView(
    path: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lower = path.lowercase()
    val (icon, color, title, desc) = when {
        lower.startsWith("mega://") -> Triple(
            AppIcons.CloudSync,
            Color(0xFFD9272E),
            "Mega Cloud Storage"
        ) to "Connected to Mega account. This folder is empty."
        lower.startsWith("onedrive://") -> Triple(
            AppIcons.Cloud,
            Color(0xFF0078D4),
            "Microsoft OneDrive"
        ) to "Connected to OneDrive account. This folder is empty."
        lower.startsWith("gdrive://") -> Triple(
            AppIcons.Cloud,
            Color(0xFF0F9D58),
            "Google Drive"
        ) to "Connected to Google Drive. This folder is empty."
        lower.startsWith("dropbox://") -> Triple(
            AppIcons.CloudQueue,
            Color(0xFF0061FF),
            "Dropbox Cloud Storage"
        ) to "Connected to Dropbox account. This folder is empty."
        lower.startsWith("smb://") -> Triple(
            AppIcons.Dns,
            Color(0xFF0284C7),
            "Windows Share (SMB)"
        ) to "Connected to SMB server. This share is empty."
        lower.startsWith("ftp://") || lower.startsWith("ftps://") -> Triple(
            AppIcons.SwapHoriz,
            Color(0xFFEA580C),
            "FTP Server"
        ) to "Connected to FTP server. Directory is empty."
        lower.startsWith("sftp://") -> Triple(
            AppIcons.Terminal,
            Color(0xFF16A34A),
            "SFTP Server (SSH)"
        ) to "Connected to SFTP server. Directory is empty."
        lower.startsWith("webdav://") || lower.startsWith("webdavs://") -> Triple(
            AppIcons.FolderShared,
            Color(0xFF7C3AED),
            "WebDAV Server"
        ) to "Connected to WebDAV server. Directory is empty."
        else -> Triple(
            AppIcons.Cloud,
            MaterialTheme.colorScheme.primary,
            "Remote Storage"
        ) to "Connected to remote service. No files found."
    }.let { (triple, description) ->
        listOf(triple.first, triple.second, triple.third, description)
    }.let {
        @Suppress("UNCHECKED_CAST")
        Tuple4(it[0] as androidx.compose.ui.graphics.vector.ImageVector, it[1] as Color, it[2] as String, it[3] as String)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF22C55E).copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(Color(0xFF22C55E), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF15803D)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            FilledTonalButton(
                onClick = onRefresh,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}
