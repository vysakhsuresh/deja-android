package com.layerbit.deja.data.index

/**
 * Two numbers that decide how much work an engine improvement costs the user.
 *
 * The first version of Deja computed the category, the extracted values and the search blob once,
 * inside the OCR loop, and stored the answers. That made every later improvement worthless to
 * anyone who already had a library: a better classifier changed nothing about the four thousand
 * screenshots already read, short of reading them all again.
 *
 * Splitting the work in two fixes that. Reading an image is expensive and rare; deciding what the
 * text means is cheap and will change often. So each row records which version of each stage
 * produced it, and the worker brings stale rows forward:
 *
 *  - [DERIVE] covers everything that can be recomputed from text already in the index - the
 *    classifier, the extractor, the search blob, which app the headline names. Bumping it
 *    re-derives the whole library from the database in a second or two, with no file I/O at all.
 *  - [READ] covers anything that needs the picture back: a change to what OCR captures, a new
 *    recogniser, barcode decoding. Bumping it schedules a background re-read that leaves the
 *    index fully usable the whole time it runs.
 *
 * The rule is simply: change `Classifier`, `EntityExtractor`, `SourceApp`, `TextLayout.headline`
 * or the search blob, bump [DERIVE]. Change `ScreenshotTextReader`, bump [READ].
 */
object EngineVersion {

    /**
     * 3 - QR and barcode payloads are captured, and a page the Latin recogniser cannot make
     * sense of is given a second pass with the Devanagari one. Neither can be recovered from
     * text already in the index, so older rows are re-read in the background.
     *
     * (2 added line geometry and a perceptual hash. 1 was text only.)
     */
    const val READ = 3

    /**
     * 2 - barcode payloads are parsed into entities and into the search blob.
     *
     * (1 added headline-aware classification and layout-aware extraction.)
     */
    const val DERIVE = 2

    /**
     * A screenshot whose read failed this many times is left alone.
     *
     * Not zero, because the old engine stored an empty result on the first failure and the id
     * went straight into the indexed set, so one transient failure - an out-of-memory decode, a
     * recogniser that had not warmed up - meant that screenshot was never readable again. Not
     * unlimited either, because a genuinely unreadable file would then be retried on every scan
     * forever.
     */
    const val MAX_READ_ATTEMPTS = 3
}
