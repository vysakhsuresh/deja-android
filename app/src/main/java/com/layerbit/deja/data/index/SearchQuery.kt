package com.layerbit.deja.data.index

/**
 * What the user typed, understood.
 *
 * The first version turned a query into `a* OR b* OR c*` and ranked the results by how many of
 * the words appeared somewhere. On a small library that is fine. On a real one it is the reason
 * search feels vague: "swiggy receipt" returns every Swiggy screenshot and every receipt, in
 * date order, with no sense that the screenshot matching both is the answer.
 *
 * A plan carries five things instead of one:
 *
 *  - **terms** the words actually typed, which are what ranking rewards;
 *  - **phrases** anything in quotes, which must appear intact;
 *  - **excluded** anything prefixed with a minus, which must not appear at all;
 *  - **window** a span of time parsed out of words like "last week" (see [DateHints]);
 *  - **expanded** the terms plus their known variants, used only to widen recall.
 *
 * Matching is then tried twice: everything-must-match first, and only if that comes back thin is
 * the net widened to anything-matches. Precision when precision is available, recall when it is
 * not - rather than always recall, which is what it used to be.
 */
data class SearchPlan(
    val raw: String,
    val terms: List<String>,
    val phrases: List<String>,
    val excluded: List<String>,
    val window: DateWindow?,
    /** FTS fragments for the loose pass only - never used for ranking. See [SearchQuery]. */
    val expanded: List<String>
) {
    /** True when there is nothing to search for and nothing to narrow by. */
    val isEmpty: Boolean get() = terms.isEmpty() && phrases.isEmpty() && window == null

    /** True when the only thing typed was a date, so there is no text to match at all. */
    val dateOnly: Boolean get() = terms.isEmpty() && phrases.isEmpty() && window != null

    /** What to paint in the result snippets. */
    val highlight: List<String> get() = phrases + terms

    /** Every typed term and every phrase must be present. */
    fun strictMatch(): String? {
        val parts = phrases.map { "\"$it\"" } + terms.map { "\"$it\"*" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    /** Anything will do, including the known variants of what was typed. */
    fun looseMatch(): String? {
        val parts = phrases.map { "\"$it\"" } + expanded
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
    }
}

object SearchQuery {

    private val stopwords = setOf(
        "the", "a", "an", "and", "or", "of", "for", "from", "in", "on", "at", "to", "with",
        "my", "me", "i", "it", "is", "was", "that", "this", "there", "were", "did",
        "find", "show", "get", "what", "where", "which", "when", "who", "some", "any",
        "screenshot", "screenshots", "screen", "shot", "picture", "photo", "image"
    )

    /**
     * Known ways of writing the same thing, as FTS fragments rather than bare words.
     *
     * Fragments, because some of these only work as phrases. FTS4 splits "Wi-Fi" into the two
     * tokens `wi` and `fi`, so a search for "wifi" could never match a screenshot that spells it
     * the normal way - and `"wi"*` on its own would match "with", "will" and "window". The
     * phrase `"wi fi"` matches exactly the two adjacent tokens and nothing else.
     *
     * These only ever widen the loose pass. Ranking scores what was typed, so an expansion can
     * surface a result but never outrank a direct hit.
     */
    private val expansions: Map<String, List<String>> = mapOf(
        "otp" to listOf("\"otp\"*", "\"one time password\"", "\"verification code\""),
        "code" to listOf("\"code\"*", "\"otp\"*"),
        "wifi" to listOf("\"wifi\"*", "\"wi fi\"", "\"ssid\"*"),
        "password" to listOf("\"password\"*", "\"passphrase\"*", "\"passcode\"*"),
        "aadhaar" to listOf("\"aadhaar\"*", "\"aadhar\"*", "\"uidai\"*"),
        "aadhar" to listOf("\"aadhar\"*", "\"aadhaar\"*", "\"uidai\"*"),
        "pan" to listOf("\"pan\"*", "\"permanent account number\""),
        "receipt" to listOf("\"receipt\"*", "\"invoice\"*", "\"paid\"*"),
        "invoice" to listOf("\"invoice\"*", "\"receipt\"*", "\"bill\"*"),
        "bill" to listOf("\"bill\"*", "\"invoice\"*", "\"receipt\"*"),
        "payment" to listOf("\"payment\"*", "\"paid\"*", "\"transaction\"*"),
        "ticket" to listOf("\"ticket\"*", "\"pnr\"*", "\"booking\"*"),
        "flight" to listOf("\"flight\"*", "\"boarding pass\"", "\"pnr\"*"),
        "train" to listOf("\"train\"*", "\"pnr\"*", "\"irctc\"*"),
        "gpay" to listOf("\"gpay\"*", "\"google pay\""),
        "upi" to listOf("\"upi\"*", "\"vpa\"*"),
        "address" to listOf("\"address\"*", "\"pincode\"*", "\"pin code\""),
        "amount" to listOf("\"amount\"*", "\"total\"*")
    )

    fun plan(raw: String): SearchPlan {
        val phrases = quoted(raw)
        // Quoted runs are removed before tokenising, so a phrase's own words are not also
        // matched loosely - "boarding pass" would otherwise drag in every pass and every board.
        val withoutPhrases = raw.replace(Regex("\"[^\"]*\""), " ")

        val excluded = Regex("(?<![\\w])-([\\p{L}\\p{N}]{2,})")
            .findAll(withoutPhrases)
            .map { it.groupValues[1].lowercase() }
            .toList()
        val withoutExclusions = withoutPhrases.replace(Regex("(?<![\\w])-[\\p{L}\\p{N}]{2,}"), " ")

        val dateHit = DateHints.find(withoutExclusions)

        val terms = tokenise(withoutExclusions)
            .filterNot { it in (dateHit?.consumed ?: emptySet()) }
            .filterNot { it in excluded }

        return SearchPlan(
            raw = raw,
            terms = terms,
            phrases = phrases,
            excluded = excluded,
            window = dateHit?.window,
            expanded = terms.flatMap { expansions[it] ?: listOf("\"$it\"*") }.distinct()
        )
    }

    fun tokenise(raw: String): List<String> =
        raw.lowercase()
            // \p{M} keeps Devanagari whole - its vowel signs are category Mark, and without
            // them in the class a Hindi search term splits into single consonants.
            .split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
            .filter { it.length >= 2 && it !in stopwords }
            .distinct()

    private fun quoted(raw: String): List<String> =
        Regex("\"([^\"]{2,})\"")
            .findAll(raw)
            .map { it.groupValues[1].trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toList()
}
