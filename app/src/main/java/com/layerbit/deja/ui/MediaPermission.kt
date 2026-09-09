package com.layerbit.deja.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class MediaAccess {
    /** Deja can see the whole Screenshots folder. */
    FULL,

    /** Android 14+ "Select photos": only the images the user hand-picked are visible. */
    PARTIAL,

    NONE
}

/**
 * Deja reads images and nothing else.
 *
 * The awkward part is not asking, it is what happens after a refusal. Android stops showing the
 * system dialog once someone has declined twice, so an app that only ever calls `launch()` becomes
 * permanently useless with no way back short of reinstalling. Everything here exists so the UI can
 * tell the three cases apart: never asked, asked and refused but askable again, and refused for
 * good - where the only honest move is to send the user to the system settings page.
 */
object MediaPermission {

    private const val PREFS = "deja_permission"
    private const val KEY_ASKED = "asked_once"

    /** What to hand to the permission launcher. */
    val requested: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES
            )

            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun access(context: Context): MediaAccess {
        if (granted(context, fullPermission)) return MediaAccess.FULL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        ) {
            return MediaAccess.PARTIAL
        }
        return MediaAccess.NONE
    }

    /**
     * True while the system dialog will still appear. Once Android has decided the user means it,
     * `launch()` returns instantly with a denial and the UI has to offer settings instead.
     */
    fun canAskAgain(activity: Activity): Boolean {
        if (!hasAsked(activity)) return true
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, fullPermission)
    }

    fun rememberAsked(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun hasAsked(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)

    /** The app's own settings page, where the permission can always be turned back on. */
    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val fullPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
