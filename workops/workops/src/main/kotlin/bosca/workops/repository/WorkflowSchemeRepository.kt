package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.workflow.WorkflowScheme
import kotlinx.serialization.json.JsonElement

/**
 * Reads and writes [WorkflowScheme] rows. The per-task-type override
 * map is stored as a `jsonb` `Map<UUID, UUID>` so a single column
 * carries the full mapping without a junction table; callers
 * serialize / deserialize at the service boundary.
 */
@Repository
interface WorkflowSchemeRepository {

    @Query("select * from workops.workflow_scheme order by name")
    suspend fun listAll(): List<WorkflowScheme>

    @Query("select * from workops.workflow_scheme where id = :id")
    suspend fun getById(id: UUID): WorkflowScheme?

    @Query("select * from workops.workflow_scheme where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<WorkflowScheme>

    @Query(
        """
        insert into workops.workflow_scheme (name, description, default_workflow_id, per_task_type_workflow_ids)
        values (:name, :description, :defaultWorkflowId, :perTaskTypeWorkflowIds::jsonb)
        returning *
        """
    )
    suspend fun add(
        name: String,
        description: String?,
        defaultWorkflowId: UUID,
        perTaskTypeWorkflowIds: JsonElement,
    ): WorkflowScheme

    @Query(
        """
        update workops.workflow_scheme
        set name = :name,
            description = :description,
            default_workflow_id = :defaultWorkflowId,
            per_task_type_workflow_ids = :perTaskTypeWorkflowIds::jsonb,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(
        id: UUID,
        name: String,
        description: String?,
        defaultWorkflowId: UUID,
        perTaskTypeWorkflowIds: JsonElement,
        expectedVersion: Long,
    ): WorkflowScheme?

    @Query("delete from workops.workflow_scheme where id = :id")
    suspend fun deleteById(id: UUID)
}
