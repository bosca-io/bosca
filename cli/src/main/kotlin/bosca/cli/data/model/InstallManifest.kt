package bosca.cli.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Root manifest describing all content to bootstrap into a Bosca instance.
 * Contains templates, collections, metadata items, and relationships
 * that together define a complete content structure.
 */
@Serializable
data class InstallManifest(
    /** Human-readable name identifying this install package */
    val name: String,
    /** Semantic version of this manifest for tracking changes */
    val version: String,
    /** Brief explanation of what content this manifest provisions */
    val description: String,
    /** Document templates to create (e.g. Blog Post, Article) */
    val documentTemplates: List<DocumentTemplateDefinition> = emptyList(),
    /** Guide templates to create (e.g. Tutorial Series, Reading Plan) */
    val guideTemplates: List<GuideTemplateDefinition> = emptyList(),
    /** Data templates to create (e.g. structured attribute sets, tables) */
    val dataTemplates: List<DataTemplateDefinition> = emptyList(),
    /** Collection templates to create (e.g. Authors, Topics) */
    val collectionTemplates: List<CollectionTemplateDefinition> = emptyList(),
    /** Standalone collections to create (folders, groups) */
    val collections: List<CollectionDefinition> = emptyList(),
    /** Metadata items to create (documents, images, data) */
    val metadata: List<MetadataDefinition> = emptyList(),
    /** Relationships between metadata items to establish after creation */
    val relationships: List<RelationshipDefinition> = emptyList(),
)

/**
 * Defines a document template that controls the schema and editing
 * experience for a particular type of rich-text metadata item.
 */
@Serializable
data class DocumentTemplateDefinition(
    /** Local reference ID used to link other manifest entries to this template */
    val ref: String,
    /** Display name of the template */
    val name: String,
    /** Content type for the template metadata item in the admin */
    val contentType: String = "bosca/v-document-template",
    /** BCP 47 language tag */
    val languageTag: String = "en",
    /** URL-friendly slug for idempotent provisioning */
    val slug: String? = null,
    /** Template attribute definitions describing available fields */
    val attributes: List<TemplateAttributeDefinition> = emptyList(),
    /** Editable content regions within documents using this template */
    val containers: List<ContainerDefinition> = emptyList(),
    /** Default document content tree (ProseMirror/TipTap JSON) */
    val defaultContent: JsonElement? = null,
    /** Default attribute values for new documents */
    val defaultAttributes: JsonObject? = null,
    /** JSON schema for validating document content */
    val schema: JsonElement? = null,
    /** Template-specific configuration */
    val configuration: JsonElement? = null,
    /** Trait IDs to assign to the template metadata item */
    val traitIds: List<String> = emptyList(),
)

/**
 * Defines a guide template that structures multi-step content such as
 * tutorial series, reading plans, or onboarding flows. Each step
 * references an existing document template for its content structure.
 */
@Serializable
data class GuideTemplateDefinition(
    /** Local reference ID used to link other manifest entries to this template */
    val ref: String,
    /** Display name of the template */
    val name: String,
    /** Content type for the template metadata item in the admin */
    val contentType: String = "bosca/v-guide-template",
    /** BCP 47 language tag */
    val languageTag: String = "en",
    /** URL-friendly slug for idempotent provisioning */
    val slug: String? = null,
    /** iCalendar recurrence rule for calendar-based guides */
    val rrule: String = "",
    /** Guide progression type: LINEAR, LINEAR_PROGRESS, CALENDAR, CALENDAR_PROGRESS */
    val type: String = "LINEAR",
    /** Template attribute definitions for guides using this template */
    val attributes: List<TemplateAttributeDefinition> = emptyList(),
    /** Default attribute values for new guides */
    val defaultAttributes: JsonObject? = null,
    /** Template-specific configuration */
    val configuration: JsonElement? = null,
    /** Steps that define the guide structure, each referencing a document template */
    val steps: List<GuideTemplateStepDefinition> = emptyList(),
    /** Trait IDs to assign to the template metadata item */
    val traitIds: List<String> = emptyList(),
)

/**
 * A single step within a guide template, referencing a document template
 * that defines the content structure for that step.
 */
@Serializable
data class GuideTemplateStepDefinition(
    /** Ref to a document template in this manifest that defines the step's content structure */
    val templateRef: String,
    /** Sub-sections within this step, each referencing a document template */
    val modules: List<GuideTemplateStepModuleDefinition> = emptyList(),
)

