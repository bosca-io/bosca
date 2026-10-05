package bosca.content.metadata.service

import bosca.attributes.TemplateAttributeInput
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.collection.model.CollectionTemplate
import bosca.content.collection.model.CollectionTemplateAttribute
import bosca.content.collection.model.CollectionTemplateAttributeWorkflow
import bosca.content.metadata.model.CollectionTemplateFilters
import bosca.content.metadata.model.CollectionTemplateFiltersInput
import bosca.content.metadata.model.CollectionTemplateInput
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.ordering.OrderingInput
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing collection templates. Collection templates define the structure,
 * attributes, configuration, filters, and ordering rules that collections of a particular
 * type should conform to.
 */
interface CollectionTemplateService : Service {

    /**
     * Persists a complete collection template definition, creating or updating it.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param template the complete template definition to save
     */
    suspend fun saveTemplate(id: UUID, version: Int, template: CollectionTemplateInput)

    /**
     * Retrieves all collection templates in the system.
     *
     * @return the complete list of collection templates
     */
    suspend fun getAll(): List<CollectionTemplate>

    /**
     * Looks up a collection template by its metadata identifier and version.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the collection template, or null if not found
     */
    suspend fun getCollectionTemplate(id: UUID, version: Int): CollectionTemplate?

    /**
     * Registers a batch loader for efficiently fetching collection templates by metadata
     * cache key.
     *
     * @param batch the batch accumulator to populate with collection template data
     */
    suspend fun addCollectionTemplatesBatch(batch: Batch<MetadataCacheKeyId, CollectionTemplate>)

    /**
     * Retrieves the attribute definitions for a collection template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the list of template attribute definitions
     */
    suspend fun getCollectionTemplateAttributes(id: UUID, version: Int): List<CollectionTemplateAttribute>

    /**
     * Retrieves the workflow configurations associated with a specific attribute key
     * on a collection template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param key the attribute key whose workflows should be retrieved
     * @return the list of attribute workflow configurations
     */
    suspend fun getCollectionTemplateAttributeWorkflows(id: UUID, version: Int, key: String): List<CollectionTemplateAttributeWorkflow>

    /**
     * Adds a new attribute definition to a collection template at the specified sort position.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param attribute the attribute definition to add
     * @param sort the sort position for the new attribute
     * @return the newly created template attribute
     */
    suspend fun addAttribute(
        metadataId: UUID,
        version: Int,
        attribute: TemplateAttributeInput,
        sort: Int
    ): TemplateAttribute

    /**
     * Removes an attribute definition from a collection template by its key.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param key the key of the attribute to remove
     */
    suspend fun deleteAttribute(
        metadataId: UUID,
        version: Int,
        key: String
    )

    /**
     * Sets the default attribute values for a collection template. These defaults are
     * applied to new collections created from this template.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param attributes the default attributes JSON, or null to clear
     */
    suspend fun setDefaultAttributes(
        metadataId: UUID,
        version: Int,
        attributes: JsonElement?
    )

    /**
     * Replaces all attribute definitions on a collection template with the provided list.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param attributes the complete list of attribute definitions to set
     */
    suspend fun setAttributes(
        metadataId: UUID,
        version: Int,
        attributes: List<TemplateAttributeInput>
    )

    /**
     * Sets the configuration for a collection template, controlling template-specific behavior.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param configuration the configuration JSON, or null to clear
     */
    suspend fun setConfiguration(
        metadataId: UUID,
        version: Int,
        configuration: JsonElement?
    )

    /**
     * Sets the filter rules for a collection template, defining how items within collections
     * of this template can be filtered.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param filters the filter configuration, or null to clear
     */
    suspend fun setFilters(
        metadataId: UUID,
        version: Int,
        filters: CollectionTemplateFiltersInput?
    )

    /**
     * Sets the ordering rules for a collection template, defining how items within collections
     * of this template are sorted.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param ordering the list of ordering rules, or null to clear
     */
    suspend fun setOrdering(
        metadataId: UUID,
        version: Int,
        ordering: List<OrderingInput>?
    )
}