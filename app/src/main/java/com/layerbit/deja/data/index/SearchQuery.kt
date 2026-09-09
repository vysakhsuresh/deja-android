package com.layerbit.deja.data.index

/**
 * Turns what someone types into an FTS MATCH expression.
 *
 * This is keyword search with the filler words dropped, not language understanding: "the wifi
 * password from the hotel" becomes `wifi* OR password* OR hotel*`. Recall comes from OR, and the
 * repository re-ranks the matches by how many terms actually hit, so the screenshot containing
 * all three still comes first.
 */
object SearchQuery {

    private val stopwords = setOf(
        "the", "a", "an", "and", "or", "of", "for", "from", "in", "on", "at", "to", "with",
        "my", "me", "i", "it", "is", "was", "that", "this", "there", "was", "were", "did",
        "find", "show", "get", "what", "where", "which", "when", "who", "some", "any",
        "screenshot", "screenshots", "screen", "shot", "picture", "photo", "image"
    )

    fun tokenise(raw: String): List<String> =
        raw.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in stopwords }
            .distinct()

    /** Null when the query carries no searchable term, so callers can skip querying entirely. */
    fun toMatch(raw: String): String? {
        val tokens = tokenise(raw)
        if (tokens.isEmpty()) return null
        // Quote each term so punctuation the tokeniser missed cannot be read as FTS syntax.
        return tokens.joinToString(" OR ") { "\"$it\"*" }
    }
}
