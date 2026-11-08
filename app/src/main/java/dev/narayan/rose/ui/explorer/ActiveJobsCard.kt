package dev.narayan.rose.ui.explorer

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import dev.narayan.rose.*
import dev.narayan.rose.R
import dev.narayan.rose.filejob.FileJob
import dev.narayan.rose.filejob.FileJobType
import dev.narayan.rose.ui.icons.AppIcons
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveJobsCard(activeJobs: List<FileJob>, onClick: () -> Unit = {}) {
    val job = activeJobs.firstOrNull() ?: return
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (job.isIndeterminate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                val operationText = when (job.type) {
                    is FileJobType.Copy -> stringResource(R.string.job_copying_files_ellipsis)
                    is FileJobType.Move -> stringResource(R.string.job_moving_files_ellipsis)
                    is FileJobType.Download -> stringResource(R.string.job_saving_offline_ellipsis)
                    is FileJobType.Delete -> stringResource(R.string.job_deleting_files_ellipsis)
                    is FileJobType.Recycle -> stringResource(R.string.job_moving_to_bin_ellipsis)
                    is FileJobType.Restore -> stringResource(R.string.job_restoring_files_ellipsis)
                    is FileJobType.Extract -> stringResource(R.string.job_extracting_archive_ellipsis)
                    is FileJobType.Compress -> stringResource(R.string.job_creating_archive_ellipsis)
                    else -> stringResource(R.string.job_processing_ellipsis)
                }
                Text(
                    text = operationText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                val progressText = if (job.isIndeterminate) {
                    val bytesLabel = when {
                        job.processedBytes >= 1024 * 1024 -> "%.1f MB".format(job.processedBytes / (1024.0 * 1024.0))
                        job.processedBytes > 0 -> "%.0f KB".format(job.processedBytes / 1024.0)
                        else -> null
                    }
                    if (bytesLabel != null) stringResource(R.string.job_downloading_with_bytes, bytesLabel) else stringResource(R.string.job_downloading)
                } else if (job.totalItems > 1) {
                    stringResource(R.string.job_progress_items_and_percent, job.processedItems + 1, job.totalItems, (job.progress * 100).toInt())
                } else {
                    stringResource(R.string.job_percent_completed, (job.progress * 100).toInt())
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (activeJobs.size > 1) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        "+${activeJobs.size - 1}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Icon(
                imageVector = AppIcons.KeyboardArrowUp,
                contentDescription = stringResource(R.string.content_desc_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp)
            )
        }
    }
}
