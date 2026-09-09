package com.layerbit.deja.data.index

import com.layerbit.deja.data.model.Category

/**
 * Works out which app a screenshot was taken in, from its filename.
 *
 * Most Android builds bake it in - `Screenshot_20240912-064012_WhatsApp.jpg` on Samsung and
 * OnePlus, `Screenshot_2024-09-12-06-40-12-345_com.whatsapp.jpg` on Xiaomi and Realme. It costs
 * nothing to read and it is by far the strongest categorisation signal available offline: a
 * screenshot from PhonePe is a payment whatever the OCR managed to make of it.
 *
 * Pixel and stock builds write no app name at all, which is why this only ever contributes to a
 * score rather than deciding a category outright.
 */
object SourceApp {

    /** package fragment or visible label -> display name and what it implies. */
    private data class Known(val display: String, val category: Category)

    private val table: List<Pair<List<String>, Known>> = listOf(
        listOf("whatsapp") to Known("WhatsApp", Category.CHAT),
        listOf("telegram") to Known("Telegram", Category.CHAT),
        listOf("signal") to Known("Signal", Category.CHAT),
        listOf("discord") to Known("Discord", Category.CHAT),
        listOf("messenger") to Known("Messenger", Category.CHAT),
        listOf("apps.messaging", "messages") to Known("Messages", Category.CHAT),

        listOf("instagram") to Known("Instagram", Category.SOCIAL),
        listOf("snapchat") to Known("Snapchat", Category.SOCIAL),
        listOf("facebook.katana", "facebook") to Known("Facebook", Category.SOCIAL),
        listOf("twitter", "com.x.android") to Known("X", Category.SOCIAL),
        listOf("reddit") to Known("Reddit", Category.SOCIAL),
        listOf("pinterest") to Known("Pinterest", Category.SOCIAL),
        listOf("threads") to Known("Threads", Category.SOCIAL),

        listOf("phonepe") to Known("PhonePe", Category.RECEIPT),
        listOf("paisa.user", "gpay", "googlepay") to Known("Google Pay", Category.RECEIPT),
        listOf("one97", "paytm") to Known("Paytm", Category.RECEIPT),
        listOf("bhim") to Known("BHIM", Category.RECEIPT),

        listOf("cred.club", "cred") to Known("CRED", Category.BANKING),
        listOf("sbi", "yono") to Known("SBI", Category.BANKING),
        listOf("icicibank") to Known("ICICI", Category.BANKING),
        listOf("hdfcbank") to Known("HDFC", Category.BANKING),
        listOf("axisbank") to Known("Axis Bank", Category.BANKING),
        listOf("kotak", "msf.kbank") to Known("Kotak", Category.BANKING),
        listOf("zerodha", "kite") to Known("Zerodha", Category.BANKING),
        listOf("groww") to Known("Groww", Category.BANKING),
        listOf("upstox") to Known("Upstox", Category.BANKING),

        listOf("amazon") to Known("Amazon", Category.SHOPPING),
        listOf("flipkart") to Known("Flipkart", Category.SHOPPING),
        listOf("myntra") to Known("Myntra", Category.SHOPPING),
        listOf("meesho") to Known("Meesho", Category.SHOPPING),
        listOf("ajio") to Known("Ajio", Category.SHOPPING),
        listOf("nykaa") to Known("Nykaa", Category.SHOPPING),

        listOf("swiggy") to Known("Swiggy", Category.FOOD),
        listOf("zomato") to Known("Zomato", Category.FOOD),
        listOf("dominos") to Known("Domino's", Category.FOOD),
        listOf("blinkit", "grofers") to Known("Blinkit", Category.FOOD),
        listOf("zepto") to Known("Zepto", Category.FOOD),

        listOf("apps.maps", "maps") to Known("Maps", Category.PLACE),
        listOf("olacabs") to Known("Ola", Category.PLACE),
        listOf("ubercab", "uber") to Known("Uber", Category.PLACE),
        listOf("rapido") to Known("Rapido", Category.PLACE),

        listOf("makemytrip") to Known("MakeMyTrip", Category.TICKET),
        listOf("goibibo") to Known("Goibibo", Category.TICKET),
        listOf("ixigo") to Known("ixigo", Category.TICKET),
        listOf("irctc", "cris.org") to Known("IRCTC", Category.TICKET),
        listOf("bookmyshow") to Known("BookMyShow", Category.TICKET),
        listOf("indigo", "goindigo") to Known("IndiGo", Category.TICKET),
        listOf("redbus") to Known("redBus", Category.TICKET),

        listOf("android.gm", "gmail") to Known("Gmail", Category.WORK),
        listOf("outlook") to Known("Outlook", Category.WORK),
        listOf("teams") to Known("Teams", Category.WORK),
        listOf("slack") to Known("Slack", Category.WORK),
        listOf("apps.docs", "docs") to Known("Docs", Category.WORK),
        listOf("linkedin") to Known("LinkedIn", Category.WORK),
        listOf("notion") to Known("Notion", Category.WORK),
        listOf("zoom") to Known("Zoom", Category.WORK),
        listOf("keep") to Known("Keep", Category.WORK),

        listOf("youtube") to Known("YouTube", Category.MEDIA),
        listOf("spotify") to Known("Spotify", Category.MEDIA),
        listOf("netflix") to Known("Netflix", Category.MEDIA),
        listOf("hotstar") to Known("Hotstar", Category.MEDIA),
        listOf("primevideo") to Known("Prime Video", Category.MEDIA),

        listOf("practo") to Known("Practo", Category.HEALTH),
        listOf("apollo") to Known("Apollo", Category.HEALTH),
        listOf("pharmeasy") to Known("PharmEasy", Category.HEALTH),

        listOf("digilocker") to Known("DigiLocker", Category.IDENTITY),
        listOf("maadhaar", "aadhaar") to Known("mAadhaar", Category.IDENTITY),

        listOf("chrome") to Known("Chrome", Category.OTHER),
        listOf("firefox") to Known("Firefox", Category.OTHER)
    )

    /** Everything a screenshot filename puts around the app name. */
    private val noise = Regex(
        "(?i)^(screenshot|screen[ _-]?shot|capture|img|image|photo|from)$|^\\d+$|^(png|jpg|jpeg|webp)$"
    )

    /**
     * The app's display name, or null when the filename carries no clue. Never guesses: an
     * unrecognised fragment is returned tidied up rather than dropped, because "Notes" is more
     * useful in a filter row than nothing.
     */
    fun detect(displayName: String): String? {
        val stem = displayName.substringBeforeLast('.')
        val lower = stem.lowercase()

        table.forEach { (keys, known) ->
            if (keys.any { lower.contains(it) }) return known.display
        }

        // Nothing known matched - fall back to the last word-ish fragment if it looks like a name.
        val fragment = stem.split('_', '-', ' ')
            .map { it.trim() }
            .lastOrNull { it.length in 3..24 && !noise.matches(it) && it.any(Char::isLetter) }
            ?: return null

        // A package name shows up as its most specific segment: com.foo.bar -> Bar
        val candidate = fragment.substringAfterLast('.')
        if (candidate.length < 3 || candidate.all(Char::isDigit)) return null
        return candidate.replaceFirstChar(Char::uppercaseChar)
    }

    /** What the detected app suggests the screenshot is, if anything. */
    fun categoryHint(displayName: String): Category? {
        val lower = displayName.lowercase()
        table.forEach { (keys, known) ->
            if (keys.any { lower.contains(it) }) return known.category
        }
        return null
    }
}