/**
 * A module (sub-section) within a guide template step,
 * referencing a document template for its content structure.
 */
@Serializable
data class GuideTemplateStepModuleDefinition(
    /** Ref to a document template in this manifest that defines the module's content structure */
    val templateRef: String,
)

/**
 * Defines a data template for structured key-value attribute sets or
 * tabular data, providing a schema for non-document content items.
 */
@Serializable
data class DataTemplateDefinition(
    /** Local reference ID used to link other manifest entries to this template */
    val ref: String,
    /** Display name of the template */
    val name: String,
    /** Content type for the template metadata item in the admin */
    val contentType: String = "bosca/v-data-template",
    /** BCP 47 language tag */
    val languageTag: String = "en",
    /** URL-friendly slug for idempotent provisioning */
    val slug: String? = null,
    /** Data storage type: ATTRIBUTES (key-value pairs) or TABLE (rows/columns) */
    val type: String = "ATTRIBUTES",
    /** Template attribute definitions for data items using this template */
    val attributes: List<TemplateAttributeDefinition> = emptyList(),
    /** Default attribute values for new data items */
    val defaultAttributes: JsonObject? = null,
    /** Trait IDs to assign to the template metadata item */
    val traitIds: List<String> = emptyList(),
)

/**
 * Defines a collection template that controls the structure and
 * behavior of collections (e.g. an Authors collection with bio fields).
 */
@Serializable
data class CollectionTemplateDefinition(
    /** Local reference ID used to link other manifest entries to this template */
    val ref: String,
    /** Display name of the template */
    val name: String,
    /** Content type for the template metadata item in the admin */
    val contentType: String = "bosca/v-collection-template",
    /** BCP 47 language tag */
    val languageTag: String = "en",
    /** URL-friendly slug for idempotent provisioning */
    val slug: String? = null,
    /** Template attribute definitions for collections using this template */
    val attributes: List<TemplateAttributeDefinition> = emptyList(),
    /** Default attribute values for new collections */
    val defaultAttributes: JsonObject? = null,
    /** Content type and attribute filters */
    val filters: List<FilterDefinition> = emptyList(),
    /** Default sort ordering for child items */
    val ordering: List<OrderingDefinition> = emptyList(),
    /** Template-specific configuration */
    val configuration: JsonElement? = null,
    /** Trait IDs to assign to the template metadata item */
    val traitIds: List<String> = emptyList(),
)

/**
 * A single attribute field within a template, defining its data type,
 * UI presentation, and validation constraints.
 */
@Serializable
data class TemplateAttributeDefinition(
    /** Unique key for this attribute within the template */
    val key: String,
    /** Display name shown in the UI */
    val name: String,
    /** Explanation of what this attribute captures */
    val description: String,
    /** Data type for validation: STRING, INT, FLOAT, DATE, DATETIME, METADATA, COLLECTION, PROFILE */
    val type: String,
    /** UI widget: INPUT, TEXTAREA, IMAGE, FILE, COLLECTION, METADATA, PROFILE */
    val ui: String,
    /** Whether this attribute accepts multiple values */
    val list: Boolean = false,
    /** Where the attribute is stored: ITEM or RELATIONSHIP */
    val location: String? = null,
    /** Optional JSON configuration for the attribute */
    val configuration: JsonElement? = null,
)

/**
 * An editable content region within a document template,
 * defining what kind of content can be placed in it.
 */
@Serializable
data class ContainerDefinition(
    /** Unique identifier for this container within the template */
    val id: String,
    /** Display name (e.g. "Main Content", "Sidebar") */
    val name: String,
    /** Explanation of the container's purpose */
    val description: String,
    /** Content source type: STANDARD, BIBLE, METADATA */
    val type: String = "STANDARD",
    /** Supplementary content key linking this container to external content */
    val supplementaryKey: String? = null,
    /** Content type filters restricting what can be placed here */
    val filters: List<String> = emptyList(),
)

/**
 * A named filter within a collection template, used to narrow
 * down which child items are displayed.
 */
@Serializable
data class FilterDefinition(
    /** Display name for this filter */
    val name: String,
    /** The filter expression or query string */
    val filter: String,
)

/**
 * Sort ordering specification for child items in a collection.
 */
