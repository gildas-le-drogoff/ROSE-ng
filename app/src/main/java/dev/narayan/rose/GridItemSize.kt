package dev.narayan.rose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class GridItemSize(val cellMinSize: Dp, val iconSize: Dp) {
    SMALL(84.dp, 48.dp),
    MEDIUM(110.dp, 64.dp),
    LARGE(150.dp, 88.dp)
}
