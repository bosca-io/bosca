package bosca.content.metadata.service

import bosca.attributes.TemplateAttributeInput
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.DataTemplateAttributeWorkflow
import bosca.content.metadata.model.DataTemplateInput
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing data templates. Data templates define the schema, attribute structure,
 * and type classification that data objects should conform to.
 */
interface DataTemplateService : Service {

    /**
     * Retrieves all data templates in the system.
     *
     * @return the complete list of data templates
     */
    suspend fun getAll(): List<DataTemplate>

    /**
     * Looks up a data template by its metadata identifier and version.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the data template, or null if not found
     */
    suspend fun getTemplate(id: UUID, version: Int): DataTemplate?

    /**
     * Registers a batch loader for efficiently fetching data templates by metadata cache key.
     *
     * @param batch the batch accumulator to populate with data template objects
     */
    suspend fun addToBatch(batch: Batch<MetadataCacheKeyId, DataTemplate>)

    /**
     * Retrieves the attribute definitions for a data template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the list of template attribute definitions
     */
    suspend fun getTemplateAttributes(id: UUID, version: Int): List<TemplateAttribute>

    /**
     * Retrieves the workflow configurations associated with a specific attribute key
     * on a data template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param key the attribute key whose workflows should be retrieved
     * @return the list of attribute workflow configurations
     */
    suspend fun getTemplateAttributeWorkflows(id: UUID, version: Int, key: String): List<DataTemplateAttributeWorkflow>

    /**
     * Adds a new attribute definition to a data template at the specified sort position.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param attribute the attribute definition to add
     * @param sort the sort position for the new attribute
     */
    suspend fun addAttribute(
        metadataId: UUID,
        version: Int,
        attribute: TemplateAttributeInput,
        sort: Int
    )

    /**
     * Removes an attribute definition from a data template by its key.
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
     * Sets the default attribute values for a data template. These defaults are applied
     * to new data objects created from this template.
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
     * Replaces all attribute definitions on a data template with the provided list.
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
     * Sets the data type classification for a data template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param type the data type to assign
     */
    suspend fun setType(
        id: UUID,
        version: Int,
        type: DataType
    )

    /**
     * Persists a complete data template definition, creating or updating it.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param template the complete template definition to save
     */
    suspend fun saveTemplate(id: UUID, version: Int, template: DataTemplateInput)
}