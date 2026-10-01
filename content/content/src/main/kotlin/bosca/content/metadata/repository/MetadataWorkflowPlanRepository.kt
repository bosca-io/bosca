package bosca.content.metadata.repository

import bosca.content.metadata.model.MetadataWorkflowPlan
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID


@Repository
interface MetadataWorkflowPlanRepository {

    @Query("select * from metadata_workflow_plans where id = :id")
    suspend fun getById(id: UUID): List<MetadataWorkflowPlan>
}