package com.layerbit.deja.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

object Clipboard {

    /**
     * Copies [value], flagging it as sensitive when it is one.
     *
     * The flag is what stops Android 13+ from previewing a one-time code or a password in the
     * "copied" toast and the keyboard's clipboard strip - which would put on screen exactly the
     * value Deja was careful to mask.
     */
    fun copy(context: Context, label: String, value: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ClipDescription.EXTRA_IS_SENSITIVE
                } else {
                    "android.content.extra.IS_SENSITIVE"
                }
                putBoolean(key, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    /** Android 13 and later confirm a copy themselves; before that the app has to. */
    val systemConfirmsCopy: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
