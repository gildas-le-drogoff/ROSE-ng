package dev.narayan.rose

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class RoseApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        roseNGLog.i(TAG) { "Rose ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) starting, verbose=${roseNGLog.verbose}" }
        roseNGLog.timed(TAG, "Shizuku initialisation") {
            ShizukuManager.init(this)
        }
        dev.narayan.rose.data.index.FileNameIndex.init(this)
        roseNGLog.heap(TAG, "After application onCreate")
        CoroutineScope(Dispatchers.IO).launch {
            roseNGLog.timed(TAG, "Startup cache cleanup") {
                CacheCleaner.cleanAllCache(this@RoseApplication)
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(25L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    private companion object {
        const val TAG = "App"
    }
}
