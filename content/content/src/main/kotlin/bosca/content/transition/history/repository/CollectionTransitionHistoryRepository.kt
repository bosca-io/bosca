package bosca.content.transition.history.repository

import bosca.content.transition.history.model.CollectionTransitionHistory
import bosca.db.annotation.Query
import bosca.db.annotation.Repository

@Repository
interface CollectionTransitionHistoryRepository {

    @Query("insert into collection_workflow_transition_history (collection_id, language_tag, from_state_id, to_state_id, principal, status, success, complete) values (:collectionId, :languageTag, :fromStateId, :toStateId, :principal, :status, :success, :complete) returning *")
    suspend fun add(history: CollectionTransitionHistory): CollectionTransitionHistory
}