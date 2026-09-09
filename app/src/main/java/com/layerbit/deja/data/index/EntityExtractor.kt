package com.layerbit.deja.data.index

import com.layerbit.deja.data.model.Extracted
import com.layerbit.deja.data.model.EntityType

/**
 * Pulls the few things worth acting on out of a screenshot's text - the code you need to copy,
 * the amount, the booking reference.
 *
 * ML Kit's entity extraction is not used on purpose: it downloads its models at runtime, which
 * would mean granting the app network access and giving up the guarantee the whole product rests
 * on. These patterns run offline and are the honest trade.
 */
object EntityExtractor {

    private val labelledCode = Regex(
        "(?:otp|one[- ]?time(?:\\s+password)?|verification\\s+code|passcode|security\\s+code|pin)" +
            "\\D{0,24}?(\\d{4,8})",
        RegexOption.IGNORE_CASE
    )
    private val wifiNetwork = Regex(
        "(?:wi-?fi|ssid|network)\\s*(?:name)?\\s*[:\\-]\\s*([^\\s\\n]{2,32})",
        RegexOption.IGNORE_CASE
    )
    private val wifiPassword = Regex(
        "(?:password|passphrase|pass\\s*key)\\s*[:\\-]\\s*([^\\s\\n]{4,40})",
        RegexOption.IGNORE_CASE
    )
    private val bookingRef = Regex(
        "(?:pnr|booking\\s*(?:ref|reference|id|code)|confirmation\\s*(?:no|number|code))" +
            "\\s*[:\\-]?\\s*([A-Z0-9]{5,8})",
        RegexOption.IGNORE_CASE
    )
    private val amount = Regex("(?:₹|Rs\\.?|INR|\\$|€|£)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val numericDate = Regex("\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b")
    private val writtenDate = Regex(
        "\\b\\d{1,2}\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{2,4}\\b",
        RegexOption.IGNORE_CASE
    )
    private val phone = Regex("(?:\\+91[\\s-]?)?\\b[6-9]\\d{9}\\b")
    private val email = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
    private val link = Regex("https?://\\S{4,}")

    fun extract(text: String): List<Extracted> {
        if (text.isBlank()) return emptyList()

        val found = mutableListOf<Extracted>()
        fun addAll(type: EntityType, regex: Regex, group: Int = 1, limit: Int = 3) {
            regex.findAll(text)
                .mapNotNull { it.groupValues.getOrNull(group)?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(limit)
                .forEach { found += Extracted(type, it) }
        }

        addAll(EntityType.CODE, labelledCode, limit = 2)
        addAll(EntityType.WIFI_NETWORK, wifiNetwork, limit = 1)
        addAll(EntityType.WIFI_PASSWORD, wifiPassword, limit = 1)
        addAll(EntityType.BOOKING_REF, bookingRef, limit = 1)
        addAll(EntityType.AMOUNT, amount, limit = 3)
        addAll(EntityType.DATE, numericDate, group = 0, limit = 2)
        addAll(EntityType.DATE, writtenDate, group = 0, limit = 2)
        addAll(EntityType.PHONE, phone, group = 0, limit = 2)
        addAll(EntityType.EMAIL, email, group = 0, limit = 2)
        addAll(EntityType.LINK, link, group = 0, limit = 2)

        return found
    }
}
