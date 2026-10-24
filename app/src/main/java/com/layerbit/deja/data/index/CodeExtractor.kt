package com.layerbit.deja.data.index

import com.layerbit.deja.data.model.EntityType
import com.layerbit.deja.data.model.Extracted

/**
 * Reads the machine-readable codes in a screenshot.
 *
 * These are the screenshots people keep on purpose and then cannot use. A Wi-Fi QR code is a
 * picture of a password you still have to type by hand off a different screen; a boarding pass
 * is a PDF417 block holding the PNR that the page above it may not even print; a UPI QR holds
 * the payee ID and often the exact amount. All of it is structured data sitting in plain sight,
 * and OCR cannot touch any of it - a QR code has no text to read.
 *
 * Only the raw payload is stored in the index. The parsing lives here, which means improving the
 * boarding-pass reader or adding a new payload format later is a `DERIVE` bump - a second of
 * database work - rather than re-reading every image on the phone. Deliberate: this is exactly
 * the kind of parser that gets better in small increments.
 */
object CodeExtractor {

    private const val RECORD = '\u001E'

    fun encode(payloads: List<String>): String =
        payloads.joinToString(RECORD.toString()) { it.replace(RECORD, ' ') }

    fun decode(blob: String): List<String> =
        if (blob.isEmpty()) emptyList() else blob.split(RECORD).filter { it.isNotBlank() }

    fun extract(payloads: List<String>): List<Extracted> {
        val found = mutableListOf<Extracted>()
        payloads.forEach { payload ->
            when {
                payload.startsWith("WIFI:", ignoreCase = true) -> wifi(payload, found)
                payload.startsWith("upi://", ignoreCase = true) -> upi(payload, found)
                payload.startsWith("BEGIN:VCARD", ignoreCase = true) -> vcard(payload, found)
                payload.startsWith("mailto:", ignoreCase = true) ->
                    add(found, EntityType.EMAIL, payload.removePrefix("mailto:").substringBefore('?'))
                payload.startsWith("tel:", ignoreCase = true) ->
                    add(found, EntityType.PHONE, payload.removePrefix("tel:"))
                payload.startsWith("SMSTO:", ignoreCase = true) ->
                    add(found, EntityType.PHONE, payload.removePrefix("SMSTO:").substringBefore(':'))
                payload.startsWith("http://", ignoreCase = true) ||
                    payload.startsWith("https://", ignoreCase = true) ->
                    add(found, EntityType.LINK, payload)
                looksLikeBoardingPass(payload) -> boardingPass(payload, found)
            }
        }
        return found
    }

    /** What of a code belongs in the search index. Never the Wi-Fi password. */
    fun searchText(payloads: List<String>): String = buildString {
        payloads.forEach { payload ->
            when {
                payload.startsWith("WIFI:", ignoreCase = true) -> {
                    append(" Wi-Fi network ")
                    append(field(payload, "S").orEmpty())
                }
                payload.startsWith("upi://", ignoreCase = true) -> {
                    append(" UPI payment ")
                    append(query(payload, "pn").orEmpty())
                }
                looksLikeBoardingPass(payload) -> {
                    append(" boarding pass ")
                    append(bcbp(payload, 30, 33).orEmpty())
                    append(' ')
                    append(bcbp(payload, 33, 36).orEmpty())
                }
                payload.startsWith("http", ignoreCase = true) -> {
                    append(' ')
                    append(payload.substringAfter("://").substringBefore('/'))
                }
            }
        }
    }.trim()

    /**
     * `WIFI:T:WPA;S:<network>;P:<password>;H:false;;`
     *
     * The one place in Deja where a password arrives already labelled as a password, so it is
     * marked sensitive and kept out of the search index like every other secret.
     */
    private fun wifi(payload: String, found: MutableList<Extracted>) {
        field(payload, "S")?.let { add(found, EntityType.WIFI_NETWORK, it) }
        field(payload, "P")?.let { add(found, EntityType.WIFI_PASSWORD, it) }
    }

    /** `upi://pay?pa=someone@okaxis&pn=Name&am=450.00&cu=INR` */
    private fun upi(payload: String, found: MutableList<Extracted>) {
        query(payload, "pa")?.let { add(found, EntityType.UPI_ID, it) }
        query(payload, "am")
            ?.takeIf { it.isNotBlank() && it.first().isDigit() }
            ?.let { add(found, EntityType.AMOUNT, it) }
    }

    private fun vcard(payload: String, found: MutableList<Extracted>) {
        payload.lineSequence().forEach { line ->
            val upper = line.uppercase()
            when {
                upper.startsWith("TEL") -> add(found, EntityType.PHONE, line.substringAfter(':'))
                upper.startsWith("EMAIL") -> add(found, EntityType.EMAIL, line.substringAfter(':'))
                upper.startsWith("URL") -> add(found, EntityType.LINK, line.substringAfter(':'))
            }
        }
    }

    /**
     * IATA BCBP, the string behind the barcode on every boarding pass. Fixed-width, so the
     * fields are at known offsets:
     *
     * ```
     * M1SURNAME/FIRST       EABC123 DELBOM 6E 2341 123Y012A0001 100
     * 0 2                   22 23   30 33  36 39
     * ```
     *
     * Read defensively. A short or malformed string yields nothing rather than a wrong PNR, and
     * a wrong booking reference is worse than none at all.
     */
    private fun boardingPass(payload: String, found: MutableList<Extracted>) {
        bcbp(payload, 23, 30)
            ?.takeIf { it.length >= 5 && it.all { c -> c.isLetterOrDigit() } }
            ?.let { add(found, EntityType.BOOKING_REF, it) }

        val carrier = bcbp(payload, 36, 39)?.filter { !it.isWhitespace() }
        val number = bcbp(payload, 39, 44)?.trimStart('0')
        if (!carrier.isNullOrBlank() && !number.isNullOrBlank() && number.all(Char::isDigit)) {
            add(found, EntityType.FLIGHT, "$carrier $number")
        }
    }

    private fun looksLikeBoardingPass(payload: String): Boolean =
        payload.length >= 60 &&
            payload.first() == 'M' &&
            payload.getOrNull(1)?.isDigit() == true &&
            payload.getOrNull(22) == 'E'

    private fun bcbp(payload: String, from: Int, to: Int): String? =
        if (payload.length >= to) payload.substring(from, to).trim().ifEmpty { null } else null

    /** A field out of a `KEY:value;` payload, honouring the backslash escapes the format uses. */
    private fun field(payload: String, key: String): String? {
        val match = Regex("(?:^|;)$key:((?:\\\\.|[^;\\\\])*)", RegexOption.IGNORE_CASE)
            .find(payload) ?: return null
        return match.groupValues[1]
            .replace(Regex("\\\\(.)"), "$1")
            .trim()
            .ifEmpty { null }
    }

    private fun query(payload: String, key: String): String? =
        payload.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("$key=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let { decodeUrl(it) }
            ?.trim()
            ?.ifEmpty { null }

    /** Percent-decoding, inline rather than through a library that wants a charset argument. */
    private fun decodeUrl(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '+' -> {
                    out.append(' ')
                    index++
                }
                ch == '%' && index + 2 < value.length -> {
                    val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (hex == null) {
                        out.append(ch)
                        index++
                    } else {
                        out.append(hex.toChar())
                        index += 3
                    }
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun add(found: MutableList<Extracted>, type: EntityType, raw: String) {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 200) return
        if (found.any { it.type == type && it.value == value }) return
        found += Extracted(type, value)
    }
}
