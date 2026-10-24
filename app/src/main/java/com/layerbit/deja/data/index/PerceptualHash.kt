package com.layerbit.deja.data.index

import android.graphics.Bitmap

/**
 * A 64-bit difference hash: the image shrunk to 9x8 greyscale, then one bit per horizontal
 * neighbour pair saying which of the two was brighter.
 *
 * This is what finds the duplicate that matters. Exact matching on text and byte size only
 * catches a file copied verbatim; the common case is the same screen captured twice seconds
 * apart, or one image re-saved through a share sheet - identical to look at, different in every
 * byte. A dHash is unchanged by recompression, small crops and scaling, which is exactly the
 * difference between those two screenshots.
 *
 * It is computed from the bitmap the reader already decoded for OCR, so it costs no extra I/O.
 *
 * A perfectly flat image hashes to 0, which is also the "not computed" sentinel. That collision
 * is left alone deliberately: an image with no brightness variation at all has nothing to tell
 * two screenshots apart with, and those land in the "No readable text" cleanup group anyway.
 */
object PerceptualHash {

    private const val COLUMNS = 9
    private const val ROWS = 8

    fun of(bitmap: Bitmap): Long {
        if (bitmap.width < 2 || bitmap.height < 2) return 0L

        val small = runCatching {
            Bitmap.createScaledBitmap(bitmap, COLUMNS, ROWS, true)
        }.getOrNull() ?: return 0L

        val pixels = IntArray(COLUMNS * ROWS)
        runCatching { small.getPixels(pixels, 0, COLUMNS, 0, 0, COLUMNS, ROWS) }
            .onFailure { return 0L }
        if (small !== bitmap) small.recycle()

        var hash = 0L
        var bit = 0
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS - 1) {
                val left = luma(pixels[row * COLUMNS + column])
                val right = luma(pixels[row * COLUMNS + column + 1])
                if (left > right) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** Hamming distance. 0 is pixel-identical after the shrink; under about 6 is the same screen. */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Rec. 601 luma, integer arithmetic - this runs 72 times per screenshot. */
    private fun luma(colour: Int): Int =
        ((colour shr 16 and 0xFF) * 299 +
            (colour shr 8 and 0xFF) * 587 +
            (colour and 0xFF) * 114) / 1000
}
