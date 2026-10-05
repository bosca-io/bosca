package bosca.content.transition.history.service

import bosca.service.Service
import bosca.content.transition.history.model.CollectionTransitionHistory
import bosca.content.transition.history.model.MetadataTransitionHistory

/**
 * Service for recording the history of workflow state transitions. Each time a content
 * item (metadata or collection) moves from one workflow state to another, a history
 * entry is created to provide an audit trail.
 */
interface TransitionHistoryService : Service {

    /**
     * Records a workflow state transition for a metadata entry.
     *
     * @param history the transition history entry to persist
     * @return the persisted transition history entry
     */
    suspend fun add(history: MetadataTransitionHistory): MetadataTransitionHistory

    /**
     * Records a workflow state transition for a collection.
     *
     * @param history the transition history entry to persist
     * @return the persisted transition history entry
     */
    suspend fun add(history: CollectionTransitionHistory): CollectionTransitionHistory
}