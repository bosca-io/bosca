package bosca.content.transition.service

import bosca.content.collection.model.ContentItem
import bosca.security.model.Principal
import bosca.serialization.OffsetDateTime

/**
 * Service interface for managing workflow state transitions on content items. Provides
 * operations for initiating, completing, and failing state transitions through a
 * pending-state mechanism. The pending state pattern allows transitions to be initiated
 * asynchronously, with the item entering a "pending" intermediate state before the
 * transition is confirmed or rolled back.
 *
 * @param T the content item type, which must implement [ContentItem]
 */
interface TransitioningService<T : ContentItem> {

    /**
     * Initiates a state transition by setting a pending destination state on a content item.
     * The item remains in its current state but records the intended transition target.
     *
     * @param item the content item to transition
     * @param toStateId the destination state identifier
     * @param status a status message describing the transition
     * @param valid an optional timestamp indicating when the transition becomes valid
     * @param principal the authenticated principal initiating the transition, or null if system-initiated
     * @param notifyEvent whether to emit a workflow event notification for this transition (defaults to true)
     * @return the updated content item with the pending state set
     */
    suspend fun setPendingState(
        item: T,
        toStateId: String,
        status: String,
        valid: OffsetDateTime? = null,
        principal: Principal? = null,
        notifyEvent: Boolean = true
    ): T

    /**
     * Confirms a pending state transition, moving the content item from its current state
     * to the previously set pending destination state.
     *
     * @param item the content item whose pending transition should be completed
     * @param status a status message describing the completion
     * @param principal the authenticated principal completing the transition, or null if system-initiated
     * @return the updated content item in its new state
     */
    suspend fun setPendingStateComplete(item: T, status: String, principal: Principal? = null): T

    /**
     * Marks a pending state transition as failed, clearing the pending state and leaving
     * the content item in its current state.
     *
     * @param item the content item whose pending transition failed
     * @param status a status message describing the failure
     * @param principal the authenticated principal recording the failure, or null if system-initiated
     * @return the updated content item with the pending state cleared
     */
    suspend fun setPendingStateFailed(item: T, status: String, principal: Principal? = null): T

    /**
     * Directly transitions a content item to a new state without going through the
     * pending-state mechanism. Use this for immediate, synchronous state changes.
     *
     * @param item the content item to transition
     * @param toStateId the destination state identifier
     * @param status a status message describing the transition
     * @param principal the authenticated principal performing the transition, or null if system-initiated
     * @return the updated content item in its new state
     */
    suspend fun setState(
        item: T,
        toStateId: String,
        status: String,
        principal: Principal? = null
    ): T
}