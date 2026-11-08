package dev.narayan.rose.data.repository

import android.content.Context
import android.provider.MediaStore
import dev.narayan.rose.R
import dev.narayan.rose.domain.model.FileItem
import dev.narayan.rose.domain.model.FileType
import dev.narayan.rose.domain.model.SearchRequest
import dev.narayan.rose.domain.model.matchesCategory
import dev.narayan.rose.domain.repository.MediaStoreRepository
import dev.narayan.rose.roseNGLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class AndroidMediaStoreRepository(context: Context) : MediaStoreRepository {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val filesUri get() = MediaStore.Files.getContentUri("external")

    private val data = MediaStore.MediaColumns.DATA
    private val mimeType = MediaStore.Files.FileColumns.MIME_TYPE
    private val mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE

    private val noHiddenSelection = "($data NOT LIKE '%/.%' AND $data NOT LIKE '.%')"

    private fun excludeSelection(hardcoded: List<String>, userExcluded: Set<String>): String =
        (hardcoded.map { "$data NOT LIKE '%/$it/%'" } +
                userExcluded.map {
                    val escaped = it.replace("'", "''")
                    "($data NOT LIKE '$escaped/%' AND $data != '$escaped')"
                }).joinToString(" AND ")

    override suspend fun countByCategory(excludedFolders: Set<String>): Map<FileType, Int> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf(
            FileType.IMAGE to 0,
            FileType.VIDEO to 0,
            FileType.AUDIO to 0,
            FileType.PDF to 0,
            FileType.APK to 0,
            FileType.ZIP to 0,
            FileType.DOCUMENT to 0
        )

        val exclude = excludeSelection(listOf(".thumbnails", "Android/data", "Android/obb"), excludedFolders)
        val selection = if (exclude.isNotEmpty()) {
            "$data IS NOT NULL AND ($exclude) AND ($noHiddenSelection)"
        } else {
            "$data IS NOT NULL AND ($noHiddenSelection)"
        }

        fun isDoc(mime: String, ext: String): Boolean {
            if (mime.startsWith("text/", ignoreCase = true) ||
                mime.startsWith("application/vnd.ms-", ignoreCase = true) ||
                mime.startsWith("application/vnd.openxmlformats-officedocument", ignoreCase = true)
            ) return true
            return ext == "txt" || ext == "md" || ext == "markdown" || ext == "pdf" || ext == "rtf" ||
                    ext.startsWith("doc") || ext.startsWith("xls") || ext.startsWith("ppt")
        }

        fun inc(type: FileType) { counts[type] = counts.getValue(type) + 1 }

        try {
            resolver.query(filesUri, arrayOf(data, mimeType, mediaType), selection, null, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndex(data)
                val mimeCol = cursor.getColumnIndex(mimeType)
                val mediaTypeCol = cursor.getColumnIndex(mediaType)

                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    if (path.isEmpty()) continue
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                    val media = if (mediaTypeCol >= 0) cursor.getInt(mediaTypeCol) else 0
                    val ext = path.substringAfterLast('.', "").lowercase()

                    val isSocialExcluded = path.contains("/WhatsApp/Media/", ignoreCase = true) ||
                            path.contains("/Telegram/", ignoreCase = true)

                    if (!isSocialExcluded) {
                        if (media == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || ext in IMAGE_EXTS) inc(FileType.IMAGE)
                        if (media == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || ext in VIDEO_EXTS) inc(FileType.VIDEO)
                        if (media == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO || ext in AUDIO_EXTS) inc(FileType.AUDIO)
                    }
                    if (mime.equals("application/pdf", ignoreCase = true) || ext == "pdf") inc(FileType.PDF)
                    if (mime.equals("application/vnd.android.package-archive", ignoreCase = true) || ext == "apk") inc(FileType.APK)
                    if (ext in ZIP_EXTS) inc(FileType.ZIP)
                    if (isDoc(mime, ext)) inc(FileType.DOCUMENT)
                }
            }
        } catch (e: Throwable) {
            roseNGLog.e(TAG, e) { "Category count query failed" }
        }
        counts
    }

    override suspend fun listCategory(type: FileType, bucketId: String?, excludedFolders: Set<String>): List<FileItem> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<FileItem>()
            val projection = mutableListOf(
                data,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE
            )
            if (type == FileType.IMAGE) {
                projection.add(MediaStore.Images.Media.BUCKET_ID)
                projection.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            }

            var categorySelection = categorySelection(type)
            if (type == FileType.IMAGE && bucketId != null) {
                val safeBucketId = bucketId.replace("'", "''")
                categorySelection = "($categorySelection) AND (${MediaStore.Images.Media.BUCKET_ID} = '$safeBucketId')"
            }

            val hardcodedExcluded = if (type == FileType.ZIP || type == FileType.DOCUMENT || type == FileType.PDF || type == FileType.APK) {
                listOf(".thumbnails", "Android/data", "Android/obb")
            } else {
                listOf("WhatsApp/Media", "Telegram", ".thumbnails", "Android/data", "Android/obb")
            }
            val exclude = excludeSelection(hardcodedExcluded, excludedFolders)
            val selection = if (categorySelection != null) {
                "($categorySelection) AND ($exclude) AND ($noHiddenSelection)"
            } else {
                "($exclude) AND ($noHiddenSelection)"
            }

            val groupAlbums = type == FileType.IMAGE && bucketId == null
            val albumMap = mutableMapOf<String, Pair<String, Int>>()
            val albumLastModified = mutableMapOf<String, Long>()
            val albumThumbnail = mutableMapOf<String, String>()
            val unknownAlbum by lazy { appContext.getString(R.string.album_unknown) }

            resolver.query(filesUri, projection.toTypedArray(), selection, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(data)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bucketIdCol = if (type == FileType.IMAGE) cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID) else -1
                val bucketNameCol = if (type == FileType.IMAGE) cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME) else -1

                while (cursor.moveToNext()) {
                    ensureActive()
                    val path = cursor.getString(dataCol) ?: continue
                    val date = cursor.getLong(dateCol) * 1000
                    val bId = if (bucketIdCol != -1) cursor.getString(bucketIdCol) else null

                    if (groupAlbums) {
                        val id = bId ?: "unknown"
                        val bName = cursor.getString(bucketNameCol) ?: unknownAlbum
                        val current = albumMap.getOrDefault(id, bName to 0)
                        albumMap[id] = bName to (current.second + 1)
                        if (date > (albumLastModified[id] ?: 0L)) {
                            albumLastModified[id] = date
                            albumThumbnail[id] = path
                        }
                        continue
                    }

                    val file = File(path)
                    val name = cursor.getString(nameCol) ?: file.name
                    results.add(
                        FileItem(
                            file = file,
                            isDirectory = false,
                            name = name,
                            size = cursor.getLong(sizeCol),
                            lastModified = date,
                            extension = name.substringAfterLast('.', "").lowercase(),
                            mimeType = cursor.getString(mimeCol),
                            bucketId = bId
                        )
                    )
                }
            }

            if (groupAlbums) {
                albumMap.forEach { (id, pair) ->
                    val thumbPath = albumThumbnail[id]
                    val albumDir = thumbPath?.let { File(it).parentFile }
                    results.add(
                        FileItem(
                            file = albumDir ?: File("album:$id"),
                            isDirectory = true,
                            name = pair.first,
                            size = 0,
                            lastModified = albumLastModified[id] ?: 0L,
                            extension = "",
                            itemCount = pair.second,
                            bucketId = id,
                            thumbnailPath = thumbPath
                        )
                    )
                }
            }
            results
        }

    override suspend fun recentFiles(limit: Int, excludedFolders: Set<String>): List<FileItem> = withContext(Dispatchers.IO) {
        val recents = mutableListOf<FileItem>()
        try {
            val projection = arrayOf(
                data,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE
            )
            resolver.query(filesUri, projection, noHiddenSelection, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(data)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                val excluded = excludedFolders.toList()
                while (cursor.moveToNext() && recents.size < limit) {
                    ensureActive()
                    val path = cursor.getString(dataCol) ?: continue
                    if (excluded.any { path.startsWith("$it/") || path == it }) continue

                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        recents.add(
                            FileItem(
                                file = file,
                                isDirectory = false,
                                name = cursor.getString(nameCol) ?: file.name,
                                size = cursor.getLong(sizeCol),
                                lastModified = cursor.getLong(dateCol) * 1000,
                                extension = file.extension.lowercase(),
                                mimeType = cursor.getString(mimeCol)
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            roseNGLog.e(TAG, e) { "Recent files query failed" }
        }
        recents
    }

    override fun generation(): Long {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return -1L
        return try {
            MediaStore.getExternalVolumeNames(appContext).sumOf { MediaStore.getGeneration(appContext, it) }
        } catch (e: Exception) {
            -1L
        }
    }

    override suspend fun searchByName(request: SearchRequest): List<FileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val conditions = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (request.scopePath.isNotEmpty()) {
            conditions.add("${MediaStore.Files.FileColumns.DATA} LIKE ?")
            selectionArgs.add("${request.scopePath}/%")
        }
        conditions.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
        selectionArgs.add("%${request.query}%")

        request.filterType?.let { searchSelection(it) }?.let { conditions.add(it) }

        if (request.bucketId != null) {
            conditions.add("${MediaStore.Images.Media.BUCKET_ID} = ?")
            selectionArgs.add(request.bucketId)
        }

        conditions.add("$data NOT LIKE '%/Android/data/%'")
        conditions.add("$data NOT LIKE '%/Android/obb/%'")
        if (!request.showHiddenFiles) conditions.add("$data NOT LIKE '%/.%'")

        try {
            resolver.query(
                filesUri,
                projection,
                conditions.joinToString(" AND "),
                selectionArgs.toTypedArray(),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    ensureActive()
                    val path = cursor.getString(dataCol) ?: continue
                    if (path.contains("/Android/data", ignoreCase = true) || path.contains("/Android/obb", ignoreCase = true)) continue

                    val file = File(path)
                    if (file.exists()) {
                        val name = cursor.getString(nameCol) ?: file.name
                        val item = FileItem(
                            file = file,
                            isDirectory = file.isDirectory,
                            name = name,
                            size = cursor.getLong(sizeCol),
                            lastModified = cursor.getLong(dateCol) * 1000,
                            extension = name.substringAfterLast('.', "").lowercase(),
                            mimeType = cursor.getString(mimeCol)
                        )
                        if (request.filterType == null || item.matchesCategory(request.filterType)) results.add(item)
                    }
                    if (results.size >= request.maxResults) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            roseNGLog.e(TAG, e) { "MediaStore search failed for \"${request.query}\"" }
        }
        results
    }

    private fun categorySelection(type: FileType): String? = when (type) {
        FileType.IMAGE -> "$mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR $data LIKE '%.jpg' OR $data LIKE '%.jpeg' OR $data LIKE '%.png' OR $data LIKE '%.webp' OR $data LIKE '%.JPG' OR $data LIKE '%.JPEG'"
        FileType.VIDEO -> "$mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} OR $data LIKE '%.mp4' OR $data LIKE '%.mkv' OR $data LIKE '%.MP4'"
        FileType.AUDIO -> "$mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}"
        FileType.PDF -> "$mimeType = 'application/pdf' OR $data LIKE '%.pdf' OR $data LIKE '%.PDF'"
        FileType.APK -> "$mimeType = 'application/vnd.android.package-archive' OR $data LIKE '%.apk' OR $data LIKE '%.APK'"
        FileType.ZIP -> "$data LIKE '%.zip' OR $data LIKE '%.rar' OR $data LIKE '%.7z' OR $data LIKE '%.tar' OR $data LIKE '%.gz' OR $data LIKE '%.ZIP'"
        FileType.DOCUMENT -> "$mimeType LIKE 'text/%' OR $mimeType = 'text/markdown' OR $mimeType = 'text/x-markdown' OR $mimeType LIKE 'application/vnd.ms-%' OR $mimeType LIKE 'application/vnd.openxmlformats-officedocument%' OR $data LIKE '%.txt' OR $data LIKE '%.md' OR $data LIKE '%.MD' OR $data LIKE '%.markdown' OR $data LIKE '%.doc%' OR $data LIKE '%.xls%' OR $data LIKE '%.ppt%' OR $data LIKE '%.pdf' OR $data LIKE '%.rtf' OR $data LIKE '%.TXT' OR $data LIKE '%.DOC%' OR $data LIKE '%.PDF'"
        else -> null
    }

    private fun searchSelection(type: FileType): String? = when (type) {
        FileType.IMAGE -> "($mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR $data LIKE '%.jpg' OR $data LIKE '%.jpeg' OR $data LIKE '%.png' OR $data LIKE '%.webp')"
        FileType.VIDEO -> "($mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} OR $data LIKE '%.mp4' OR $data LIKE '%.mkv')"
        FileType.AUDIO -> "($mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO} OR $data LIKE '%.mp3' OR $data LIKE '%.m4a')"
        FileType.PDF -> "($mimeType = 'application/pdf' OR $data LIKE '%.pdf')"
        FileType.APK -> "($mimeType = 'application/vnd.android.package-archive' OR $data LIKE '%.apk')"
        FileType.ZIP -> "($data LIKE '%.zip' OR $data LIKE '%.rar' OR $data LIKE '%.7z' OR $data LIKE '%.tar' OR $data LIKE '%.gz')"
        FileType.DOCUMENT -> "($mimeType LIKE 'text/%' OR $mimeType = 'application/pdf' OR $mimeType LIKE 'application/msword' OR $mimeType LIKE 'application/vnd.openxmlformats-officedocument%' OR $data LIKE '%.txt' OR $data LIKE '%.doc%' OR $data LIKE '%.pdf')"
        else -> null
    }

    private companion object {
        const val TAG = "MediaStore"
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "avi", "3gp", "flv", "wmv")
        val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "opus", "m4a", "flac", "aac", "wma", "amr", "3ga")
        val ZIP_EXTS = setOf("zip", "rar", "7z", "tar", "gz")
    }
}
