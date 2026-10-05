package bosca.localization.export

import bosca.localization.model.ExportFormat

/**
 * Dispatches an export request to the [LocalizationExporter] registered for a format.
 *
 * Exporters are pluggable and keyed by their [LocalizationExporter.format]. New formats
 * only need to add an exporter instance to the [exporters] list passed at construction
 * time; no changes to the service layer are required.
 */
class ExportEngine(private val exporters: List<LocalizationExporter>) {

    private val byFormat: Map<ExportFormat, LocalizationExporter> = exporters.associateBy { it.format }

    /**
     * Default constructor registering every exporter bundled with Bosca. Tests or
     * integrations that need a reduced set can pass a trimmed list explicitly.
     */
    constructor() : this(
        listOf(
            AndroidXmlExporter(),
            IosStringsExporter(),
            IosStringsdictExporter(),
            JsonI18nExporter(),
            JsonFlatExporter(),
            JsonNestedExporter(),
            ArbExporter(),
            XliffExporter()
        )
    )

    /** Returns the exporter for [format], or throws if none is registered. */
    fun exporterFor(format: ExportFormat): LocalizationExporter =
        byFormat[format] ?: throw IllegalArgumentException("No exporter registered for format: $format")
}
