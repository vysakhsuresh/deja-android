package com.layerbit.deja.data.index

import com.layerbit.deja.data.model.Category
import com.layerbit.deja.data.model.EntityType
import com.layerbit.deja.data.model.Extracted

/**
 * Decides which bucket a screenshot belongs in.
 *
 * Three signals, weighted rather than chained, because no single one is reliable on its own:
 *
 *  - **What app it came from** (from the filename). The strongest signal by far, and free - but
 *    absent on Pixel and stock builds, so it can never decide alone.
 *  - **What was extracted from it.** A PAN number means an ID document no matter what else the
 *    OCR picked up; a PNR means travel.
 *  - **Words in the text**, split into strong terms that mean one thing ("boarding pass") and
 *    ordinary ones that only count alongside others ("total").
 *
 * The earlier version demanded two keyword hits and dropped everything else into OTHER, which put
 * most of a real library there. Scoring fixes that: a WhatsApp screenshot is a conversation even
 * when the OCR read almost nothing, and an OTP inside it still outranks the app it came from.
 */
object Classifier {

    private const val APP_HINT = 4.0
    private const val STRONG_WORD = 3.0
    private const val WEAK_WORD = 1.2
    private const val MIN_SCORE = 2.0

    /** Terms that mean one category and nothing else. */
    private val strong: Map<Category, List<String>> = mapOf(
        Category.IDENTITY to listOf(
            "aadhaar", "aadhar", "pan card", "permanent account number", "passport",
            "driving licence", "driving license", "voter id", "digilocker", "date of birth"
        ),
        Category.CODE to listOf(
            "otp", "one time password", "one-time password", "verification code",
            "do not share", "wi-fi password", "wifi password", "ssid"
        ),
        Category.TICKET to listOf(
            "pnr", "boarding pass", "e-ticket", "booking confirmed", "departure", "gate no",
            "seat no", "check-in", "itinerary", "showtime", "coach"
        ),
        Category.RECEIPT to listOf(
            "transaction successful", "payment successful", "paid to", "amount paid", "invoice",
            "transaction id", "upi ref", "utr", "receipt", "money sent", "debited from"
        ),
        Category.BANKING to listOf(
            "available balance", "account statement", "ifsc", "neft", "imps", "rtgs",
            "credit card statement", "mutual fund", "portfolio", "holdings", "emi"
        ),
        Category.HEALTH to listOf(
            "prescription", "dosage", "mg twice", "diagnosis", "blood group", "haemoglobin",
            "consultation", "lab report", "vaccination"
        ),
        Category.CONTACT to listOf(
            "save contact", "phone number", "contact card", "mobile no", "add to contacts"
        ),
        Category.FOOD to listOf(
            "ingredients", "preheat", "recipe", "order delivered", "restaurant", "menu",
            "out for delivery"
        ),
        Category.PLACE to listOf(
            "directions", "min drive", "km away", "open now", "closes at", "get directions"
        ),
        Category.SHOPPING to listOf(
            "add to cart", "buy now", "place order", "order placed", "wishlist", "mrp",
            "free delivery", "out of stock", "return window"
        ),
        Category.WORK to listOf(
            "meeting", "agenda", "deadline", "assignment", "syllabus", "attendance",
            "invoice due", "resume", "job description", "offer letter"
        ),
        Category.SOCIAL to listOf(
            "liked by", "shared a post", "story", "followers", "following", "retweet", "upvote"
        ),
        Category.MEDIA to listOf(
            "now playing", "episode", "season", "watchlist", "subscribe", "views"
        ),
        Category.CHAT to listOf(
            "last seen", "typing", "forwarded", "message deleted", "online"
        )
    )

    /** Terms that only count when something else agrees. */
    private val weak: Map<Category, List<String>> = mapOf(
        Category.RECEIPT to listOf("total", "subtotal", "gst", "tax", "bill", "paid", "amount"),
        Category.BANKING to listOf("balance", "account", "bank", "credit", "debit", "interest"),
        Category.TICKET to listOf("flight", "train", "bus", "seat", "travel", "trip", "ticket"),
        Category.CODE to listOf("code", "password", "pin", "secure", "expires"),
        Category.IDENTITY to listOf("government", "issued", "id number", "valid till", "signature"),
        Category.CONTACT to listOf("call", "whatsapp", "email", "contact", "profile"),
        Category.CHAT to listOf("reply", "sent", "delivered", "chat", "group"),
        Category.SHOPPING to listOf("price", "delivery", "order", "cart", "offer", "discount"),
        Category.PLACE to listOf("map", "route", "address", "location", "near"),
        Category.FOOD to listOf("order", "food", "cook", "serve", "cup", "tbsp", "recipe"),
        Category.WORK to listOf("project", "team", "report", "document", "class", "exam", "note"),
        Category.SOCIAL to listOf("post", "comment", "share", "follow", "profile", "reel"),
        Category.MEDIA to listOf("play", "watch", "listen", "song", "album", "video", "movie"),
        Category.HEALTH to listOf("doctor", "clinic", "test", "report", "patient", "tablet")
    )

    /** What an extracted value implies, and how strongly. */
    private val entitySignals: Map<EntityType, Pair<Category, Double>> = mapOf(
        EntityType.AADHAAR to (Category.IDENTITY to 6.0),
        EntityType.PAN to (Category.IDENTITY to 6.0),
        EntityType.PASSPORT to (Category.IDENTITY to 5.0),
        EntityType.VOTER_ID to (Category.IDENTITY to 5.0),
        EntityType.DRIVING_LICENCE to (Category.IDENTITY to 5.0),
        EntityType.VEHICLE to (Category.IDENTITY to 2.0),
        EntityType.CODE to (Category.CODE to 5.0),
        EntityType.WIFI_PASSWORD to (Category.CODE to 5.0),
        EntityType.WIFI_NETWORK to (Category.CODE to 3.0),
        EntityType.CARD to (Category.BANKING to 4.0),
        EntityType.ACCOUNT_NUMBER to (Category.BANKING to 4.0),
        EntityType.IFSC to (Category.BANKING to 4.0),
        EntityType.GSTIN to (Category.RECEIPT to 3.0),
        EntityType.UPI_ID to (Category.RECEIPT to 3.0),
        EntityType.BOOKING_REF to (Category.TICKET to 5.0),
        EntityType.AMOUNT to (Category.RECEIPT to 1.2),
        EntityType.PHONE to (Category.CONTACT to 1.5),
        EntityType.EMAIL to (Category.CONTACT to 1.2),
        EntityType.HANDLE to (Category.SOCIAL to 1.0)
    )

    fun classify(text: String, displayName: String, entities: List<Extracted>): Category {
        val scores = mutableMapOf<Category, Double>()
        fun add(category: Category, amount: Double) {
            scores[category] = (scores[category] ?: 0.0) + amount
        }

        SourceApp.categoryHint(displayName)
            ?.takeIf { it != Category.OTHER }
            ?.let { add(it, APP_HINT) }

        entities.forEach { entity ->
            entitySignals[entity.type]?.let { (category, weight) -> add(category, weight) }
        }

        if (text.isNotBlank()) {
            val haystack = text.lowercase()
            strong.forEach { (category, words) ->
                add(category, STRONG_WORD * words.count { haystack.contains(it) })
            }
            weak.forEach { (category, words) ->
                add(category, WEAK_WORD * words.count { haystack.contains(it) })
            }
        }

        val best = scores.maxByOrNull { it.value } ?: return Category.OTHER
        return if (best.value >= MIN_SCORE) best.key else Category.OTHER
    }
}
