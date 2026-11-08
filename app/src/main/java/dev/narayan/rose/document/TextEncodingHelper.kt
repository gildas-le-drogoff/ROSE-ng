package dev.narayan.rose.document

import android.content.Context
import android.net.Uri
import dev.narayan.rose.data.rescanForMediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object TextEncodingHelper {

    const val MAX_EDIT_BYTES = 5L * 1024L * 1024L

    data class LoadedText(
        val content: String,
        val charsetName: String,
        val lineEnding: LineEnding,
        val byteSize: Long,
        val isTruncated: Boolean = false,
        val hasBom: Boolean = false
    )

    fun readText(inputStream: InputStream, maxBytes: Long = MAX_EDIT_BYTES): LoadedText {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var totalRead = 0L
        var isTruncated = false

        while (true) {
            val toRead = if (maxBytes > 0) {
                val remaining = maxBytes - totalRead
                if (remaining <= 0) {
                    isTruncated = true
                    break
                }
                buffer.size.toLong().coerceAtMost(remaining).toInt()
            } else {
                buffer.size
            }

            val read = inputStream.read(buffer, 0, toRead)
            if (read == -1) break
            baos.write(buffer, 0, read)
            totalRead += read
        }

        if (!isTruncated && inputStream.read() != -1) {
            isTruncated = true
            totalRead++
        }

        val bytes = baos.toByteArray()
        val byteSize = totalRead

        if (bytes.isEmpty()) {
            return LoadedText(
                content = "",
                charsetName = "UTF-8",
                lineEnding = LineEnding.LF,
                byteSize = 0L,
                isTruncated = false,
                hasBom = false
            )
        }

        var offset = 0
        var detectedCharset: Charset? = null
        var hasBom = false

        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            detectedCharset = StandardCharsets.UTF_8
            offset = 3
            hasBom = true
        } else if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            detectedCharset = StandardCharsets.UTF_16BE
            offset = 2
            hasBom = true
        } else if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            detectedCharset = StandardCharsets.UTF_16LE
            offset = 2
            hasBom = true
        }

        if (detectedCharset == null) {
            val byteBuffer = ByteBuffer.wrap(bytes)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

            detectedCharset = try {
                decoder.decode(byteBuffer)
                StandardCharsets.UTF_8
            } catch (e: Exception) {
                StandardCharsets.ISO_8859_1
            }
        }

        val textLength = bytes.size - offset
        val decoded = String(bytes, offset, textLength, detectedCharset)
        val lineEnding = LineEnding.detect(decoded)

        return LoadedText(
            content = decoded,
            charsetName = detectedCharset.name(),
            lineEnding = lineEnding,
            byteSize = byteSize,
            isTruncated = isTruncated,
            hasBom = hasBom
        )
    }

    fun normalizeLineEndings(text: String, lineEnding: LineEnding): String {
        val standardLf = text.replace("\r\n", "\n").replace("\r", "\n")
        return if (lineEnding == LineEnding.LF) {
            standardLf
        } else {
            standardLf.replace("\n", lineEnding.separator)
        }
    }

    fun saveFile(
        context: Context,
        targetFile: File,
        content: String,
        charsetName: String = "UTF-8",
        lineEnding: LineEnding = LineEnding.LF,
        includeBom: Boolean = false
    ): Result<Unit> {
        return try {
            val parentDir = targetFile.parentFile ?: context.cacheDir
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }

            val tempFile = File(parentDir, ".${targetFile.name}.${System.currentTimeMillis()}.tmp")
            val normalized = normalizeLineEndings(content, lineEnding)
            val charset = try { Charset.forName(charsetName) } catch (e: Exception) { StandardCharsets.UTF_8 }
            val textBytes = normalized.toByteArray(charset)

            FileOutputStream(tempFile).use { fos ->
                if (includeBom && charset == StandardCharsets.UTF_8) {
                    fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                }
                fos.write(textBytes)
                fos.flush()
                fos.channel.force(true)
            }

            var backupFile: File? = null
            try {
                var moved = false
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        Files.move(
                            tempFile.toPath(),
                            targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                        moved = true
                    } catch (e: Exception) {
                        try {
                            Files.move(
                                tempFile.toPath(),
                                targetFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                            moved = true
                        } catch (e2: Exception) {
                            moved = false
                        }
                    }
                }

                if (!moved) {
                    if (!tempFile.renameTo(targetFile)) {
                        if (targetFile.exists()) {
                            val backup = File(parentDir, ".${targetFile.name}.${System.currentTimeMillis()}.bak")
                            if (targetFile.renameTo(backup)) {
                                backupFile = backup
                            }
                        }
                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.inputStream().use { input ->
                                targetFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
                backupFile?.delete()
                tempFile.delete()
            } catch (e: Exception) {
                if (backupFile != null && backupFile.exists() && !targetFile.exists()) {
                    backupFile.renameTo(targetFile)
                }
                tempFile.delete()
                throw e
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
                runCatching { if (backupFile != null && backupFile.exists() && targetFile.exists()) backupFile.delete() }
            }

            rescanForMediaStore(context, targetFile)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun saveUri(
        context: Context,
        targetUri: Uri,
        content: String,
        charsetName: String = "UTF-8",
        lineEnding: LineEnding = LineEnding.LF,
        includeBom: Boolean = false
    ): Result<Unit> {
        return try {
            val normalized = normalizeLineEndings(content, lineEnding)
            val charset = try { Charset.forName(charsetName) } catch (e: Exception) { StandardCharsets.UTF_8 }
            val textBytes = normalized.toByteArray(charset)

            context.contentResolver.openOutputStream(targetUri, "wt")?.use { os ->
                if (includeBom && charset == StandardCharsets.UTF_8) {
                    os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                }
                os.write(textBytes)
                os.flush()
            } ?: throw IOException("Could not open stream for $targetUri")

            if (targetUri.scheme == "file" && targetUri.path != null) {
                rescanForMediaStore(context, File(targetUri.path!!))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
