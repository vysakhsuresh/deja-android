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

/**
 * @param text the lines joined in reading order, which is not the same as ML Kit's block order.
 * @param phash a 64-bit difference hash of the image; 0 when it could not be computed.
 */
data class ReadResult(
    val text: String,
    val lines: List<TextLine>,
    val phash: Long
)
