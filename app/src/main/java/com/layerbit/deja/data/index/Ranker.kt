package com.layerbit.deja.data.index

import com.layerbit.deja.data.db.ShotEntity
import com.layerbit.deja.data.model.Category

/**
 * Decides what comes first.
 *
 * FTS gives recall - which rows contain the words at all - and nothing else useful; FTS4 has no
 * BM25 and no concept of one column mattering more than another. So ordering happens here, in
 * Kotlin, over the few hundred rows the match returned, where every field is in hand and the
 * rules can say what they actually mean.
 *
 * The rules, in rough order of how much they move a result:
 *
 *  - **Matching everything beats matching some.** The old ranker counted hits linearly, so two
 *    screenshots matching one word each outranked nothing, and the one matching both words was
 *    only marginally ahead. Completeness gets its own large bonus.
 *  - **Where a word appears matters more than how often.** A term in the app name or the screen
 *    title is a far stronger signal than the same term buried in body text, because those two
 *    strings are short and deliberate.
 *  - **Words near each other mean more** than words at opposite ends of a long receipt.
 *  - **Recent beats old, but only to break near-ties** - never enough to push an exact match
 *    below a vague one, which is what pure date ordering did.
 */
object Ranker {

    private const val IN_APP = 4.0
    private const val IN_HEADLINE = 3.5
    private const val IN_CATEGORY = 2.5
    private const val IN_TEXT = 1.0
    private const val PER_REPEAT = 0.25
    private const val MAX_REPEATS = 6

    private const val PHRASE = 8.0
    private const val COVERAGE = 5.0
    private const val MAX_COVERAGE_TERMS = 3
    private const val PROXIMITY = 3.0
    private const val PROXIMITY_WINDOW = 140
    private const val PINNED = 1.5

    private const val DAY_MILLIS = 86_400_000.0

    fun rank(shots: List<ShotEntity>, plan: SearchPlan, now: Long): List<ShotEntity> =
        shots
            .map { shot -> shot to score(shot, plan, now) }
            .sortedWith(
                compareByDescending<Pair<ShotEntity, Double>> { it.second }
                    .thenByDescending { it.first.dateTakenMillis }
            )
            .map { it.first }

    /** Anything carrying an excluded word is not a weaker result, it is the wrong result. */
    fun excludeRejects(shots: List<ShotEntity>, plan: SearchPlan): List<ShotEntity> {
        if (plan.excluded.isEmpty()) return shots
        return shots.filterNot { shot ->
            val haystack = haystackOf(shot)
            plan.excluded.any { haystack.contains(it) }
        }
    }

    private fun haystackOf(shot: ShotEntity): String =
        buildString {
            append(shot.searchBlob.lowercase())
            append(' ')
            append(shot.sourceApp.lowercase())
            append(' ')
            append(shot.headline.lowercase())
        }

    private fun score(shot: ShotEntity, plan: SearchPlan, now: Long): Double {
        val app = shot.sourceApp.lowercase()
        val headline = shot.headline.lowercase()
        val category = Category.fromId(shot.category).label.lowercase()
        val text = shot.searchBlob.lowercase()

        var score = 0.0
        var matched = 0

        plan.terms.forEach { term ->
            var termScore = 0.0
            if (app.contains(term)) termScore += IN_APP
            if (headline.contains(term)) termScore += IN_HEADLINE
            if (category.contains(term)) termScore += IN_CATEGORY

            val repeats = occurrences(text, term)
            if (repeats > 0) {
                termScore += IN_TEXT + minOf(repeats, MAX_REPEATS) * PER_REPEAT
            }

            if (termScore > 0.0) {
                matched++
                score += termScore
            }
        }

        if (plan.terms.isNotEmpty() && matched == plan.terms.size) {
            score += COVERAGE * minOf(plan.terms.size, MAX_COVERAGE_TERMS)
        }

        plan.phrases.forEach { phrase ->
            if (text.contains(phrase) || headline.contains(phrase)) score += PHRASE
        }

        if (plan.terms.size >= 2) {
            val positions = plan.terms.mapNotNull { term ->
                text.indexOf(term).takeIf { it >= 0 }
            }
            if (positions.size == plan.terms.size) {
                val spread = (positions.maxOrNull() ?: 0) - (positions.minOrNull() ?: 0)
                if (spread <= PROXIMITY_WINDOW) score += PROXIMITY
            }
        }

        val ageDays = (now - shot.dateTakenMillis) / DAY_MILLIS
        score += when {
            ageDays < 2 -> 2.0
            ageDays < 14 -> 1.2
            ageDays < 60 -> 0.5
            else -> 0.0
        }

        if (shot.pinned) score += PINNED

        return score
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0 && count < MAX_REPEATS) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }
}
