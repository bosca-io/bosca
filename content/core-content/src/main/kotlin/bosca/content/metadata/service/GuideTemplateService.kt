package bosca.content.metadata.service

import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.model.GuideTemplateAttribute
import bosca.content.metadata.model.GuideTemplateInput
import bosca.content.metadata.model.GuideTemplateStep
import bosca.content.metadata.model.GuideTemplateStepModule
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing guide templates. Guide templates define the structure, steps,
 * modules, attributes, and recurrence rules that guide instances should conform to.
 * Templates serve as blueprints from which concrete guide instances are created.
 */
interface GuideTemplateService : Service {

    /**
     * Retrieves all guide templates in the system.
     *
     * @return the complete list of guide templates
     */
    suspend fun getAll(): List<GuideTemplate>

    /**
     * Looks up a guide template by its metadata identifier and version.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the guide template, or null if not found
     */
    suspend fun getTemplate(id: UUID, version: Int): GuideTemplate?

    /**
     * Registers a batch loader for efficiently fetching guide templates by metadata cache key.
     *
     * @param batch the batch accumulator to populate with guide template data
     */
    suspend fun addTemplatesToBatch(batch: Batch<MetadataCacheKeyId, GuideTemplate>)

    /**
     * Retrieves the attribute definitions for a guide template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the list of guide template attribute definitions
     */
    suspend fun getTemplateAttributes(id: UUID, version: Int): List<GuideTemplateAttribute>

    /**
     * Registers a batch loader for efficiently fetching guide template attributes by
     * metadata cache key.
     *
     * @param batch the batch accumulator to populate with attribute lists
     */
    suspend fun addTemplateAttributesToBatch(batch: Batch<MetadataCacheKeyId, List<GuideTemplateAttribute>>)

    /**
     * Retrieves a specific step within a guide template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param stepId the step identifier within the template
     * @return the template step, or null if not found
     */
    suspend fun getTemplateStep(id: UUID, version: Int, stepId: Long): GuideTemplateStep?

    /**
     * Retrieves all steps within a guide template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @return the list of template steps
     */
    suspend fun getTemplateSteps(id: UUID, version: Int): List<GuideTemplateStep>

    /**
     * Registers a batch loader for efficiently fetching guide template steps by metadata
     * cache key.
     *
     * @param batch the batch accumulator to populate with step lists
     */
    suspend fun addTemplateStepsToBatch(batch: Batch<MetadataCacheKeyId, List<GuideTemplateStep>>)

    /**
     * Retrieves a specific module within a step of a guide template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param stepId the step identifier containing the module
     * @param moduleId the module identifier
     * @return the template step module
     */
    suspend fun getTemplateStepModule(id: UUID, version: Int, stepId: Long, moduleId: Long): GuideTemplateStepModule

    /**
     * Retrieves a specific module of a guide template by its identifier alone.
     *
     * Module identifiers are globally unique, so no step identifier is required. This is
     * the lookup used when adding a module to a guide step, where only the template
     * module's identifier is known (guide step identifiers are unrelated to template
     * step identifiers).
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param moduleId the module identifier
     * @return the template step module, or null when no such module exists
     */
    suspend fun getTemplateModule(id: UUID, version: Int, moduleId: Long): GuideTemplateStepModule?

    /**
     * Retrieves all modules within a specific step of a guide template.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param stepId the step identifier whose modules should be retrieved
     * @return the list of modules in the specified step
     */
    suspend fun getTemplateStepModules(id: UUID, version: Int, stepId: Long): List<GuideTemplateStepModule>

    /**
     * Registers a batch loader for efficiently fetching guide template step modules by
     * metadata cache key.
     *
     * @param batch the batch accumulator to populate with module lists
     */
    suspend fun addTemplateStepModulesToBatch(batch: Batch<MetadataCacheKeyId, List<GuideTemplateStepModule>>)

    /**
     * Adds a new attribute definition to a guide template at the specified sort position.
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
     * Removes an attribute definition from a guide template by its key.
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
     * Sets the default attribute values for a guide template. These defaults are applied
     * to new guides created from this template.
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
     * Sets the recurrence rule (RRULE) for a guide template, defining the default scheduling
     * pattern for guides created from this template.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param rrule the iCalendar RRULE string, or null to clear
     */
    suspend fun setRrule(
        metadataId: UUID,
        version: Int,
        rrule: String?
    )

    /**
     * Sets the guide type classification for a guide template.
     *
     * @param metadataId the template's metadata identifier
     * @param version the template version number
     * @param type the guide type to assign
     */
    suspend fun setType(
        metadataId: UUID,
        version: Int,
        type: GuideType
    )

    /**
     * Replaces all attribute definitions on a guide template with the provided list.
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
     * Sets the configuration for a guide template, controlling template-specific behavior.
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
     * Adds a step to a guide template, referencing an existing metadata entry as the
     * step definition.
     *
     * @param metadata the template's parent metadata entry
     * @param stepMetadataId the metadata identifier of the step to add
     * @param stepMetadataVersion the version of the step metadata
     */
    suspend fun addStep(metadata: Metadata, stepMetadataId: UUID, stepMetadataVersion: Int)

    /**
     * Adds a module to a step within a guide template, referencing an existing metadata
     * entry as the module definition.
     *
     * @param metadata the template's parent metadata entry
     * @param stepId the step identifier to add the module to
     * @param moduleMetadataId the metadata identifier of the module to add
     * @param moduleMetadataVersion the version of the module metadata
     */
    suspend fun addModule(metadata: Metadata, stepId: Long, moduleMetadataId: UUID, moduleMetadataVersion: Int)

    /**
     * Reorders the steps within a guide template according to the specified sequence.
     *
     * @param metadata the template's parent metadata entry
     * @param stepIds the ordered list of step identifiers defining the new order
     */
    suspend fun reorderSteps(metadata: Metadata, stepIds: List<Long>)

    /**
     * Reorders the modules within a step of a guide template according to the specified
     * sequence.
     *
     * @param metadata the template's parent metadata entry
     * @param stepId the step identifier whose modules should be reordered
     * @param moduleIds the ordered list of module identifiers defining the new order
     */
    suspend fun reorderModules(metadata: Metadata, stepId: Long, moduleIds: List<Long>)

    /**
     * Removes a step from a guide template.
     *
     * @param metadata the template's parent metadata entry
     * @param stepId the step identifier to remove
     */
    suspend fun removeStep(metadata: Metadata, stepId: Long)

    /**
     * Removes a module from a step within a guide template.
     *
     * @param metadata the template's parent metadata entry
     * @param stepId the step identifier containing the module
     * @param moduleId the module identifier to remove
     */
    suspend fun removeModule(metadata: Metadata, stepId: Long, moduleId: Long)

    /**
     * Persists a complete guide template definition, creating or updating it.
     *
     * @param id the template's metadata identifier
     * @param version the template version number
     * @param template the complete template definition to save
     */
    suspend fun saveTemplate(id: UUID, version: Int, template: GuideTemplateInput)
}