package com.layerbit.deja.data.index

import com.layerbit.deja.data.model.EntityType
import com.layerbit.deja.data.model.Extracted
import com.layerbit.deja.data.ocr.TextLine

/**
 * Pulls the few things worth acting on out of a screenshot - the code to copy, the amount, the
 * booking reference, the ID number you screenshotted so you'd stop digging for the card.
 *
 * ML Kit's own entity extraction is not used on purpose: it downloads its models at runtime,
 * which would mean granting the app network access and giving up the guarantee the whole product
 * rests on. These patterns run offline and are the honest trade.
 *
 * Two passes.
 *
 * **Flat.** Regexes over the whole text, longest and most structured first, each consuming the
 * span it matched so a 16-digit card is never also reported as a 12-digit Aadhaar and an Aadhaar
 * is never reported as a 10-digit phone.
 *
 * **Layout.** The flat pass cannot tell which of four amounts on a receipt is the total, and it
 * cannot connect a label to a value sitting on the next line rather than after a colon. Both are
 * the normal case on a phone screen, and both are obvious once the line boxes are in hand:
 *
 *  - the total is the amount next to a word like "total", or the one set in the biggest type;
 *  - the code is the bare number directly below the line that says "verification code".
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

    /**
     * Airline designator plus number. Two-letter IATA codes are too common as ordinary words to
     * match openly, so this is an explicit list of the carriers an Indian library actually sees.
     */
    private val flight = Regex(
        "\\b(?:6E|AI|UK|SG|IX|G8|QP|I5|EK|EY|QR|LH|BA|AF|KL|SQ|TG|MH|CX|TK|AA|UA|DL)" +
            // The lookahead keeps "UK 1947" and "SG 2024" out: a four-digit number in the
            // nineteen- or twenty-hundreds is a year on a page about travel, not a flight.
            "[\\s-]?(?!19\\d{2}\\b|20\\d{2}\\b)\\d{2,4}\\b"
    )

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
    private val clockTime = Regex("\\b([01]?\\d|2[0-3])[:.][0-5]\\d\\s?(?:am|pm|AM|PM)?\\b")
    private val phone = Regex("(?:\\+91[\\s-]?)?\\b[6-9]\\d{9}\\b")
    private val email = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
    private val handle = Regex("(?<![\\w.])@([A-Za-z][A-Za-z0-9_.]{2,29})\\b")
    private val link = Regex("https?://\\S{4,}")

    /** Words that mark the amount somebody actually cares about. */
    private val totalWords = listOf(
        "total", "grand total", "amount paid", "paid", "payable", "net amount",
        "you paid", "order total", "bill total", "amount"
    )

    /** A line that is only a label - no value on it - so the value must be the next line down. */
    private val codeLabelOnly = Regex(
        "^(?:your\\s+)?(?:otp|one[- ]?time\\s*(?:password|code|pin)|verification\\s*code|" +
            "security\\s*code|passcode|login\\s*code)\\s*[:\\-]?$",
        RegexOption.IGNORE_CASE
    )
    private val passwordLabelOnly = Regex(
        "^(?:wi-?fi\\s*)?(?:password|passphrase|pass\\s*key)\\s*[:\\-]?$",
        RegexOption.IGNORE_CASE
    )
    private val networkLabelOnly = Regex(
        "^(?:wi-?fi(?:\\s*name)?|ssid|network(?:\\s*name)?)\\s*[:\\-]?$",
        RegexOption.IGNORE_CASE
    )
    private val bookingLabelOnly = Regex(
        "^(?:pnr(?:\\s*(?:no\\.?|number))?|booking\\s*(?:ref(?:erence)?|id|code)|" +
            "confirmation\\s*(?:no\\.?|number|code))\\s*[:\\-]?$",
        RegexOption.IGNORE_CASE
    )

    private val bareCode = Regex("^\\d{4,8}$")
    private val bareRef = Regex("^[A-Z0-9]{5,10}$")

    fun extract(text: String, lines: List<TextLine> = emptyList()): List<Extracted> {
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
        take(EntityType.FLIGHT, flight, group = 0, limit = 2)

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

        if (lines.isNotEmpty()) {
            fillFromLayout(found, lines)
            reorderAmounts(found, lines)
            takeTimes(found, lines)
        }

        return found
    }

    /**
     * Label on one line, value on the next.
     *
     * Only fills types the flat pass found nothing for. The flat regexes already handle
     * "OTP: 481902"; what they cannot handle is the far more common phone layout where the label
     * is a caption above a value set on its own line, because at that point the two are separated
     * by a newline and an arbitrary amount of other text in reading order.
     */
    private fun fillFromLayout(found: MutableList<Extracted>, lines: List<TextLine>) {
        val ordered = TextLayout.readingOrder(lines)

        fun missing(type: EntityType) = found.none { it.type == type }

        for (index in 0 until ordered.size - 1) {
            val label = ordered[index].text.trim()
            val value = ordered[index + 1].text.trim()
            if (value.isEmpty()) continue

            when {
                missing(EntityType.CODE) &&
                    codeLabelOnly.matches(label) &&
                    bareCode.matches(value) ->
                    found += Extracted(EntityType.CODE, value)

                missing(EntityType.WIFI_PASSWORD) &&
                    passwordLabelOnly.matches(label) &&
                    value.length in 4..40 && !value.contains(' ') ->
                    found += Extracted(EntityType.WIFI_PASSWORD, value)

                missing(EntityType.WIFI_NETWORK) &&
                    networkLabelOnly.matches(label) &&
                    value.length in 2..32 ->
                    found += Extracted(EntityType.WIFI_NETWORK, value)

                missing(EntityType.BOOKING_REF) &&
                    bookingLabelOnly.matches(label) &&
                    bareRef.matches(value) ->
                    found += Extracted(EntityType.BOOKING_REF, value)
            }
        }
    }

    /**
     * Puts the amount that matters first.
     *
     * A delivery receipt reads "₹49 delivery, ₹32 taxes, ₹450 total" and the flat pass reports
     * ₹49, because that is the one it met first. Two signals fix it, and they agree far more
     * often than either is right alone: the word next to the number, and how big the number is
     * set. Payment apps render the amount paid as the largest text on the screen.
     */
    private fun reorderAmounts(found: MutableList<Extracted>, lines: List<TextLine>) {
        val amounts = found.filter { it.type == EntityType.AMOUNT }
        if (amounts.size < 2) return

        val ordered = TextLayout.readingOrder(lines)
        val tallest = TextLayout.maxHeight(ordered).coerceAtLeast(1)

        fun scoreOf(value: String): Double {
            val index = ordered.indexOfFirst { it.text.contains(value) }
            if (index < 0) return 0.0
            val context = TextLayout.contextAround(ordered, index).lowercase()
            val labelled = totalWords.any { context.contains(it) }
            val relativeSize = ordered[index].height.toDouble() / tallest
            return (if (labelled) 3.0 else 0.0) + relativeSize * 2.0
        }

        val ranked = amounts.sortedByDescending { scoreOf(it.value) }
        if (ranked == amounts) return

        found.removeAll { it.type == EntityType.AMOUNT }
        found.addAll(ranked)
    }

    /**
     * Times, but only from the page itself.
     *
     * Every screenshot has a clock in the status bar and it is never the time anyone wants. The
     * flat text cannot tell it apart from a departure time; a line box can, because it is the one
     * at the very top of the screen.
     */
    private fun takeTimes(found: MutableList<Extracted>, lines: List<TextLine>) {
        if (found.any { it.type == EntityType.TIME }) return

        var added = 0
        for (line in TextLayout.readingOrder(lines)) {
            if (added >= 2) break
            if (line.bottom <= STATUS_BAR_BOTTOM) continue
            val match = clockTime.find(line.text) ?: continue
            val value = match.value.trim()
            if (found.any { it.type == EntityType.TIME && it.value == value }) continue
            found += Extracted(EntityType.TIME, value)
            added++
        }
    }

    private const val STATUS_BAR_BOTTOM = 32
}
