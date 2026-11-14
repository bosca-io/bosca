package bosca.content.metadata.model

/**
 * How much live (not soft-deleted) content still points at a template.
 *
 * - [items]: documents, data and guides created from the template
 * - [collections]: collections created from the template
 * - [templates]: guide templates that use it for a step or step module
 */
data class TemplateUsage(
    val items: Int,
    val collections: Int,
    val templates: Int,
) {
    val total: Int get() = items + collections + templates
}

val TEMPLATE_CONTENT_TYPES = setOf(
    "bosca/v-document-template",
    "bosca/v-data-template",
    "bosca/v-guide-template",
    "bosca/v-collection-template",
)

fun isTemplateContentType(contentType: String?) = contentType in TEMPLATE_CONTENT_TYPES
