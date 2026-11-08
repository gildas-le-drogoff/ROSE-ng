package dev.narayan.rose.domain.model

import java.io.File

data class FileItem(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val extension: String,
    val mimeType: String? = null,
    val itemCount: Int? = null,
    val virtualZipSource: File? = null,
    val zipEntryPath: String? = null,
    val bucketId: String? = null,
    val thumbnailPath: String? = null,
    val isEncrypted: Boolean = false
) {
    constructor(file: File, itemCount: Int? = null, virtualZipSource: File? = null) : this(
        file = file,
        isDirectory = file.isDirectory,
        name = file.name,
        size = if (file.isFile) file.length() else 0,
        lastModified = file.lastModified(),
        extension = file.extension.lowercase(),
        mimeType = null,
        itemCount = itemCount,
        virtualZipSource = virtualZipSource
    )

    val fileType: FileType = when {
        isDirectory -> FileType.FOLDER
        extension == "apk" || mimeType == "application/vnd.android.package-archive" -> FileType.APK
        extension in ZIP_EXTENSIONS || mimeType in ZIP_MIME_TYPES -> FileType.ZIP
        extension == "pdf" || mimeType == "application/pdf" -> FileType.PDF
        extension in IMAGE_EXTENSIONS || mimeType?.startsWith("image/") == true -> FileType.IMAGE
        extension in VIDEO_EXTENSIONS || mimeType?.startsWith("video/") == true -> FileType.VIDEO
        extension in AUDIO_EXTENSIONS || mimeType?.startsWith("audio/") == true -> FileType.AUDIO
        extension in DOCUMENT_EXTENSIONS || mimeType?.startsWith("text/") == true || mimeType in DOCUMENT_MIME_TYPES -> FileType.DOCUMENT
        else -> FileType.OTHER
    }
}

private val ZIP_EXTENSIONS = hashSetOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "7-zip")
private val ZIP_MIME_TYPES = hashSetOf("application/zip", "application/x-7z-compressed", "application/x-rar-compressed", "application/x-tar", "application/gzip", "application/x-bzip2", "application/x-xz", "application/x-iso9660-image")
private val IMAGE_EXTENSIONS = hashSetOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXTENSIONS = hashSetOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm")
private val AUDIO_EXTENSIONS = hashSetOf("mp3", "wav", "ogg", "opus", "flac", "m4a", "aac", "wma", "amr", "3ga")
private val DOCUMENT_EXTENSIONS = hashSetOf("txt", "md", "markdown", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "html", "htm", "xml", "json", "yml", "yaml", "css", "js", "ts", "sh", "py", "csv", "ml", "cpp", "c", "h", "hpp", "cc", "cxx", "java", "kt", "kts", "rs", "go", "rb", "php", "swift", "lua", "sql")
private val DOCUMENT_MIME_TYPES = hashSetOf("text/markdown", "text/x-markdown", "text/csv", "application/json", "application/xml", "application/javascript", "text/javascript", "application/x-sh", "application/x-yaml", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

enum class FileType {
    FOLDER, APK, ZIP, PDF, IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER
}

fun FileItem.matchesCategory(type: FileType): Boolean {
    return when (type) {
        FileType.DOCUMENT -> this.fileType == FileType.DOCUMENT || this.fileType == FileType.PDF
        FileType.IMAGE -> this.fileType == FileType.IMAGE
        FileType.VIDEO -> this.fileType == FileType.VIDEO
        FileType.AUDIO -> this.fileType == FileType.AUDIO
        FileType.APK -> this.fileType == FileType.APK
        FileType.ZIP -> this.fileType == FileType.ZIP
        else -> this.fileType == type
    }
}