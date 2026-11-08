package dev.narayan.rose.filejob

import java.nio.file.Path
import java.util.UUID

sealed class FileJobType {
    data class Copy(val sources: List<SourcePath>, val targetDir: Path) : FileJobType()
    data class Move(val sources: List<SourcePath>, val targetDir: Path) : FileJobType()
    data class Delete(val targets: List<Path>) : FileJobType()
    data class Download(val source: SourcePath, val targetFile: Path) : FileJobType()
    data class Recycle(val sources: List<SourcePath>) : FileJobType()
    data class Restore(val sources: List<SourcePath>) : FileJobType()
    data class Extract(val source: Path, val targetDir: Path, val entries: List<String>? = null, val passphrase: String? = null) : FileJobType()
    data class Compress(val sources: List<SourcePath>, val targetFile: Path, val passphrase: String? = null) : FileJobType()
}

data class SourcePath(
    val path: String,
    val displayName: String
)

data class FileJob(
    val id: String = UUID.randomUUID().toString(),
    val type: FileJobType,
    var totalItems: Int,
    var processedItems: Int = 0,
    var currentFileName: String = "",
    val completedPaths: MutableSet<String> = mutableSetOf(),
    val createdPaths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
    val failedItems: MutableList<FileOperationError> = java.util.Collections.synchronizedList(mutableListOf()),
    var progress: Float = 0f,
    var totalBytes: Long = 0L,
    var processedBytes: Long = 0L,
    var isPaused: Boolean = false,
    var isIndeterminate: Boolean = false,
    val startTime: Long = System.currentTimeMillis()
)
