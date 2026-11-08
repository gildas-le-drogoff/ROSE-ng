package dev.narayan.rose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import android.net.Uri
import dev.narayan.rose.domain.model.FileType

object RestrictedThumbnailLoader {

    private const val TAG = "RestrictedThumbLoader"
    private const val TARGET_SIZE = 160

    private val memoryCache = object : LruCache<String, Bitmap>(80) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    suspend fun loadThumbnail(context: Context, path: String, fileType: FileType): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${path}_$fileType"
        val cached = memoryCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        val bitmap = when (fileType) {
            FileType.IMAGE -> loadImageThumbnail(context, path)
            FileType.VIDEO -> loadVideoThumbnail(context, path)
            else -> null
        }

        if (bitmap != null) {
            memoryCache.put(cacheKey, bitmap)
        }
        bitmap
    }

    private fun loadImageThumbnail(context: Context, path: String): Bitmap? {
        if (SafManager.isSafUri(path)) {
            try {
                val uri = Uri.parse(SafManager.normalizeSafPath(path))
                val thumb = android.provider.DocumentsContract.getDocumentThumbnail(
                    context.contentResolver, uri, android.graphics.Point(TARGET_SIZE, TARGET_SIZE), null
                )
                if (thumb != null) return scaleBitmap(thumb, TARGET_SIZE, TARGET_SIZE)
            } catch (e: Throwable) {}
        }

        if (SafManager.hasPermission(context, path)) {
            val uri = SafManager.getContentUri(context, path)
            if (uri != null) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        val sampled = decodeSampledBitmapFromByteArray(bytes, TARGET_SIZE, TARGET_SIZE)
                        if (sampled != null) return sampled
                    }
                } catch (e: Throwable) {
                    roseNGLog.w(TAG, e) { "SAF image thumbnail failed for $path" }
                }
            }
        }

        if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
            val pfd = ShizukuManager.openFileDescriptor(context, path, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            if (pfd != null) {
                try {
                    pfd.use {
                        val sampled = decodeSampledBitmapFromFd(it.fileDescriptor, TARGET_SIZE, TARGET_SIZE)
                        if (sampled != null) return sampled
                    }
                } catch (e: Throwable) {
                    roseNGLog.w(TAG, e) { "Shizuku direct image thumbnail failed for $path" }
                }
            }

            try {
                val clean = ShizukuManager.normalize(path)
                val escaped = ShizukuManager.shellEscape(clean)
                val proc = ShizukuManager.newProcess(arrayOf("sh", "-c", "cat $escaped"), null, null)
                val bytes = proc.inputStream.use { it.readBytes() }
                proc.waitFor()
                val sampled = decodeSampledBitmapFromByteArray(bytes, TARGET_SIZE, TARGET_SIZE)
                if (sampled != null) return sampled
            } catch (e: Throwable) {
                roseNGLog.w(TAG, e) { "Shizuku image thumbnail failed for $path" }
            }
        }

        return null
    }

    private fun loadVideoThumbnail(context: Context, path: String): Bitmap? {
        if (SafManager.isSafUri(path)) {
            try {
                val uri = Uri.parse(SafManager.normalizeSafPath(path))
                val thumb = android.provider.DocumentsContract.getDocumentThumbnail(
                    context.contentResolver, uri, android.graphics.Point(TARGET_SIZE, TARGET_SIZE), null
                )
                if (thumb != null) return scaleBitmap(thumb, TARGET_SIZE, TARGET_SIZE)
            } catch (e: Throwable) {}
        }

        val retriever = MediaMetadataRetriever()
        var tempFile: File? = null
        try {
            if (SafManager.hasPermission(context, path)) {
                val uri = SafManager.getContentUri(context, path)
                if (uri != null) {
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                            val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.frameAtTime
                            if (frame != null) {
                                return scaleBitmap(frame, TARGET_SIZE, TARGET_SIZE)
                            }
                        }
                    } catch (e: Throwable) {
                        roseNGLog.w(TAG, e) { "SAF video thumbnail failed for $path" }
                    }

                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tempFile = File.createTempFile("thumb_vid_saf_", ".tmp", context.cacheDir)
                            tempFile!!.outputStream().use { output ->
                                val buffer = ByteArray(16384)
                                var total = 0
                                val limit = 4 * 1024 * 1024
                                while (total < limit) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    total += read
                                }
                            }
                            if (tempFile!!.exists() && tempFile!!.length() > 0) {
                                retriever.setDataSource(tempFile!!.absolutePath)
                                val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: retriever.frameAtTime
                                if (frame != null) {
                                    return scaleBitmap(frame, TARGET_SIZE, TARGET_SIZE)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        roseNGLog.w(TAG, e) { "SAF video stream thumbnail failed for $path" }
                    }
                }
            }

            if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                val pfd = ShizukuManager.openFileDescriptor(context, path, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                if (pfd != null) {
                    try {
                        pfd.use {
                            retriever.setDataSource(it.fileDescriptor)
                            val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.frameAtTime
                            if (frame != null) {
                                return scaleBitmap(frame, TARGET_SIZE, TARGET_SIZE)
                            }
                        }
                    } catch (e: Throwable) {
                        roseNGLog.w(TAG, e) { "Shizuku direct video thumbnail failed for $path" }
                    }
                }

                val clean = ShizukuManager.normalize(path)
                val escaped = ShizukuManager.shellEscape(clean)
                tempFile = File.createTempFile("thumb_vid_", ".tmp", context.cacheDir)
                val escapedTemp = ShizukuManager.shellEscape(tempFile!!.absolutePath)
                val proc = ShizukuManager.newProcess(arrayOf("sh", "-c", "head -c 8388608 $escaped > $escapedTemp"), null, null)
                proc.waitFor()

                if (tempFile!!.exists() && tempFile!!.length() > 0) {
                    retriever.setDataSource(tempFile!!.absolutePath)
                    val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                    if (frame != null) {
                        return scaleBitmap(frame, TARGET_SIZE, TARGET_SIZE)
                    }
                }
            }
        } catch (e: Throwable) {
            roseNGLog.w(TAG, e) { "Video thumbnail failed for $path" }
        } finally {
            try {
                retriever.release()
            } catch (ignored: Throwable) {}
            tempFile?.let {
                runCatching { it.delete() }
            }
        }
        return null
    }

    private fun decodeSampledBitmapFromFd(fd: java.io.FileDescriptor, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFileDescriptor(fd, null, options)

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val decoded = BitmapFactory.decodeFileDescriptor(fd, null, options) ?: return null
            scaleBitmap(decoded, reqWidth, reqHeight)
        } catch (e: Throwable) {
            null
        }
    }

    private fun decodeSampledBitmapFromByteArray(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            scaleBitmap(decoded, reqWidth, reqHeight)
        } catch (e: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (bitmap.width <= maxW && bitmap.height <= maxH) return bitmap
        val ratio = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}
