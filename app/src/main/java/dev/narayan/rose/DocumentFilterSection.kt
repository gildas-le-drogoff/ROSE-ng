package dev.narayan.rose

import androidx.compose.ui.res.vectorResource
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import dev.narayan.rose.ui.icons.AppIcons
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.narayan.rose.domain.model.FileItem

enum class DocumentTypeCategory(
    val id: String,
    val label: String,
    @DrawableRes val iconRes: Int,
    val accentColor: Color
) {
    ALL("all", "All", R.drawable.ic_description_fill, Color(0xFF4C9A72)),
    PDF("pdf", "PDF", R.drawable.ic_picture_as_pdf_fill, Color(0xFFE53935)),
    DOC("doc", "DOC", R.drawable.ic_article_fill, Color(0xFF1E88E5)),
    TXT("txt", "TXT", R.drawable.ic_subject, Color(0xFF43A047)),
    XLS("xls", "XLS", R.drawable.ic_table_chart_fill, Color(0xFF2E7D32)),
    PPT("ppt", "PPT", R.drawable.ic_slideshow_fill, Color(0xFFE65100)),
    OTHER("other", "Other", R.drawable.ic_draft_fill, Color(0xFF78909C));

    val icon: ImageVector @Composable get() = ImageVector.vectorResource(iconRes)

    fun matches(item: FileItem): Boolean {
        val ext = item.extension.lowercase()
        val mime = item.mimeType?.lowercase() ?: ""
        return when (this) {
            ALL -> true
            PDF -> ext == "pdf" || mime == "application/pdf"
            DOC -> ext in setOf("doc", "docx", "odt", "rtf", "dot", "dotx", "wpd") ||
                    mime in setOf(
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/rtf",
                        "application/vnd.oasis.opendocument.text"
                    )
            TXT -> (ext in setOf("txt", "text", "log", "md", "markdown", "cfg", "conf", "ini", "xml", "yml", "yaml", "json", "html", "htm", "css", "js", "ts", "sh", "py", "ml", "cpp", "c", "h", "hpp", "cc", "cxx", "java", "kt", "kts", "rs", "go", "rb", "php", "swift", "lua", "sql") ||
                    mime.startsWith("text/")) &&
                    ext !in setOf("csv", "tsv")
            XLS -> ext in setOf("xls", "xlsx", "csv", "tsv", "ods", "xlsm") ||
                    mime in setOf(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/csv",
                        "application/vnd.oasis.opendocument.spreadsheet"
                    )
            PPT -> ext in setOf("ppt", "pptx", "pps", "ppsx", "odp", "pot", "potx") ||
                    mime in setOf(
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/vnd.oasis.opendocument.presentation"
                    )
            OTHER -> !PDF.matches(item) && !DOC.matches(item) && !TXT.matches(item) && !XLS.matches(item) && !PPT.matches(item)
        }
    }
}

fun computeDocumentCategoryCounts(files: List<FileItem>): Map<DocumentTypeCategory, Int> {
    val counts = mutableMapOf<DocumentTypeCategory, Int>()
    DocumentTypeCategory.entries.forEach { counts[it] = 0 }
    counts[DocumentTypeCategory.ALL] = files.size

    for (file in files) {
        for (category in DocumentTypeCategory.entries) {
            if (category != DocumentTypeCategory.ALL && category.matches(file)) {
                counts[category] = (counts[category] ?: 0) + 1
            }
        }
    }
    return counts
}

fun getOrderedDocumentCategories(
    files: List<FileItem>,
    counts: Map<DocumentTypeCategory, Int> = computeDocumentCategoryCounts(files)
): List<DocumentTypeCategory> {
    val subCategories = DocumentTypeCategory.entries.filter { it != DocumentTypeCategory.ALL }

    val sortedSubCategories = subCategories.sortedWith(
        compareByDescending<DocumentTypeCategory> { counts[it] ?: 0 }
            .thenBy { it.ordinal }
    )

    val populatedSubCategories = sortedSubCategories.filter { (counts[it] ?: 0) > 0 }

    return if (populatedSubCategories.isNotEmpty()) {
        listOf(DocumentTypeCategory.ALL) + populatedSubCategories
    } else {
        listOf(
            DocumentTypeCategory.ALL,
            DocumentTypeCategory.PDF,
            DocumentTypeCategory.DOC,
            DocumentTypeCategory.TXT
        )
    }
}

fun filterDocumentFiles(files: List<FileItem>, category: DocumentTypeCategory): List<FileItem> {
    if (category == DocumentTypeCategory.ALL) return files
    return files.filter { category.matches(it) }
}

@Composable
fun DocumentFilterChipsRow(
    categories: List<DocumentTypeCategory>,
    selectedCategory: DocumentTypeCategory,
    onCategorySelected: (DocumentTypeCategory) -> Unit,
    counts: Map<DocumentTypeCategory, Int>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(selectedCategory) {
        val selectedIndex = categories.indexOf(selectedCategory)
        if (selectedIndex >= 0 && categories.size > 1) {
            val maxScroll = scrollState.maxValue
            if (maxScroll > 0) {
                val targetScroll = (maxScroll * (selectedIndex.toFloat() / (categories.size - 1))).toInt()
                scrollState.animateScrollTo(targetScroll)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEach { category ->
            val isSelected = category == selectedCategory
            val count = counts[category] ?: 0

            val animatedBg by animateColorAsState(
                targetValue = if (isSelected) {
                    category.accentColor.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                },
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "DocFilterChipBg"
            )

            val animatedBorderColor by animateColorAsState(
                targetValue = if (isSelected) {
                    category.accentColor
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "DocFilterChipBorder"
            )

            val animatedContentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    category.accentColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "DocFilterChipContent"
            )

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onCategorySelected(category) }
                    .border(
                        BorderStroke(if (isSelected) 1.5.dp else 1.dp, animatedBorderColor),
                        shape = RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                color = animatedBg
            ) {
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = category.label,
                        tint = animatedContentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = animatedContentColor
                    )

                    if (count > 0 || category == DocumentTypeCategory.ALL) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) category.accentColor else MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (count > 999) "999+" else "$count",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
