package com.layerbit.deja.ambient

import android.content.Context

/**
 * Whether Deja may tell you what it read in a screenshot you just took.
 *
 * Off until switched on from the Privacy screen. A notification is the one thing Deja does that
 * reaches outside its own window, so it is something to ask for, never a default.
 */
class AmbientPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("deja_ambient", Context.MODE_PRIVATE)

    var notifyNewShots: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY, value).apply()

    private companion object {
        const val KEY_NOTIFY = "notify_new_shots"
    }
}
