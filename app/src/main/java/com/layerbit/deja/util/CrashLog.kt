package com.layerbit.deja.util

import android.content.Context
import android.os.Build
import com.layerbit.deja.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * A crash log that never leaves the phone on its own.
 *
 * Deja has no crash reporting by design, which means a failure in the field is invisible unless
 * someone describes it. This keeps the last few stack traces in app-private storage so that, when
 * they do write in, "Report a problem" can attach something useful. Sending it is always the
 * user's own action through their own email app.
 */
object CrashLog {

    private const val FILE = "crash.log"

    /** Enough for several traces; older ones fall off the front. */
    private const val MAX_BYTES = 48 * 1024

    fun install(context: Context) {
        val file = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(file, thread, error) }
            // Hand on to the system handler so the process still dies the normal way.
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE)

    fun hasEntries(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun append(file: File, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            append("=== ").append(Instant.now()).append('\n')
            append("Deja ").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")")
            append(" · Android ").append(Build.VERSION.SDK_INT)
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Thread: ").append(thread.name).append('\n')
            append(trace).append('\n')
        }
        val existing = if (file.exists()) file.readText() else ""
        val combined = existing + entry
        file.writeText(if (combined.length > MAX_BYTES) combined.takeLast(MAX_BYTES) else combined)
    }
}
