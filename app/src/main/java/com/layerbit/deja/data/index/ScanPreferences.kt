package com.layerbit.deja.data.index

import android.content.Context
import com.layerbit.deja.data.scan.MediaGeneration

/**
 * The little bit of state that decides whether Deja needs to scan at all, and what to do about a
 * scan that never finished.
 *
 * A scan can be interrupted by the user stopping it, by the process dying, or by the system
 * reclaiming the worker. In every one of those cases the index is real but partial, and the right
 * thing on next launch is to ask rather than to silently resume or silently start over.
 */
class ScanPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("deja_scan", Context.MODE_PRIVATE)

    /** True when a scan was started and never reported completion. */
    var interrupted: Boolean
        get() = prefs.getBoolean(KEY_INTERRUPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_INTERRUPTED, value).apply()

    /**
     * The MediaStore version as of the last completed scan. When it still matches, nothing on the
     * device has changed and there is nothing to do.
     */
    var lastGeneration: Long
        get() = prefs.getLong(KEY_GENERATION, MediaGeneration.UNKNOWN)
        set(value) = prefs.edit().putLong(KEY_GENERATION, value).apply()

    /** True when the library is known to be unchanged since the last completed scan. */
    fun isUpToDate(current: Long): Boolean =
        !interrupted &&
            current != MediaGeneration.UNKNOWN &&
            current == lastGeneration

    fun markStarted() {
        prefs.edit().putBoolean(KEY_INTERRUPTED, true).apply()
    }

    fun markFinished(generation: Long) {
        prefs.edit()
            .putBoolean(KEY_INTERRUPTED, false)
            .putLong(KEY_GENERATION, generation)
            .apply()
    }

    /** Forces the next launch to scan, whatever MediaStore says. */
    fun forgetGeneration() {
        prefs.edit().putLong(KEY_GENERATION, MediaGeneration.UNKNOWN).apply()
    }

    private companion object {
        const val KEY_INTERRUPTED = "interrupted"
        const val KEY_GENERATION = "last_generation"
    }
}
