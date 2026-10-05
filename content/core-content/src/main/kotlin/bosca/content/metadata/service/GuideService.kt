package bosca.content.metadata.service

import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideInput
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepInput
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.GuideStepModuleInput
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing guide content associated with metadata entries. Guides are structured
 * content items composed of ordered steps, where each step contains modules. Guides support
 * recurrence rules (RRULE) for scheduling and can be versioned alongside their parent metadata.
 */
interface GuideService : Service {

    /**
     * Invalidates cached guide data for a specific metadata identifier and optional version.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version to remove from cache, or null to remove all versions
     */
    suspend fun removeFromCache(id: UUID, version: Int?)

    /**
     * Retrieves a guide by its parent metadata identifier and version.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @return the guide, or null if not found
     */
    suspend fun getGuide(id: UUID, version: Int): Guide?

    /**
     * Registers a batch loader for efficiently fetching guides by metadata cache key.
     *
     * @param batch the batch accumulator to populate with guide data
     */
    suspend fun addGuidesToBatch(batch: Batch<MetadataCacheKeyId, Guide>)

    /**
     * Returns the total number of steps in a guide.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @return the step count
     */
    suspend fun getStepCount(id: UUID, version: Int): Long

    /**
     * Registers a batch loader for efficiently fetching guide step counts by metadata cache key.
     *
     * @param batch the batch accumulator to populate with step count data
     */
    suspend fun addStepCountsToBatch(batch: Batch<MetadataCacheKeyId, Long>)

    /**
     * Retrieves a specific step within a guide.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param stepId the step identifier within the guide
     * @return the guide step, or null if not found
     */
    suspend fun getGuideStep(id: UUID, version: Int, stepId: Long): GuideStep?

    /**
     * Registers a batch loader for efficiently fetching guide steps by metadata cache key.
     *
     * @param batch the batch accumulator to populate with step lists
     */
    suspend fun addGuideStepsToBatch(batch: Batch<MetadataCacheKeyId, List<GuideStep>>)

    /**
     * Retrieves the steps within a guide, with optional pagination.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param offset optional zero-based offset for pagination
     * @param limit optional maximum number of steps to return
     * @return the list of guide steps
     */
    suspend fun getGuideSteps(id: UUID, version: Int, offset: Int? = null, limit: Int? = null): List<GuideStep>

    /**
     * Retrieves all modules within a specific step of a guide.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param stepId the step identifier within the guide
     * @return the list of modules in the specified step
     */
    suspend fun getGuideStepModules(id: UUID, version: Int, stepId: Long): List<GuideStepModule>

    /**
     * Registers a batch loader for efficiently fetching guide step modules by metadata cache key.
     *
     * @param batch the batch accumulator to populate with step module lists
     */
    suspend fun addGuideStepModulesToBatch(batch: Batch<MetadataCacheKeyId, List<GuideStepModule>>)

    /**
     * Sets the recurrence rule (RRULE) for a guide, defining its scheduling pattern
     * (e.g., daily, weekly).
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param rrule the iCalendar RRULE string
     */
    suspend fun setGuideRrule(id: UUID, version: Int, rrule: String)

    /**
     * Sets the navigation and progress tracking mode ([GuideType]) for a guide, changing how
     * users move through its steps (e.g. switching between linear and calendar navigation).
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param type the new guide type
     */
    suspend fun setGuideType(id: UUID, version: Int, type: GuideType)

    /**
     * Updates the sort position of a step within a guide.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param stepId the step identifier to reposition
     * @param sort the new sort position
     */
    suspend fun setGuideStepSort(id: UUID, version: Int, stepId: Long, sort: Int)

    /**
     * Creates a new guide for the specified metadata version.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param guide the guide definition to persist
     * @return the newly created guide
     */
    suspend fun addGuide(id: UUID, version: Int, guide: GuideInput): Guide

    /**
     * Adds a new step to a guide at the specified index position.
     *
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     * @param step the step definition to add
     * @param index the zero-based position at which to insert the step
     * @return the newly created guide step
     */
    suspend fun addGuideStep(metadataId: UUID, version: Int, step: GuideStepInput, index: Int): GuideStep

    /**
     * Adds a new module to a step within a guide at the specified index position.
     *
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     * @param stepId the step identifier to add the module to
     * @param module the module definition to add
     * @param index the zero-based position at which to insert the module
     * @return the newly created guide step module
     */
    suspend fun addGuideStepModule(metadataId: UUID, version: Int, stepId: Long, module: GuideStepModuleInput, index: Int): GuideStepModule

    /**
     * Reorders steps within a guide by assigning new sort positions based on the provided
     * step ID sequence. Each step's sort value is set to its index in the list.
     *
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     * @param stepIds the desired ordering of step IDs
     */
    suspend fun reorderSteps(metadataId: UUID, version: Int, stepIds: List<Long>)

    /**
     * Reorders modules within a guide step by assigning new sort positions based on the provided
     * module ID sequence. Each module's sort value is set to its index in the list.
     *
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     * @param stepId the step identifier containing the modules to reorder
     * @param moduleIds the desired ordering of module IDs
     */
    suspend fun reorderModules(metadataId: UUID, version: Int, stepId: Long, moduleIds: List<Long>)

    /**
     * Deletes a guide and its associated steps and modules from a metadata entry.
     * Uses the [MetadataService] to coordinate the deletion with parent metadata state.
     *
     * @param svc the metadata service for coordinating the deletion
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     */
    suspend fun deleteGuide(svc: MetadataService, metadataId: UUID, version: Int)

    /**
     * Deletes a specific step and its modules from a guide.
     * Uses the [MetadataService] to coordinate the deletion with parent metadata state.
     *
     * @param svc the metadata service for coordinating the deletion
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     * @param stepId the step identifier to delete
     */
    suspend fun deleteGuideStep(svc: MetadataService, metadataId: UUID, version: Int, stepId: Long)

    /**
     * Deletes a specific module from a guide step.
     * Uses the [MetadataService] to coordinate the deletion with parent metadata state.
     *
     * @param svc the metadata service for coordinating the deletion
     * @param metadataId the parent metadata identifier
     * @param version the metadata version number
     * @param stepId the step identifier containing the module
     * @param moduleId the module identifier to delete
     */
    suspend fun deleteGuideStepModule(svc: MetadataService, metadataId: UUID, version: Int, stepId: Long, moduleId: Long)
}