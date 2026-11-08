package dev.narayan.rose.ui.explorer

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.ui.navigation.ExplorerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun displayNameFor(fileItem: FileItem, showExtension: Boolean): String {
    if (showExtension || fileItem.isDirectory) return fileItem.name
    val dotIndex = fileItem.name.lastIndexOf('.')
    return if (dotIndex > 0) fileItem.name.substring(0, dotIndex) else fileItem.name
}

data class ExplorerContext(
    val view: ExplorerView,
    val categoryTitle: String?,
    val path: String

)

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

internal val dateFormatter = SimpleDateFormat("d MMMM", Locale.getDefault())

fun formatItemDate(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Calendar.getInstance()
    val itemDate = Calendar.getInstance().apply { time = date }

    return if (now.get(Calendar.YEAR) == itemDate.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == itemDate.get(Calendar.DAY_OF_YEAR)) {
        timeFormatter.format(date)
    } else {
        dateFormatter.format(date)
    }
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var s = size.toDouble()
    var unitIndex = 0
    while (s >= 1024 && unitIndex < units.size - 1) {
        s /= 1024
        unitIndex++
    }
    return "%.1f %s".format(s, units[unitIndex])
}

internal sealed class ListItemType {
    data class Header(val title: String, val count: Int) : ListItemType()
    data class File(val fileItem: FileItem) : ListItemType()
}

@Composable
internal fun DateHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        VerticalDivider(
            modifier = Modifier.height(14.dp),
            thickness = 1.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.items_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun formatDateHeader(context: Context, timestamp: Long): String {
    val now = Calendar.getInstance()
    val itemDate = Calendar.getInstance().apply { timeInMillis = timestamp }

    val nowCalendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val itemCalendar = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val actualDiffDays = ((nowCalendar.timeInMillis - itemCalendar.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()

    return when {
        actualDiffDays == 0 -> context.getString(R.string.date_today)
        actualDiffDays == 1 -> context.getString(R.string.date_yesterday)
        actualDiffDays in 2..3 -> context.getString(R.string.date_days_ago, actualDiffDays)
        else -> SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date(timestamp))
    }
}

internal data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
