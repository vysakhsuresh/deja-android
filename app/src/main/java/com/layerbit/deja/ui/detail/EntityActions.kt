package com.layerbit.deja.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.layerbit.deja.data.model.Extracted
import com.layerbit.deja.data.model.EntityType

/**
 * What you can do with a thing Deja found, beyond looking at it.
 *
 * This is the whole argument for the app over a gallery. A gallery can show you the screenshot
 * with the number in it; it cannot dial the number. Everything below is a hand-off to an app that
 * already does the job under its own permissions - Deja has no network, no dialler and no
 * contacts access of its own, and starting an intent asks for none of those.
 */
data class EntityAction(
    val label: String,
    /** True for the action that is almost always what someone wants, so it can lead the row. */
    val primary: Boolean = false,
    val perform: () -> Unit
)

object EntityActions {

    /** The one action whose result is invisible unless the UI says so. Shared, not duplicated. */
    const val COPY = "Copy"

    /**
     * @param onFind runs a Deja search for the value. Pulled out as a callback rather than an
     *   intent because it is the one action that stays inside the app - and it is the one a
     *   gallery could never offer: every other screenshot that mentions the same booking
     *   reference, account or handle, found by text rather than by date.
     */
    fun forItem(context: Context, item: Extracted, onFind: (String) -> Unit): List<EntityAction> {
        val value = item.value.trim()
        if (value.isEmpty()) return emptyList()

        val actions = mutableListOf<EntityAction>()

        when (item.type) {
            EntityType.PHONE -> {
                val dialable = value.filter { it.isDigit() || it == '+' }
                if (dialable.length >= 6) {
                    actions += EntityAction("Call", primary = true) {
                        context.start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialable")))
                    }
                    actions += EntityAction("Message") {
                        context.start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$dialable")))
                    }
                    actions += EntityAction("Save contact") {
                        context.start(
                            Intent(ContactsContract.Intents.Insert.ACTION)
                                .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                                .putExtra(ContactsContract.Intents.Insert.PHONE, dialable)
                        )
                    }
                }
            }

            EntityType.EMAIL -> {
                actions += EntityAction("Email", primary = true) {
                    context.start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$value")))
                }
                actions += EntityAction("Save contact") {
                    context.start(
                        Intent(ContactsContract.Intents.Insert.ACTION)
                            .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.EMAIL, value)
                    )
                }
            }

            EntityType.LINK -> {
                val url = if (value.startsWith("http://") || value.startsWith("https://")) {
                    value
                } else {
                    "https://$value"
                }
                actions += EntityAction("Open", primary = true) {
                    context.start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }

            // A one-time code or a Wi-Fi password is only ever wanted in one form: on the
            // clipboard, immediately, which the shared Copy action below already is.
            else -> Unit
        }

        actions += EntityAction(COPY, primary = actions.none { it.primary }) {
            context.copy(item.type.label, value)
        }

        // Only worth offering for values distinctive enough that a second hit means something.
        // Searching for a two-digit amount matches half the library and teaches nobody anything.
        if (value.length >= 5 && item.type !in noisyToSearch) {
            actions += EntityAction("Find in Deja") { onFind(value) }
        }

        return actions
    }

    /**
     * Types whose values are too common or too private to offer as a search.
     *
     * Sensitive values are excluded for a concrete reason, not a cautious one: they are
     * deliberately kept out of the search index, so a search for an Aadhaar number would find
     * nothing and look broken.
     */
    private val noisyToSearch = setOf(
        EntityType.AMOUNT,
        EntityType.DATE,
        EntityType.CODE,
        EntityType.CARD,
        EntityType.AADHAAR,
        EntityType.PAN,
        EntityType.PASSPORT,
        EntityType.VOTER_ID,
        EntityType.DRIVING_LICENCE,
        EntityType.ACCOUNT_NUMBER,
        EntityType.WIFI_PASSWORD
    )
}

/** Nothing here is required to resolve - a phone with no dialler is unusual, not impossible. */
private fun Context.start(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun Context.copy(label: String, value: String) {
    getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, value))
}
