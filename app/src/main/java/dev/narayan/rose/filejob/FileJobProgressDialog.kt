package dev.narayan.rose.filejob

import android.content.Context
import dev.narayan.rose.ui.icons.AppIcons
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.narayan.rose.R
import kotlinx.coroutines.delay

@Composable
fun FileJobProgressDialog(activeJobs: List<FileJob>, onDismissRequest: () -> Unit) {
    if (activeJobs.isEmpty()) return

    var confirmingCancelJob by remember { mutableStateOf<FileJob?>(null) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (activeJobs.size > 1) stringResource(R.string.job_active_tasks_count, activeJobs.size) else stringResource(R.string.job_working),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = AppIcons.Close,
                            contentDescription = stringResource(R.string.action_hide),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.job_background_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(activeJobs, key = { it.id }) { job ->
                        JobDetailCard(
                            job = job,
                            onCancel = {
                                JobManager.pauseJob(job.id)
                                confirmingCancelJob = job
                            }
                        )
                    }
                }
            }
        }
    }

    confirmingCancelJob?.let { jobToCancel ->
        AlertDialog(
            onDismissRequest = {
                JobManager.resumeJob(jobToCancel.id)
                confirmingCancelJob = null
            },
            title = {
                Text(
                    text = stringResource(R.string.job_cancel_confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.job_cancel_confirm_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = jobToCancel.id
                        confirmingCancelJob = null
                        JobManager.cancelJob(id)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        JobManager.resumeJob(jobToCancel.id)
                        confirmingCancelJob = null
                    }
                ) {
                    Text(stringResource(R.string.job_action_resume), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun JobDetailCard(job: FileJob, onCancel: () -> Unit) {
    val latestJob by rememberUpdatedState(job)

    var now by remember(job.id) { mutableLongStateOf(System.currentTimeMillis()) }
    var smoothedSpeedBps by remember(job.id) { mutableDoubleStateOf(0.0) }

    LaunchedEffect(job.id) {
        var lastSampleTime = System.currentTimeMillis()
        var lastSampleBytes = latestJob.processedBytes
        while (true) {
            delay(250)
            val nowMs = System.currentTimeMillis()
            if (latestJob.isPaused) {
                lastSampleTime = nowMs
                lastSampleBytes = latestJob.processedBytes
                smoothedSpeedBps = 0.0
                continue
            }
            val sampleBytes = latestJob.processedBytes
            val dtSec = (nowMs - lastSampleTime) / 1000.0
            if (dtSec > 0.05) {
                val instantSpeed = (sampleBytes - lastSampleBytes).coerceAtLeast(0L) / dtSec
                smoothedSpeedBps = if (smoothedSpeedBps <= 0.0) instantSpeed
                else (smoothedSpeedBps * 0.6 + instantSpeed * 0.4)
            }
            lastSampleTime = nowMs
            lastSampleBytes = sampleBytes
            now = nowMs
        }
    }

    val context = LocalContext.current
    val paths = remember(job.type) { jobPaths(job, context) }
    val (icon, titleRes) = jobIconAndTitle(job)

    val elapsedSec = ((now - job.startTime).coerceAtLeast(0L)) / 1000.0
    val remainingBytes = (job.totalBytes - job.processedBytes).coerceAtLeast(0L)
    val etaSec = if (smoothedSpeedBps >= 1.0 && job.totalBytes > 0) remainingBytes / smoothedSpeedBps else null

    val currentProgress = job.progress.coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = Color.White)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (latestJob.isPaused) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(${stringResource(R.string.job_status_paused)})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (job.totalItems > 1) {
                        Text(
                            text = stringResource(R.string.job_item_progress, (job.processedItems + 1).coerceAtMost(job.totalItems), job.totalItems),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!job.isIndeterminate) {
                    Text(
                        text = "${(currentProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (paths.from != null) {
                PathRow(label = stringResource(R.string.job_label_from), value = paths.from)
            }
            if (paths.to != null) {
                Spacer(modifier = Modifier.height(6.dp))
                PathRow(label = stringResource(R.string.job_label_to), value = paths.to)
            }

            if (job.currentFileName.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = job.currentFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (job.isIndeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(currentProgress)
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (job.totalBytes > 0) {
                    stringResource(R.string.job_bytes_progress, formatBytes(job.processedBytes), formatBytes(job.totalBytes))
                } else if (job.processedBytes > 0) {
                    formatBytes(job.processedBytes)
                } else {
                    stringResource(R.string.status_calculating)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatChip(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.Speed,
                    label = stringResource(R.string.job_stat_speed),
                    value = if (latestJob.isPaused) stringResource(R.string.job_status_paused) else if (smoothedSpeedBps >= 1.0) formatSpeed(smoothedSpeedBps) else "—"
                )
                StatChip(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.Timer,
                    label = stringResource(R.string.job_stat_left),
                    value = etaSec?.let { formatDuration(it) } ?: "—"
                )
                StatChip(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.Schedule,
                    label = stringResource(R.string.job_stat_elapsed),
                    value = formatDuration(elapsedSec)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            FilledTonalButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(imageVector = AppIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PathRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatChip(modifier: Modifier = Modifier, icon: ImageVector, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class JobPaths(val from: String?, val to: String?)

private fun sourceFolderPath(sourcePaths: List<String>, context: Context): String? {
    val parents = sourcePaths.mapNotNull { java.io.File(it).parent }.distinct()
    return when {
        parents.size == 1 -> parents[0]
        parents.size > 1 -> context.getString(R.string.job_locations_count, parents.size)
        else -> null
    }
}

private fun jobPaths(job: FileJob, context: Context): JobPaths = when (val type = job.type) {
    is FileJobType.Copy -> JobPaths(
        from = sourceFolderPath(type.sources.map { it.path }, context),
        to = type.targetDir.toString()
    )
    is FileJobType.Move -> JobPaths(
        from = sourceFolderPath(type.sources.map { it.path }, context),
        to = type.targetDir.toString()
    )
    is FileJobType.Delete -> JobPaths(
        from = sourceFolderPath(type.targets.map { it.toString() }, context),
        to = null
    )
    is FileJobType.Download -> {
        val src = type.source.path
        val fromLabel = when {
            dev.narayan.rose.SafManager.isSafUri(src) -> {
                val uri = try { android.net.Uri.parse(dev.narayan.rose.SafManager.normalizeSafPath(src)) } catch (e: Exception) { null }
                val authority = uri?.authority ?: ""
                when {
                    authority.contains("docs", ignoreCase = true) -> "Google Drive"
                    authority.contains("onedrive", ignoreCase = true) -> "OneDrive"
                    authority.contains("dropbox", ignoreCase = true) -> "Dropbox"
                    else -> context.getString(R.string.storage_cloud)
                }
            }
            else -> java.io.File(src).parent ?: type.source.displayName
        }
        JobPaths(
            from = fromLabel,
            to = context.getString(R.string.status_offline_available)
        )
    }
    is FileJobType.Recycle -> JobPaths(
        from = sourceFolderPath(type.sources.map { it.path }, context),
        to = context.getString(R.string.home_section_recycle_bin)
    )
    is FileJobType.Restore -> JobPaths(
        from = context.getString(R.string.home_section_recycle_bin),
        to = job.currentFileName.ifBlank { null }
    )
    is FileJobType.Extract -> JobPaths(
        from = type.source.toString(),
        to = type.targetDir.toString()
    )
    is FileJobType.Compress -> JobPaths(
        from = sourceFolderPath(type.sources.map { it.path }, context),
        to = type.targetFile.toString()
    )
}

@Composable
private fun jobIconAndTitle(job: FileJob): Pair<ImageVector, Int> = when (job.type) {
    is FileJobType.Copy -> AppIcons.ContentCopy to R.string.job_title_copying
    is FileJobType.Move -> AppIcons.DriveFileMove to R.string.job_title_moving
    is FileJobType.Delete -> AppIcons.Delete to R.string.job_title_deleting
    is FileJobType.Download -> AppIcons.Download to R.string.job_title_saving_offline
    is FileJobType.Recycle -> AppIcons.DeleteSweep to R.string.job_title_moving_to_recycle_bin
    is FileJobType.Restore -> AppIcons.RestoreFromTrash to R.string.job_title_restoring
    is FileJobType.Extract -> AppIcons.FolderZip to R.string.job_title_extracting
    is FileJobType.Compress -> AppIcons.FolderZip to R.string.job_title_creating_archive
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

private fun formatSpeed(bytesPerSecond: Double): String = "${formatBytes(bytesPerSecond.toLong())}/s"

private fun formatDuration(totalSeconds: Double): String {
    val seconds = totalSeconds.toLong().coerceAtLeast(0L)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "%ds".format(s)
    }
}