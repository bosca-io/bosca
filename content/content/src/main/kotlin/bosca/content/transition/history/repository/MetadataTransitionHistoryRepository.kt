package bosca.content.transition.history.repository

import bosca.content.transition.history.model.MetadataTransitionHistory
import bosca.db.annotation.Query
import bosca.db.annotation.Repository

@Repository
interface MetadataTransitionHistoryRepository {

    @Query("insert into metadata_workflow_transition_history (metadata_id, from_state_id, to_state_id, principal, status, success, complete) values (:metadataId, :fromStateId, :toStateId, :principal, :status, :success, :complete) returning *")
    suspend fun add(history: MetadataTransitionHistory): MetadataTransitionHistory
}