package dev.narayan.rose.ui.explorer

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.input.*
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.narayan.rose.*
import dev.narayan.rose.domain.model.FileItem
import java.util.*

@Stable
class CheckboxDragSelectState {
    val itemBounds = mutableMapOf<String, Rect>()
    var lastToggledKey: String? = null
    var dragPosition by mutableStateOf<Offset?>(null)
    var containerBounds by mutableStateOf<Rect?>(null)

    var startIndex: Int = -1
    val initialSelectedPaths = mutableSetOf<String>()
    var selectTarget: Boolean = true
    var lastTargetIndex: Int = -1
}

@Composable
internal fun rememberCheckboxDragSelectState() = remember { CheckboxDragSelectState() }

internal suspend fun PointerInputScope.detectCheckboxDragSelect(
    dragSelectState: CheckboxDragSelectState,
    displayedFiles: List<FileItem>,
    viewModel: RoseViewModel
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val containerOrigin = dragSelectState.containerBounds?.topLeft ?: Offset.Zero
        val windowDownPos = containerOrigin + down.position
        val originKey = dragSelectState.itemBounds.entries
            .firstOrNull { (_, rect) -> rect.contains(windowDownPos) }
            ?.key
            ?: return@awaitEachGesture

        val touchSlop = viewConfiguration.touchSlop
        var dragging = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            if (!dragging) {
                if (!change.pressed) break
                if ((change.position - down.position).getDistance() < touchSlop) continue

                dragging = true
                change.consume()

                val startIdx = displayedFiles.indexOfFirst { it.file.absolutePath == originKey }
                if (startIdx == -1) break

                dragSelectState.startIndex = startIdx
                dragSelectState.lastTargetIndex = startIdx
                dragSelectState.selectTarget = !viewModel.selectedFiles.any { it.file.absolutePath == originKey }
                dragSelectState.initialSelectedPaths.clear()
                viewModel.selectedFiles.forEach { dragSelectState.initialSelectedPaths.add(it.file.absolutePath) }

                viewModel.setSelected(displayedFiles[startIdx], dragSelectState.selectTarget)
                dragSelectState.dragPosition = containerOrigin + change.position
                continue
            }

            change.consume()
            if (!change.pressed) break

            val windowPos = containerOrigin + change.position
            dragSelectState.dragPosition = windowPos

            performDragSelect(dragSelectState, displayedFiles, viewModel)
        }

        dragSelectState.startIndex = -1
        dragSelectState.lastTargetIndex = -1
        dragSelectState.dragPosition = null
    }
}

internal fun performDragSelect(
    state: CheckboxDragSelectState,
    files: List<FileItem>,
    viewModel: RoseViewModel
) {
    val pos = state.dragPosition ?: return
    val startIndex = state.startIndex
    if (startIndex == -1) return

    var targetIndex = -1

    val itemUnderFinger = state.itemBounds.entries.firstOrNull { it.value.contains(pos) }
    if (itemUnderFinger != null) {
        targetIndex = files.indexOfFirst { it.file.absolutePath == itemUnderFinger.key }
    } else {
        val itemsInRow = files.mapIndexedNotNull { index, item ->
            val rect = state.itemBounds[item.file.absolutePath]
            if (rect != null && pos.y >= rect.top && pos.y <= rect.bottom) index to rect else null
        }

        if (itemsInRow.isNotEmpty()) {
            targetIndex = itemsInRow.minByOrNull { (_, rect) ->
                when {
                    pos.x < rect.left -> rect.left - pos.x
                    pos.x > rect.right -> pos.x - rect.right
                    else -> 0f
                }
            }?.first ?: -1
        } else {
            val visibleIndices = files.mapIndexedNotNull { index, item ->
                if (state.itemBounds.containsKey(item.file.absolutePath)) index else null
            }
            if (visibleIndices.isNotEmpty()) {
                val firstVisible = visibleIndices.first()
                val lastVisible = visibleIndices.last()
                val firstRect = state.itemBounds[files[firstVisible].file.absolutePath]!!
                val lastRect = state.itemBounds[files[lastVisible].file.absolutePath]!!

                if (pos.y < firstRect.top) targetIndex = firstVisible
                else if (pos.y > lastRect.bottom) targetIndex = lastVisible
            }
        }
    }

    if (targetIndex != -1 && targetIndex != state.lastTargetIndex) {
        state.lastTargetIndex = targetIndex
        viewModel.setSelectedRange(
            files,
            minOf(startIndex, targetIndex),
            maxOf(startIndex, targetIndex),
            state.selectTarget,
            state.initialSelectedPaths
        )
    }
}
