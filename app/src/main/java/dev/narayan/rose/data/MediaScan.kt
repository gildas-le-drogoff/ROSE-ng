package dev.narayan.rose.data

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

fun rescanForMediaStore(context: Context, vararg files: File) {
    if (files.isEmpty()) return
    MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null)
}
