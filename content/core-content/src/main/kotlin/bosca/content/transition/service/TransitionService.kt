package bosca.content.transition.service

import bosca.content.transition.model.Transition
import bosca.content.transition.model.TransitionInput
import bosca.service.Service

/**
 * Service for managing workflow transitions between states. Transitions define the
 * allowed movements between workflow states (e.g., from "draft" to "review") and any
 * associated rules or actions.
 */
interface TransitionService : Service {

    /**
     * Retrieves all workflow transitions defined in the system.
     *
     * @return the complete list of workflow transitions
     */
    suspend fun getAll(): List<Transition>

    /**
     * Looks up a workflow transition by its source and destination state identifiers.
     *
     * @param fromStateId the source state identifier
     * @param toStateId the destination state identifier
     * @return the transition between the two states, or null if not found
     */
    suspend fun get(fromStateId: String, toStateId: String): Transition?

    /**
     * Creates a new workflow transition from the given input.
     *
     * @param input the transition definition to create
     * @return the newly created workflow transition
     */
    suspend fun add(input: TransitionInput): Transition

    /**
     * Updates an existing workflow transition with new values.
     *
     * @param input the updated transition definition
     * @return the modified workflow transition
     */
    suspend fun edit(input: TransitionInput): Transition

    /**
     * Deletes a workflow transition between two states.
     *
     * @param fromStateId the source state identifier
     * @param toStateId the destination state identifier
     */
    suspend fun delete(fromStateId: String, toStateId: String)
}
