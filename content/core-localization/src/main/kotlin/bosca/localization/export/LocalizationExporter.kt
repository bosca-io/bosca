@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.export

import bosca.localization.model.ExportFormat
import bosca.localization.model.ExportResult
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationTranslation
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Renders a set of translations for a single language into a target file format.
 *
 * Exporters receive already-filtered inputs (by translation state) from the localization
 * service; they are responsible only for format-specific rendering and placeholder
 * conversion (ICU -> platform-specific where applicable).
 */
interface LocalizationExporter {

    /** The [ExportFormat] this exporter produces. Used by the engine to dispatch by format. */
    val format: ExportFormat

    /**
     * Renders the given [strings], their plain [translations], and per-string [pluralTranslations]
     * into the exporter's target format for the requested [languageTag].
     *
     * @param languageTag the BCP-47 language tag the export is being produced for
     * @param strings every string in the export set; exporters iterate these to enforce
     *  stable output order
     * @param translations lookup keyed by string ID for non-plural translations
     * @param pluralTranslations lookup keyed by string ID; each entry holds every category
     *  row for that string in the target language
     * @param sourceLanguage the BCP-47 tag of the project's source language, used by
     *  formats like XLIFF that embed a source-language attribute
     * @param sourceTranslations source-language translations keyed by string ID, used by
     *  XLIFF to populate `<source>` elements with actual text instead of keys
     */
    suspend fun export(
        languageTag: String,
        strings: List<LocalizationString>,
        translations: Map<UUID, LocalizationTranslation>,
        pluralTranslations: Map<UUID, List<LocalizationPluralTranslation>>,
        sourceLanguage: String = "en",
        sourceTranslations: Map<UUID, LocalizationTranslation> = emptyMap()
    ): ExportResult
}
