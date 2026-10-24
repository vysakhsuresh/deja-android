package com.layerbit.deja.data.index

import com.layerbit.deja.data.model.Category
import com.layerbit.deja.data.model.Extracted
import com.layerbit.deja.data.model.ExtractedCodec
import com.layerbit.deja.data.ocr.TextLine

/**
 * Everything Deja concludes about a screenshot once the pixels are behind it.
 *
 * This exists as one pure function on purpose. It used to be four steps inlined in the middle of
 * the OCR loop, which meant the only way to run it was to read an image - and so improving the
 * classifier did nothing whatsoever for a library that was already indexed. Pulled out here, the
 * same function runs over rows straight from the database at thousands per second, and a better
 * extractor reaches four thousand old screenshots in the time it takes a progress bar to appear.
 *
 * Nothing in here may touch the disk, the network or the clock. Given the same inputs it must
 * give the same answer, because the re-derive pass depends on being able to replay it.
 */
data class Derived(
    val category: Category,
    val entities: List<Extracted>,
    val headline: String,
    val sourceApp: String,
    val searchBlob: String
)

object Derivation {

    fun derive(displayName: String, text: String, lines: List<TextLine>): Derived {
        val headline = TextLayout.headline(lines)

        // Filename first - it names the app the screenshot was *taken in*, which is the truth.
        // The headline names the app it is *of*, which is almost always the same thing and is
        // the only answer available on a phone that writes bare timestamps for filenames.
        val sourceApp = SourceApp.detect(displayName)
            ?: SourceApp.detectInText(headline).orEmpty()

        val entities = EntityExtractor.extract(text, lines)
        val category = Classifier.classify(text, headline, sourceApp, entities)

        return Derived(
            category = category,
            entities = entities,
            headline = headline,
            sourceApp = sourceApp,
            searchBlob = buildSearchBlob(text, headline, sourceApp, category, entities)
        )
    }

    /**
     * What full-text search matches against.
     *
     * The headline is in here twice over, in effect: once as itself and once as part of the body
     * text. That is not a mistake - FTS4 has no notion of field weighting, so the ranker in
     * [Ranker] does the weighting properly in Kotlin, and this blob's only job is recall. A term
     * that appears anywhere worth finding has to be in it.
     *
     * Sensitive values are deliberately absent. Finding "the screenshot with my PAN on it" by
     * searching for the word PAN is useful; surfacing the number itself by typing fragments of
     * it is not.
     */
    private fun buildSearchBlob(
        text: String,
        headline: String,
        sourceApp: String,
        category: Category,
        entities: List<Extracted>
    ): String = buildString {
        append(text)
        if (headline.isNotEmpty()) {
            append(' ')
            append(headline)
        }
        if (sourceApp.isNotEmpty()) {
            append(' ')
            append(sourceApp)
        }
        append(' ')
        append(category.label)
        val values = ExtractedCodec.searchableValues(entities)
        if (values.isNotEmpty()) {
            append(' ')
            append(values)
        }
    }
}
