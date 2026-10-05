package bosca.content.state.service

import bosca.content.state.model.State
import bosca.content.state.model.StateInput
import bosca.service.Service

/**
 * Service for managing workflow states. States represent the discrete stages that content
 * items (metadata and collections) move through during their lifecycle (e.g., draft,
 * review, published).
 */
interface StateService : Service {

    /**
     * Retrieves all workflow states defined in the system.
     *
     * @return the complete list of workflow states
     */
    suspend fun getAll(): List<State>

    /**
     * Looks up a workflow state by its string identifier.
     *
     * @param id the state identifier
     * @return the workflow state, or null if not found
     */
    suspend fun get(id: String): State?

    /**
     * Creates a new workflow state from the given input.
     *
     * @param input the state definition to create
     * @return the newly created workflow state
     */
    suspend fun add(input: StateInput): State

    /**
     * Updates an existing workflow state with new values.
     *
     * @param id the state identifier to update
     * @param input the updated state definition
     * @return the modified workflow state
     */
    suspend fun edit(id: String, input: StateInput): State

    /**
     * Deletes a workflow state by its identifier.
     *
     * @param id the state identifier to delete
     */
    suspend fun delete(id: String)
}