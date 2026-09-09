package com.layerbit.deja.data.index

import android.content.Context

/**
 * Remembers whether the last scan actually finished.
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

    /** Set once the user has answered the resume-or-restart question for this interruption. */
    var resumeAsked: Boolean
        get() = prefs.getBoolean(KEY_RESUME_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_RESUME_ASKED, value).apply()

    fun markStarted() {
        prefs.edit()
            .putBoolean(KEY_INTERRUPTED, true)
            .putBoolean(KEY_RESUME_ASKED, false)
            .apply()
    }

    fun markFinished() {
        prefs.edit()
            .putBoolean(KEY_INTERRUPTED, false)
            .putBoolean(KEY_RESUME_ASKED, false)
            .apply()
    }

    private companion object {
        const val KEY_INTERRUPTED = "interrupted"
        const val KEY_RESUME_ASKED = "resume_asked"
    }
}
