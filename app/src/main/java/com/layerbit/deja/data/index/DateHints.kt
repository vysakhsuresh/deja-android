package com.layerbit.deja.data.index

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A span of time pulled out of a query, and a label short enough to show back to the user. */
data class DateWindow(val from: Long, val to: Long, val label: String)

/**
 * Reads the time out of a search.
 *
 * "receipt from last week" was being tokenised into `receipt` and `week`, with "last" thrown away
 * as a stopword - so the most specific thing in the sentence, the only part that narrows four
 * thousand screenshots down to a handful, was the one part the engine discarded. People reach for
 * time first when hunting a screenshot, because they remember roughly when far better than they
 * remember the words in it.
 *
 * Whatever is matched here is removed from the search terms, so "receipt last week" searches for
 * `receipt` inside that window instead of searching for the word "week".
 */
object DateHints {

    data class Hit(val window: DateWindow, val consumed: Set<String>)

    private val months = mapOf(
        "january" to Month.JANUARY, "jan" to Month.JANUARY,
        "february" to Month.FEBRUARY, "feb" to Month.FEBRUARY,
        "march" to Month.MARCH, "mar" to Month.MARCH,
        "april" to Month.APRIL, "apr" to Month.APRIL,
        "may" to Month.MAY,
        "june" to Month.JUNE, "jun" to Month.JUNE,
        "july" to Month.JULY, "jul" to Month.JULY,
        "august" to Month.AUGUST, "aug" to Month.AUGUST,
        "september" to Month.SEPTEMBER, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
        "october" to Month.OCTOBER, "oct" to Month.OCTOBER,
        "november" to Month.NOVEMBER, "nov" to Month.NOVEMBER,
        "december" to Month.DECEMBER, "dec" to Month.DECEMBER
    )

    private val relativeCount = Regex("\\b(?:last|past)\\s+(\\d{1,3})\\s+(day|week|month|year)s?\\b")

    fun find(raw: String, zone: ZoneId = ZoneId.systemDefault()): Hit? {
        val lower = raw.lowercase()
        val today = LocalDate.now(zone)

        relativeCount.find(lower)?.let { match ->
            val count = match.groupValues[1].toIntOrNull() ?: return@let
            if (count !in 1..999) return@let
            val unit = match.groupValues[2]
            val start = when (unit) {
                "day" -> today.minusDays(count.toLong())
                "week" -> today.minusWeeks(count.toLong())
                "month" -> today.minusMonths(count.toLong())
                else -> today.minusYears(count.toLong())
            }
            return hit(start, today, "last $count ${unit}s", match.value.split(" ").toSet(), zone)
        }

        when {
            lower.contains("today") ->
                return hit(today, today, "today", setOf("today"), zone)

            lower.contains("yesterday") ->
                return hit(today.minusDays(1), today.minusDays(1), "yesterday", setOf("yesterday"), zone)

            lower.contains("last week") -> {
                val startOfThisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                return hit(
                    startOfThisWeek.minusWeeks(1),
                    startOfThisWeek.minusDays(1),
                    "last week",
                    setOf("last", "week"),
                    zone
                )
            }

            lower.contains("this week") ->
                return hit(
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    today,
                    "this week",
                    setOf("this", "week"),
                    zone
                )

            lower.contains("last month") -> {
                val startOfLastMonth = today.minusMonths(1).withDayOfMonth(1)
                return hit(
                    startOfLastMonth,
                    startOfLastMonth.with(TemporalAdjusters.lastDayOfMonth()),
                    "last month",
                    setOf("last", "month"),
                    zone
                )
            }

            lower.contains("this month") ->
                return hit(today.withDayOfMonth(1), today, "this month", setOf("this", "month"), zone)

            lower.contains("last year") -> {
                val year = today.year - 1
                return hit(
                    LocalDate.of(year, 1, 1),
                    LocalDate.of(year, 12, 31),
                    year.toString(),
                    setOf("last", "year"),
                    zone
                )
            }

            lower.contains("this year") ->
                return hit(
                    LocalDate.of(today.year, 1, 1),
                    today,
                    today.year.toString(),
                    setOf("this", "year"),
                    zone
                )

            lower.contains("recently") || lower.contains("recent") ->
                return hit(today.minusDays(14), today, "recently", setOf("recently", "recent"), zone)
        }

        // A month name, optionally followed by a year. Without a year it means the most recent
        // time that month happened - asking for "june" in March means last June, not next June.
        val words = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        words.forEachIndexed { index, word ->
            val month = months[word] ?: return@forEachIndexed
            val explicitYear = words.getOrNull(index + 1)?.toIntOrNull()?.takeIf { it in 1990..2100 }
            val year = explicitYear
                ?: if (month.value <= today.monthValue) today.year else today.year - 1
            val start = LocalDate.of(year, month, 1)
            val consumed = if (explicitYear != null) setOf(word, explicitYear.toString()) else setOf(word)
            val label = if (explicitYear != null) "$word $explicitYear" else word
            return hit(start, start.with(TemporalAdjusters.lastDayOfMonth()), label, consumed, zone)
        }

        // A bare year on its own.
        words.firstOrNull { word ->
            word.length == 4 && (word.toIntOrNull()?.let { it in 1990..2100 } == true)
        }?.let { word ->
            val year = word.toInt()
            return hit(
                LocalDate.of(year, 1, 1),
                LocalDate.of(year, 12, 31),
                word,
                setOf(word),
                zone
            )
        }

        return null
    }

    private fun hit(
        from: LocalDate,
        to: LocalDate,
        label: String,
        consumed: Set<String>,
        zone: ZoneId
    ): Hit = Hit(
        window = DateWindow(
            from = from.atStartOfDay(zone).toInstant().toEpochMilli(),
            // End of the closing day, so a screenshot taken at 23:50 is inside "today".
            to = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            label = label
        ),
        consumed = consumed
    )
}
