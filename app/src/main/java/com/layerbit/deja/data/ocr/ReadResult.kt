package com.layerbit.deja.data.ocr

/**
 * One line of recognised text and where it sat on the screen.
 *
 * Keeping the geometry is what separates this engine from a text dump. "₹450" means nothing on
 * its own; "₹450 on the same line as the word Total, set in the largest type on the screen" is a
 * receipt total. Position and size are the only things that tell those apart, and ML Kit hands
 * them over for free.
 *
 * Coordinates are per-mille of the image's own dimensions (0..1000) rather than pixels, so the
 * same screenshot read on a 1080p and a 1440p phone produces the same numbers, and the values
 * survive any downscale the reader applies before handing the bitmap to ML Kit.
 */
data class TextLine(
    val text: String,
    val top: Int,
    val left: Int,
    val width: Int,
    /** Line height, which is the only font-size proxy available - and so marks the headings. */
    val height: Int
) {
    val bottom: Int get() = top + height
}

/** Which recogniser produced the text, or that neither of them could. */
enum class ReadScript(val id: String) {
    LATIN("latin"),
    DEVANAGARI("devanagari"),

    /**
     * Both passes came back looking like shape-matching rather than language - a script Deja
     * has no model for, or an image with no real text in it. Recorded rather than papered over,
     * so the app can say so instead of showing nonsense.
     */
    NONE("none");

    companion object {
        fun fromId(id: String): ReadScript = entries.firstOrNull { it.id == id } ?: LATIN
    }
}

/**
 * @param text the lines joined in reading order, which is not the same as ML Kit's block order.
 * @param phash a 64-bit difference hash of the image; 0 when it could not be computed.
 * @param codes raw payloads of any QR or barcode in the picture, left unparsed on purpose so a
 *   better parser later is a re-derive rather than a re-read of every image on the phone.
 */
data class ReadResult(
    val text: String,
    val lines: List<TextLine>,
    val phash: Long,
    val codes: List<String>,
    val script: ReadScript
)
