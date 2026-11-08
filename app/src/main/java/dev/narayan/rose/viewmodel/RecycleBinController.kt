package dev.narayan.rose.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.narayan.rose.RecycleBinManager
import dev.narayan.rose.RecycledItem
import dev.narayan.rose.filejob.FileJobService
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecycleBinController(
    private val app: Application,
    private val scope: CoroutineScope
) {
    var recycleBinItems by mutableStateOf<List<RecycledItem>>(emptyList())
        private set

    var recycleBinScrollIndex by mutableIntStateOf(0)
    var recycleBinScrollOffset by mutableIntStateOf(0)

    private var bulkOperationActive = false

    fun loadRecycleBin() {
        if (bulkOperationActive) return
        scope.launch(Dispatchers.IO) {
            val items = roseNGLog.timed(TAG, "List recycle bin", { "${it.size} items" }) {
                RecycleBinManager.listItems(app)
            }
            withContext(Dispatchers.Main) {
                if (!bulkOperationActive) recycleBinItems = items
            }
        }
    }

    fun deleteRecycleBinItemPermanently(item: RecycledItem) {
        recycleBinItems = recycleBinItems.filter { it.id != item.id }
        scope.launch(Dispatchers.IO) {
            RecycleBinManager.deletePermanently(app, item)
        }
    }

    fun deleteRecycleBinItemsPermanently(items: Set<RecycledItem>) = deleteSequentially(items.toList())

    fun emptyRecycleBinPermanently() = deleteSequentially(recycleBinItems.toList())

    private fun deleteSequentially(toDelete: List<RecycledItem>) {
        bulkOperationActive = true
        scope.launch(Dispatchers.Main) {
            roseNGLog.timed(TAG, "Delete ${toDelete.size} recycled item(s)") {
                toDelete.forEach { item ->
                    withContext(Dispatchers.IO) { RecycleBinManager.deletePermanently(app, item) }
                    recycleBinItems = recycleBinItems.filter { it.id != item.id }
                    delay(10)
                }
            }
            bulkOperationActive = false
        }
    }

    fun restoreRecycleBinItems(context: Context, items: Set<RecycledItem>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }.toSet()
        val names = items.map { it.originalName }
        bulkOperationActive = true

        recycleBinItems = recycleBinItems.filter { it.id !in ids }

        FileJobService.startRestore(context, ids.toList(), names)

        scope.launch {
            delay(120_000)
            if (bulkOperationActive) {
                roseNGLog.w(TAG) { "Restore watchdog fired, reloading recycle bin" }
                onBinJobFinished()
            }
        }
    }

    fun onBinJobFinished() {
        bulkOperationActive = false
        loadRecycleBin()
    }

    private companion object {
        const val TAG = "RecycleBin"
    }
}
