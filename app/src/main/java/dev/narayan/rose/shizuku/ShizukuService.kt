package dev.narayan.rose.shizuku

import dev.narayan.rose.roseNGLog

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File
import kotlin.system.exitProcess

@Keep
class ShizukuService : IShizukuService.Stub {

    constructor() : super()

    constructor(context: Context) : super()

    override fun destroy() {
        roseNGLog.d(TAG) { "ShizukuService destroyed" }
        exitProcess(0)
    }

    override fun openFile(path: String, mode: Int): ParcelFileDescriptor? {
        return try {
            val file = File(path)
            ParcelFileDescriptor.open(file, mode)
        } catch (e: Throwable) {
            roseNGLog.e(TAG, e) { "Failed to open file: $path with mode: $mode" }
            null
        }
    }

    override fun exists(path: String): Boolean {
        return try {
            File(path).exists()
        } catch (e: Throwable) {
            false
        }
    }

    override fun getFileSize(path: String): Long {
        return try {
            File(path).length()
        } catch (e: Throwable) {
            0L
        }
    }

    companion object {
        private const val TAG = "ShizukuService"
    }
}
