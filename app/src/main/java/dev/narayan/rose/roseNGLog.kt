@file:Suppress("ObjectPropertyName")

package dev.narayan.rose

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

@Suppress("ClassName")
object roseNGLog {

    @PublishedApi
    internal const val PREFIX = "roseNG."

    @Volatile
    @JvmStatic
    var verbose: Boolean = BuildConfig.DEBUG

    @PublishedApi
    internal fun tagOf(tag: String): String = PREFIX + tag

    inline fun d(tag: String, message: () -> String) {
        if (verbose) Log.d(tagOf(tag), message())
    }

    inline fun i(tag: String, message: () -> String) {
        Log.i(tagOf(tag), message())
    }

    inline fun w(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (throwable != null) Log.w(tagOf(tag), message(), throwable) else Log.w(tagOf(tag), message())
    }

    inline fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (throwable != null) Log.e(tagOf(tag), message(), throwable) else Log.e(tagOf(tag), message())
    }

    inline fun <T> timed(
        tag: String,
        step: String,
        noinline describe: ((T) -> String)? = null,
        block: () -> T
    ): T {
        d(tag) { "▶ $step" }
        val start = SystemClock.elapsedRealtime()
        val result = try {
            block()
        } catch (c: CancellationException) {
            d(tag) { "⏹ $step annulé après ${SystemClock.elapsedRealtime() - start} ms" }
            throw c
        } catch (t: Throwable) {
            e(tag, t) { "✖ $step échoué après ${SystemClock.elapsedRealtime() - start} ms" }
            throw t
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        val suffix = describe?.let { " — ${it(result)}" }.orEmpty()
        if (elapsed >= SLOW_THRESHOLD_MS) {
            i(tag) { "✔ $step en $elapsed ms$suffix" }
        } else {
            d(tag) { "✔ $step en $elapsed ms$suffix" }
        }
        return result
    }

    fun heap(tag: String, label: String) {
        if (!verbose) return
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val maxMb = rt.maxMemory() / MB
        val nativeMb = Debug.getNativeHeapAllocatedSize() / MB
        Log.d(tagOf(tag), "[heap] $label — java ${usedMb}/${maxMb} MiB, natif $nativeMb MiB")
    }

    const val SLOW_THRESHOLD_MS = 300L

    private const val MB = 1024L * 1024L
}
