package com.layerbit.deja.brand

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

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
