package com.layerbit.deja.data.index

/**
 * Tells text apart from glyph-shaped guessing.
 *
 * A Latin recogniser handed Devanagari cannot return Devanagari - its output alphabet is Latin,
 * so it returns the nearest Latin shapes it can find. "दिल्ली" comes back as something like
 * "fereft". That junk used to be stored as if it were text, which did three quiet kinds of
 * damage: it poisoned the search index with tokens that match nothing, it made the duplicate
 * hash effectively random so two identical screenshots stopped matching, and it filled the "text
 * Deja read" panel with nonsense - which makes the app look broken rather than honestly limited.
 *
 * The same measurement does double duty. Below one threshold it is a **router**: a page that
 * does not look like Latin is worth a second pass with the Devanagari recogniser. Above a higher
 * one it is a **filter**: text that still looks like nothing after both passes is not stored at
 * all.
 *
 * Two thresholds rather than one because the two decisions have completely different costs.
 * Routing wrongly costs one extra OCR pass and nothing else, since the better of the two results
 * is the one that gets kept. Discarding wrongly loses text that was really there. So the bar for
 * trying again is low and the bar for throwing away is high.
 */
object TextQuality {

    /** Above this share of suspicious words, try the other script's recogniser. */
    const val ROUTE_THRESHOLD = 0.45

    /** Above this, both passes failed and storing the result would be worse than storing none. */
    const val DISCARD_THRESHOLD = 0.75

    /**
     * Below this many judgeable words there is nothing to measure.
     *
     * A screenshot that is almost entirely numbers - a receipt, a bank balance, a calculator -
     * is perfectly readable and has barely any words in it. Guessing from two or three tokens
     * would throw those away.
     */
    private const val MIN_TOKENS = 8

    /**
     * \p{M} is not decoration. Devanagari vowel signs and the virama are Unicode
     * category Mark, not Letter, so a class of just \p{L}\p{N} splits दिल्ली into द, ल, ल -
     * three meaningless single consonants. That shreds Hindi search terms, breaks the
     * near-duplicate text comparison, and would have made TextQuality condemn every correctly
     * read Devanagari page as garbage.
     */
    private val splitter = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /**
     * The share of word-like tokens that look wrong. 0.0 is clean prose, near 1.0 is noise.
     *
     * Tokens holding a digit are never judged: serial numbers, IDs, reference codes and hashes
     * are legitimately unpronounceable, and they are exactly what this app is full of.
     */
    fun suspiciousShare(text: String): Double {
        if (text.isBlank()) return 0.0
        val judged = splitter.split(text)
            .filter { token -> token.isNotEmpty() && token.none(Char::isDigit) }
        if (judged.size < MIN_TOKENS) return 0.0
        return judged.count(::suspicious).toDouble() / judged.size
    }

    /** How much of the text is made of words that look real - the score used to pick a winner. */
    fun plausibleCharacters(text: String): Int {
        if (text.isBlank()) return 0
        return splitter.split(text)
            .filter { it.isNotEmpty() && !suspicious(it) }
            .sumOf { it.length }
    }

    private fun suspicious(token: String): Boolean {
        // The script test comes first, and the order is load-bearing. Anything outside the
        // Latin alphabet is not this check's business: once the Devanagari recogniser is in
        // play its output lands here, and judging देवनागरी by whether it holds Latin vowels -
        // or by how long it is - would condemn every correct reading of it.
        if (token.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return false

        // A lone Latin letter adrift between separators is a misread mark, not a word. "a" and
        // "I" are the exceptions and they are too common to matter either way.
        if (token.length < 2) return true

        val lower = token.lowercase()

        // Real words are lower case, Capitalised, or ALL CAPS. "aTfm" and "RIAl" are what a
        // recogniser produces when it is matching shapes it has no letters for.
        val shapely = token == lower ||
            token == token.uppercase() ||
            (token[0].isUpperCase() && token.drop(1) == token.drop(1).lowercase())
        if (!shapely) return true

        // Consonant runs. Short ones are ordinary abbreviations - gst, pnr, tv, km - so only a
        // longer one counts against the text.
        if (token.length >= 4 && lower.none { it in "aeiou" }) return true

        return false
    }
}
