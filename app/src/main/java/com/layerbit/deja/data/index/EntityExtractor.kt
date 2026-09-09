package com.layerbit.deja.data.index

import com.layerbit.deja.data.model.EntityType
import com.layerbit.deja.data.model.Extracted

/**
 * Pulls the few things worth acting on out of a screenshot's text - the code to copy, the amount,
 * the booking reference, the ID number you screenshotted so you'd stop digging for the card.
 *
 * ML Kit's entity extraction is not used on purpose: it downloads its models at runtime, which
 * would mean granting the app network access and giving up the guarantee the whole product rests
 * on. These patterns run offline and are the honest trade.
 *
 * Order matters. Longer, more specific numbers are matched and consumed first so a 16-digit card
 * number is never also reported as a 12-digit Aadhaar, and a 12-digit Aadhaar is never reported
 * as a 10-digit phone.
 */
object EntityExtractor {

    private val card = Regex("\\b(?:\\d{4}[ -]?){3}\\d{4}\\b")
    private val aadhaar = Regex("\\b[2-9]\\d{3}\\s?\\d{4}\\s?\\d{4}\\b")
    private val pan = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")
    private val passport = Regex("\\b[A-PR-WYa-pr-wy][1-9]\\d{6}\\b")
    private val voterId = Regex("\\b[A-Z]{3}\\d{7}\\b")
    private val drivingLicence = Regex("\\b[A-Z]{2}\\d{2}\\s?\\d{11}\\b")
    private val gstin = Regex("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z0-9]Z[A-Z0-9]\\b")
    private val ifsc = Regex("\\b[A-Z]{4}0[A-Z0-9]{6}\\b")
    private val accountNumber = Regex(
        "(?:a/?c|account)\\s*(?:no\\.?|number|#)?\\s*[:\\-]?\\s*(\\d{9,18})",
        RegexOption.IGNORE_CASE
    )
    private val upiId = Regex("\\b[\\w.\\-]{2,}@(?:ok[a-z]+|ybl|ibl|axl|apl|paytm|upi|okaxis|oksbi)\\b")
    private val vehicle = Regex("\\b[A-Z]{2}\\s?\\d{1,2}\\s?[A-Z]{1,3}\\s?\\d{4}\\b")

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
            "\\s*[:\\-]?\\s*([A-Z0-9]{5,10})",
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
    private val handle = Regex("(?<![\\w.])@([A-Za-z][A-Za-z0-9_.]{2,29})\\b")
    private val link = Regex("https?://\\S{4,}")

    fun extract(text: String): List<Extracted> {
        if (text.isBlank()) return emptyList()

        val found = mutableListOf<Extracted>()
        // Spans already claimed by a more specific pattern, so a card number is not re-reported
        // as an Aadhaar and an Aadhaar is not re-reported as a phone number.
        val claimed = mutableListOf<IntRange>()

        fun free(range: IntRange) = claimed.none { it.first <= range.last && range.first <= it.last }

        fun take(type: EntityType, regex: Regex, group: Int = 1, limit: Int = 3) {
            var added = 0
            for (match in regex.findAll(text)) {
                if (added >= limit) break
                if (!free(match.range)) continue
                val value = match.groupValues.getOrNull(group)?.trim().orEmpty()
                if (value.isEmpty()) continue
                if (found.any { it.type == type && it.value == value }) continue
                found += Extracted(type, value)
                claimed += match.range
                added++
            }
        }

        // Longest and most structured first.
        take(EntityType.CARD, card, group = 0, limit = 2)
        take(EntityType.DRIVING_LICENCE, drivingLicence, group = 0, limit = 1)
        take(EntityType.GSTIN, gstin, group = 0, limit = 1)
        take(EntityType.AADHAAR, aadhaar, group = 0, limit = 1)
        take(EntityType.ACCOUNT_NUMBER, accountNumber, limit = 1)
        take(EntityType.PAN, pan, group = 0, limit = 1)
        take(EntityType.IFSC, ifsc, group = 0, limit = 1)
        take(EntityType.VOTER_ID, voterId, group = 0, limit = 1)
        take(EntityType.PASSPORT, passport, group = 0, limit = 1)
        take(EntityType.VEHICLE, vehicle, group = 0, limit = 1)

        take(EntityType.CODE, labelledCode, limit = 2)
        take(EntityType.WIFI_NETWORK, wifiNetwork, limit = 1)
        take(EntityType.WIFI_PASSWORD, wifiPassword, limit = 1)
        take(EntityType.BOOKING_REF, bookingRef, limit = 1)

        take(EntityType.UPI_ID, upiId, group = 0, limit = 2)
        take(EntityType.EMAIL, email, group = 0, limit = 2)
        take(EntityType.PHONE, phone, group = 0, limit = 3)
        take(EntityType.HANDLE, handle, limit = 3)

        take(EntityType.AMOUNT, amount, limit = 4)
        take(EntityType.DATE, numericDate, group = 0, limit = 2)
        take(EntityType.DATE, writtenDate, group = 0, limit = 2)
        take(EntityType.LINK, link, group = 0, limit = 2)

        return found
    }
}