@Serializable
data class OrderingDefinition(
    /** Field name to sort by */
    val field: String? = null,
    /** Where the ordering applies: ITEM or RELATIONSHIP */
    val location: String? = null,
    /** Sort direction: ASCENDING or DESCENDING */
    val order: String = "ASCENDING",
    /** JSON path segments for nested attribute sorting */
    val path: List<String> = emptyList(),
    /** Data type for correct comparison */
    val type: String? = null,
)

/**
 * Defines a collection to create in the Bosca instance,
 * optionally referencing a collection template.
 */
@Serializable
data class CollectionDefinition(
    /** Local reference ID for linking from other manifest entries */
    val ref: String,
    /** Display name of the collection */
    val name: String,
    /** Description of the collection's purpose */
    val description: String? = null,
    /** URL-friendly slug */
    val slug: String? = null,
    /** Collection type: STANDARD, FOLDER, ROOT, QUEUE, SYSTEM */
    val collectionType: String = "STANDARD",
    /** Reference to a collection template defined in this manifest */
    val templateRef: String? = null,
    /** Reference to a parent collection defined in this manifest */
    val parentRef: String? = null,
    /** Custom attributes to set on the collection */
    val attributes: JsonObject? = null,
    /** Trait IDs to assign */
    val traitIds: List<String> = emptyList(),
    /** Whether the collection should be public */
    val public: Boolean = false,
    /** Whether the collection list should be public */
    val publicList: Boolean = false,
    /** BCP 47 language tag */
    val languageTag: String = "en",
)

/**
 * Defines a metadata item (document, image, data, etc.) to create,
 * optionally using a document template for structure.
 */
@Serializable
data class MetadataDefinition(
    /** Local reference ID for linking from relationships and collections */
    val ref: String,
    /** Display name of the metadata item */
    val name: String,
    /** MIME content type (e.g. "text/html", "image/jpeg") */
    val contentType: String,
    /** BCP 47 language tag */
    val languageTag: String = "en",
    /** URL-friendly slug */
    val slug: String? = null,
    /** Reference to a document template defined in this manifest */
    val documentTemplateRef: String? = null,
    /** Document content (title + ProseMirror JSON content tree) */
    val document: DocumentContentDefinition? = null,
    /** Reference to the parent collection in this manifest */
    val parentRef: String? = null,
    /** Custom attributes */
    val attributes: JsonObject? = null,
    /** Path to a local file (relative to manifest) to upload as content */
    val contentFile: String? = null,
    /** Inline text content to set */
    val textContent: String? = null,
    /** Supplementary items (thumbnails, attachments, etc.) */
    val supplementary: List<SupplementaryDefinition> = emptyList(),
    /** Trait IDs to assign */
    val traitIds: List<String> = emptyList(),
    /** Whether the metadata item should be public */
    val public: Boolean = false,
    /** Whether the content should be publicly accessible */
    val publicContent: Boolean = false,
    /** Whether supplementary content should be publicly accessible */
    val publicSupplementary: Boolean = false,
)

/**
 * Rich text document content to set on a metadata item,
 * structured as a ProseMirror/TipTap JSON tree.
 */
@Serializable
data class DocumentContentDefinition(
    /** Display title of the document */
    val title: String,
    /** JSON document tree representing the rich text content */
    val content: JsonElement,
)

/**
 * A supplementary content item (e.g. thumbnail, audio, transcript)
 * to attach to a metadata item.
 */
@Serializable
data class SupplementaryDefinition(
    /** Unique key within the parent metadata (e.g. "thumbnail", "audio") */
    val key: String,
    /** Display name */
    val name: String,
    /** MIME content type */
    val contentType: String,
    /** Path to local file (relative to manifest) to upload */
    val file: String? = null,
    /** Inline text content to set */
    val textContent: String? = null,
    /** Custom attributes */
    val attributes: JsonObject? = null,
)

/**
 * A directional relationship between two metadata items,
 * referenced by their local manifest ref IDs.
 */
@Serializable
data class RelationshipDefinition(
    /** Local ref ID of the source metadata item */
    val fromRef: String,
    /** Local ref ID of the target metadata item */
    val toRef: String,
    /** Relationship type (e.g. "thumbnail", "related", "author") */
    val relationship: String,
    /** Custom attributes on the relationship */
    val attributes: JsonObject? = null,
)
