package bosca.content.metadata.service

import bosca.attributes.TemplateAttributeInput
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.DocumentTemplateAttributeWorkflow
import bosca.content.metadata.model.DocumentTemplateContainer
import bosca.content.metadata.model.DocumentTemplateContainerInput
import bosca.content.metadata.model.DocumentTemplateInput
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing document templates. Document templates define the schema, attributes,
 * content structure, containers, and configuration that documents should conform to.
 * Containers within a document template define the structural regions (e.g., header, body,
 * sidebar) that documents of this template type can populate.
 */
interface DocumentTemplateService : Service {

    /**
     * Retrieves all document templates in the system.
     *
     * @return the complete list of document templates
     */
    suspend fun getAll(): List<DocumentTemplate>

    /**
     * Looks up a document template by its metadata identifier and version.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the document template, or null if not found
     */
    suspend fun getTemplate(id: UUID, version: Int): DocumentTemplate?

    /**
     * Registers a batch loader for efficiently fetching document templates by metadata cache key.
     *
     * @param batch the batch accumulator to populate with document template data
     */
    suspend fun addToBatch(batch: Batch<MetadataCacheKeyId, DocumentTemplate>)

    /**
     * Retrieves the attribute definitions for a document template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the list of template attribute definitions
     */
    suspend fun getTemplateAttributes(id: UUID, version: Int): List<TemplateAttribute>

    /**
     * Retrieves the container definitions for a document template. Containers define
     * the structural regions that documents of this template can use.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the list of container definitions
     */
    suspend fun getTemplateContainers(id: UUID, version: Int): List<DocumentTemplateContainer>

    /**
     * Retrieves the workflow configurations associated with a specific attribute key
     * on a document template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param key the attribute key whose workflows should be retrieved
     * @return the list of attribute workflow configurations
     */
    suspend fun getTemplateAttributeWorkflows(id: UUID, version: Int, key: String): List<DocumentTemplateAttributeWorkflow>

    /**
     * Adds a new attribute definition to a document template at the specified sort position.
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
     * Removes an attribute definition from a document template by its key.
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
     * Sets the default attribute values for a document template. These defaults are applied
     * to new documents created from this template.
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
     * Replaces all attribute definitions on a document template with the provided list.
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
     * Sets the configuration for a document template, controlling template-specific behavior.
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
     * Sets the JSON schema for a document template, defining the validation rules for
     * document content.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param schema the JSON schema definition, or null to clear
     */
    suspend fun setSchema(
        metadataId: UUID,
        version: Int,
        schema: JsonElement?
    )

    /**
     * Sets the default content structure for a document template. This content is used as
     * the initial state when creating new documents from this template.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param content the default content JSON, or null to clear
     */
    suspend fun setContent(
        metadataId: UUID,
        version: Int,
        content: JsonElement?
    )

    /**
     * Adds a new container definition to a document template at the specified sort position.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param container the container definition to add
     * @param sort the sort position for the new container
     */
    suspend fun addContainer(
        metadataId: UUID,
        version: Int,
        container: DocumentTemplateContainerInput,
        sort: Int
    )

    /**
     * Removes a container definition from a document template.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param containerId the identifier of the container to remove
     */
    suspend fun deleteContainer(
        metadataId: UUID,
        version: Int,
        containerId: String,
    )

    /**
     * Replaces all container definitions on a document template with the provided list.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param containers the complete list of container definitions to set
     */
    suspend fun setContainers(
        metadataId: UUID,
        version: Int,
        containers: List<DocumentTemplateContainerInput>
    )

    /**
     * Persists a complete document template definition, creating or updating it.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param template the complete template definition to save
     */
    suspend fun saveTemplate(id: UUID, version: Int, template: DocumentTemplateInput)
}