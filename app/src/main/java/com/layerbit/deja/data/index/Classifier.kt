package com.layerbit.deja.data.index

import com.layerbit.deja.data.model.Category

/**
 * Sorts a screenshot into a bucket from the words in it.
 *
 * Deliberately a keyword score rather than a model: it runs in microseconds, costs no download,
 * and every decision it makes can be explained to the user. It is also wrong sometimes, which is
 * why nothing destructive keys off the category alone.
 */
object Classifier {

    private val signals: Map<Category, List<String>> = mapOf(
        Category.DOCUMENT to listOf(
            "aadhaar", "aadhar", "pan card", "passport", "driving licence", "driving license",
            "ifsc", "account number", "policy number", "voter id"
        ),
        Category.CODE to listOf(
            "otp", "one time password", "one-time password", "verification code", "passcode",
            "do not share", "wifi", "wi-fi", "ssid", "password", "security code"
        ),
        Category.TICKET to listOf(
            "pnr", "boarding", "flight", "departure", "arrival", "seat", "gate",
            "booking confirmed", "e-ticket", "check-in", "platform", "coach", "showtime"
        ),
        Category.RECEIPT to listOf(
            "total", "subtotal", "amount paid", "invoice", "receipt", "gst", "tax",
            "bill", "paid to", "transaction id", "upi", "debited", "credited", "order total"
        ),
        Category.PRODUCT to listOf(
            "add to cart", "buy now", "delivery by", "free delivery", "in stock", "out of stock",
            "off", "mrp", "ratings", "wishlist"
        ),
        Category.PLACE to listOf(
            "directions", "km", "min drive", "arrive", "route", "nearby", "open now",
            "closes", "navigation"
        ),
        Category.CHAT to listOf(
            "typing", "online", "last seen", "forwarded", "you:", "replied to", "message"
        )
    )

    fun classify(text: String): Category {
        if (text.isBlank()) return Category.OTHER
        val haystack = text.lowercase()

        var best = Category.OTHER
        var bestScore = 0
        // Iterating the map in declaration order makes ties resolve towards the more specific
        // category, which is why DOCUMENT and CODE are declared first.
        for ((category, words) in signals) {
            val score = words.count { haystack.contains(it) }
            if (score > bestScore) {
                bestScore = score
                best = category
            }
        }
        return if (bestScore >= 2) best else weakGuess(haystack, best, bestScore)
    }

    private fun weakGuess(haystack: String, best: Category, bestScore: Int): Category {
        // A single keyword is only trusted when it is a strong one on its own.
        val decisive = bestScore == 1 && (
            haystack.contains("otp") ||
                haystack.contains("pnr") ||
                haystack.contains("aadhaar") ||
                haystack.contains("invoice") ||
                haystack.contains("boarding")
            )
        return if (decisive) best else Category.OTHER
    }
}
