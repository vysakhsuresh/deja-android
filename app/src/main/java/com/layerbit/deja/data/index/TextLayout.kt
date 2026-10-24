package com.layerbit.deja.data.index

import com.layerbit.deja.data.ocr.TextLine

/**
 * What the shape of a screenshot tells you, and how that shape is stored.
 *
 * Three jobs:
 *
 *  - **Reading order.** ML Kit returns blocks in the order its model found them, which on a
 *    two-column layout interleaves the columns. Every regex in [EntityExtractor] that looks for a
 *    label followed by a value depends on the text being in the order a person would read it, so
 *    the lines are sorted top-to-bottom and then left-to-right before they are joined.
 *  - **The headline.** Near the top of almost every screenshot is an app bar with the app's or
 *    the screen's name in it: "Payments", "Boarding pass", "WhatsApp". It is the single most
 *    informative string on the screen and it was being thrown away. It is also the only app
 *    signal that exists at all on Pixel and stock builds, which write no app name into the
 *    filename.
 *  - **Storage.** A compact text encoding, so the geometry survives in the index and the engine
 *    can re-derive from it later without reading the image again.
 */
object TextLayout {

    /**
     * Two lines whose tops are within this many per-mille are treated as the same visual row and
     * ordered left-to-right. Roughly half a line height on a phone screenshot.
     */
    private const val ROW_TOLERANCE = 14

    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    /** The status bar. Clock, battery and signal live here and are never worth reading. */
    private const val STATUS_BAR_BOTTOM = 32

    /** Below this, a line is page content rather than a title. */
    private const val HEADLINE_BOTTOM = 210

    private const val MAX_HEADLINE_LINES = 3
    private const val MAX_HEADLINE_CHARS = 36

    /** Clock, battery percentage, signal strength - status-bar debris, never a title. */
    private val statusJunk = Regex(
        "^(?:\\d{1,2}[:.]\\d{2}(?:\\s?[ap]m)?|\\d{1,3}\\s?%|[\\d.]+\\s?(?:kb|mb|gb)/s)$",
        RegexOption.IGNORE_CASE
    )

    fun readingOrder(lines: List<TextLine>): List<TextLine> =
        lines.sortedWith(compareBy({ it.top / ROW_TOLERANCE }, { it.left }))

    /** The lines joined the way a person reads them. */
    fun flatten(lines: List<TextLine>): String =
        readingOrder(lines).joinToString("\n") { it.text }

    /**
     * The app bar and the screen title, as one string.
     *
     * Picks from the band between the status bar and roughly a fifth of the way down, prefers the
     * largest type in that band, and keeps the result short - a title is a few words. A long line
     * up there is body copy on a page that happens to start high, not a heading.
     */
    fun headline(lines: List<TextLine>): String {
        if (lines.isEmpty()) return ""

        val candidates = lines.filter { line ->
            line.bottom > STATUS_BAR_BOTTOM &&
                line.top < HEADLINE_BOTTOM &&
                line.text.length in 2..MAX_HEADLINE_CHARS &&
                line.text.any(Char::isLetter) &&
                !statusJunk.matches(line.text.trim())
        }
        if (candidates.isEmpty()) return ""

        // Biggest type first, then whichever sits highest, then reading order for the output so
        // "Gmail / Inbox" does not come back as "Inbox / Gmail".
        val chosen = candidates
            .sortedWith(compareByDescending<TextLine> { it.height }.thenBy { it.top })
            .take(MAX_HEADLINE_LINES)

        return readingOrder(chosen).joinToString(" ") { it.text }.trim()
    }

    /**
     * The line holding [needle], with the line above it. Label and value are as often stacked as
     * side by side ("Verification code" on one line, "481902" on the next), and a flat-text regex
     * cannot tell the difference between "the next line" and "somewhere further down the page".
     */
    fun contextAround(lines: List<TextLine>, index: Int): String {
        if (index !in lines.indices) return ""
        val above = if (index > 0) lines[index - 1].text else ""
        return "$above ${lines[index].text}".trim()
    }

    /** The tallest line on the screen, used to judge whether a value is set in display type. */
    fun maxHeight(lines: List<TextLine>): Int = lines.maxOfOrNull { it.height } ?: 0

    fun encode(lines: List<TextLine>): String =
        lines.joinToString(RECORD.toString()) { line ->
            listOf(
                line.top.toString(),
                line.left.toString(),
                line.width.toString(),
                line.height.toString(),
                line.text.replace(FIELD, ' ').replace(RECORD, ' ')
            ).joinToString(FIELD.toString())
        }

    fun decode(blob: String): List<TextLine> {
        if (blob.isEmpty()) return emptyList()
        return blob.split(RECORD).mapNotNull { record ->
            // Limit 5 so a value containing the field separator cannot shift the columns.
            val parts = record.split(FIELD, limit = 5)
            if (parts.size < 5) return@mapNotNull null
            val top = parts[0].toIntOrNull() ?: return@mapNotNull null
            val left = parts[1].toIntOrNull() ?: return@mapNotNull null
            val width = parts[2].toIntOrNull() ?: return@mapNotNull null
            val height = parts[3].toIntOrNull() ?: return@mapNotNull null
            TextLine(text = parts[4], top = top, left = left, width = width, height = height)
        }
    }
}
