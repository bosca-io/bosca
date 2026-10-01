package bosca.experimentation.service

import bosca.experimentation.model.ExclusionLayer
import bosca.experimentation.model.ExclusionLayerInput
import bosca.experimentation.model.Experiment
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing mutual exclusion layers that prevent users from
 * being enrolled in multiple conflicting experiments simultaneously.
 *
 * Exclusion layers partition experiment traffic so that a user assigned
 * to one experiment within a layer is excluded from all other experiments
 * in the same layer, ensuring clean measurement without interaction effects.
 */
interface ExclusionLayerService : Service {

    /**
     * Retrieves all exclusion layers.
     *
     * @return the complete list of exclusion layers
     */
    suspend fun getAll(): List<ExclusionLayer>

    /**
     * Retrieves a single exclusion layer by its unique identifier.
     *
     * @param id the UUID of the exclusion layer
     * @return the matching layer, or `null` if not found
     */
    suspend fun getById(id: UUID): ExclusionLayer?

    /**
     * Creates a new exclusion layer.
     *
     * @param input the layer definition
     * @return the newly created exclusion layer
     */
    suspend fun add(input: ExclusionLayerInput): ExclusionLayer

    /**
     * Updates an existing exclusion layer's definition.
     *
     * @param id the UUID of the layer to update
     * @param input the updated layer definition
     * @return the modified exclusion layer
     */
    suspend fun edit(id: UUID, input: ExclusionLayerInput): ExclusionLayer

    /**
     * Permanently deletes an exclusion layer. Experiments referencing
     * this layer will have their exclusion layer reference set to null.
     *
     * @param id the UUID of the layer to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Retrieves all experiments assigned to a specific exclusion layer.
     *
     * @param layerId the UUID of the exclusion layer
     * @return the list of experiments in this layer
     */
    suspend fun getExperiments(layerId: UUID): List<Experiment>

    /**
     * Retrieves a page of experiments assigned to a specific exclusion layer,
     * ordered by creation date descending.
     *
     * @param layerId the UUID of the exclusion layer
     * @param offset zero-based row offset
     * @param limit maximum number of rows to return
     * @return the matching page of experiments
     */
    suspend fun getExperiments(layerId: UUID, offset: Long, limit: Int): List<Experiment>
}
