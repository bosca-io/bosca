package bosca.content.collection.repository

import bosca.content.collection.model.CollectionWorkflowPlan
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CollectionWorkflowPlanRepository {

    @Query("select * from collection_workflow_plans where id = :id")
    suspend fun getById(id: UUID): List<CollectionWorkflowPlan>
}