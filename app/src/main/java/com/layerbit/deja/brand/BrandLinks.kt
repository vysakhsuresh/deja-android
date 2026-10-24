package com.layerbit.deja.brand

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.layerbit.deja.util.CrashLog
import java.io.File

/**
 * Company-wide links, the same destinations LayerLink's `core` module resolves through. Kept in
 * step by hand rather than shared, since Deja has no dependency on that module.
 *
 * Opening any of these does not contradict the no-internet guarantee: Deja hands a URL to another
 * app through an Intent and that app does the fetching under its own permissions. Deja itself
 * still cannot open a socket, and nothing about the user's screenshots travels with the intent.
 */
object BrandLinks {
    const val WEBSITE_URL = "https://layerbit.co.in"
    /** How the company is named in the UI - never a bare URL. Matches LayerLink's footer. */
    const val BRAND_LABEL = "Layerbit AI"
    const val COFFEE_URL = "https://www.buymeacoffee.com/layerbit"
    const val WHATSAPP_URL = "https://wa.me/916282595823"
    const val SUPPORT_EMAIL = "ceo@layerbit.co.in"
    const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=com.layerbit.deja"

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device can open it - no browser, or WhatsApp not installed. There is
            // nothing sensible to fall back to, so do nothing rather than crash.
        }
    }

    /**
     * Email with the local crash log attached, if there is one. The log is copied to a folder
     * that is the only thing Deja's FileProvider exposes, and read access is granted to the one
     * app the user picks.
     */
    fun reportProblem(context: Context, subject: String, body: String) {
        val log = CrashLog.file(context)
        if (!CrashLog.hasEntries(context)) {
            sendEmail(context, subject, body)
            return
        }
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val copy = File(dir, "deja-crash-log.txt")
        log.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", copy)

        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Email apps only, not every app that accepts a file.
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        try {
            context.startActivity(send)
        } catch (_: ActivityNotFoundException) {
            sendEmail(context, subject, body)
        }
    }

    fun sendEmail(context: Context, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$SUPPORT_EMAIL")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openUrl(context, WEBSITE_URL)
        }
    }
}
